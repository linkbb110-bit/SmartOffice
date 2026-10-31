package com.geek.smartoffice.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geek.smartoffice.model.entity.MeetingRoom;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RedisRoomCacheStore implements RoomCacheStore {
    private static final String NULL_VALUE = "null";
    // Lua 的 KEYS 保存参与操作的 Redis 键，ARGV 保存普通参数，两者均按 1 开始索引。
    // VERSION 的 KEYS[1] 为版本键，ARGV 依次为候选版本、TTL 秒数；已有版本直接返回，且不续期。
    // 脚本在 Redis 端连续执行，避免“先 GET 再 SET”被其他请求插入；String.class 指定返回值按字符串接收。
    private static final DefaultRedisScript<String> VERSION = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current then return current end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return ARGV[1]
            """, String.class);
    // REFILL 的 KEYS 依次为版本键、详情键，ARGV 依次为回源前的版本、缓存内容、TTL 秒数。
    // 比较版本与写详情在同一脚本中完成，避免检查通过后又被失效操作穿插；版本缺失也视为不匹配。
    // Redis 整数结果用 Long.class 接收：1 表示写入，0 表示版本已变或过期，本次结果不再入缓存。
    private static final DefaultRedisScript<Long> REFILL = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
            return 1
            """, Long.class);
    // INVALIDATE 的 KEYS 依次为版本键、详情键，ARGV 为新版本及其 TTL 秒数，返回 Long 类型的 1。
    // 换版本与删除详情在 Redis 端原子执行，使正在回源的旧版本请求无法把旧值重新填回。
    // 原子性只覆盖该脚本的 Redis 操作，不包含数据库提交；脚本执行失败仍需由详情 TTL 限制陈旧时间。
    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ObjectMapper json;
    private final long valueTtl;
    private final long nullTtl;
    private final long versionTtl;

    public RedisRoomCacheStore(StringRedisTemplate redis, RedissonClient redisson, ObjectMapper json,
                              @Value("${smartoffice.room-cache.value-ttl-seconds:180}") long valueTtl,
                              @Value("${smartoffice.room-cache.null-ttl-seconds:30}") long nullTtl,
                              @Value("${smartoffice.room-cache.version-ttl-seconds:600}") long versionTtl) {
        // 版本比普通值和空值存活更久，减少详情尚未过期时就失去版本标记的窗口。
        // 版本最终仍会过期；届时旧快照回填会失败，而不是绕过版本检查重新创建详情。
        if (valueTtl < 1 || nullTtl < 1 || versionTtl <= Math.max(valueTtl, nullTtl)) {
            throw new IllegalArgumentException("Room cache TTLs must be positive and version TTL must exceed both value TTLs");
        }
        this.redis = redis;
        this.redisson = redisson;
        this.json = json;
        this.valueTtl = valueTtl;
        this.nullTtl = nullTtl;
        this.versionTtl = versionTtl;
    }

    // 花括号中的 room:id 是 Redis Cluster 的哈希标签，同一房间的版本键、详情键会落在同一槽位。
    // 多键 Lua 要求相关键处于同槽；不同房间仍可分散存储，避免把所有房间集中到一个槽位。
    private static String key(long id, String suffix) { return "smartoffice:{room:" + id + "}:" + suffix; }

    // Redis 没有键表示未命中；字面字符串 "null" 则是有 TTL 的空值缓存，不能当作未命中。
    // 损坏的 JSON 抛为运行时异常，让上层按缓存故障回源，不将无法解析的数据伪装成房间不存在。
    @Override public CachedRoom read(long roomId) {
        String value = redis.opsForValue().get(key(roomId, "detail"));
        if (value == null) return CachedRoom.miss();
        if (NULL_VALUE.equals(value)) return new CachedRoom(true, null);
        try { return new CachedRoom(true, json.readValue(value, MeetingRoom.class)); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid room cache JSON", failure); }
    }

    @Override public String version(long roomId) {
        String version = redis.execute(VERSION, List.of(key(roomId, "version")), UUID.randomUUID().toString(), String.valueOf(versionTtl));
        if (version == null) throw new IllegalStateException("Redis returned no room version");
        return version;
    }

    @Override public Lease tryAcquire(long roomId) {
        var lock = redisson.getLock(key(roomId, "load-lock"));
        try {
            // 这里显式给出 5 秒租期，最多等待 100ms；它与预约入口使用看门狗续期的锁用法不同。
            // 慢查询可能超过租期，导致其他请求也回源，因此这把锁只减少并发重建，回填仍必须校验版本。
            if (!lock.tryLock(100, 5000, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted acquiring room cache lock", interrupted);
        }
        // Lambda 实现 Lease.close()，创建时不立即解锁，而由 RoomCache 在 finally 中调用。
        // 先检查当前线程是否仍持有锁，避免固定租期已到后误释放其他请求持有的锁。
        return () -> { if (lock.isHeldByCurrentThread()) lock.unlock(); };
    }

    // 不存在的房间使用独立 nullTtl 缓存，减少无效 ID 穿透；默认空值存活 30 秒，真实房间存活 180 秒。
    // 序列化或 Redis 执行异常交由上层降级；返回 false 表示未确认写入成功，正常脚本返回 0 时是版本不符。
    @Override public boolean putIfVersion(long roomId, String version, MeetingRoom room) {
        String value;
        try { value = room == null ? NULL_VALUE : json.writeValueAsString(room); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot serialize room cache", failure); }
        Long filled = redis.execute(REFILL, List.of(key(roomId, "version"), key(roomId, "detail")),
                version, value, String.valueOf(room == null ? nullTtl : valueTtl));
        return Long.valueOf(1).equals(filled);
    }

    @Override public void invalidate(long roomId) {
        Long invalidated = redis.execute(INVALIDATE, List.of(key(roomId, "version"), key(roomId, "detail")),
                UUID.randomUUID().toString(), String.valueOf(versionTtl));
        if (!Long.valueOf(1).equals(invalidated)) throw new IllegalStateException("Room cache invalidation did not complete");
    }
}
