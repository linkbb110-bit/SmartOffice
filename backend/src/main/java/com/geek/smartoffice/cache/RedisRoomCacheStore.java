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
    private static final DefaultRedisScript<String> VERSION = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current then return current end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return ARGV[1]
            """, String.class);
    private static final DefaultRedisScript<Long> REFILL = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
            return 1
            """, Long.class);
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

    private static String key(long id, String suffix) { return "smartoffice:{room:" + id + "}:" + suffix; }

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
            if (!lock.tryLock(100, 5000, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted acquiring room cache lock", interrupted);
        }
        return () -> { if (lock.isHeldByCurrentThread()) lock.unlock(); };
    }

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
