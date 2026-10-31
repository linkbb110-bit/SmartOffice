package com.geek.smartoffice.cache;

import com.geek.smartoffice.model.entity.MeetingRoom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
@Slf4j
@RequiredArgsConstructor
public class RoomCache {
    private final RoomCacheStore store;

    /**
     * 按房间 ID 读取详情，优先使用缓存；返回 null 也可以是已缓存的“不存在”。
     * {@code Supplier<MeetingRoom>} 表示一个无参数、返回房间的延迟操作，只有调用 get() 才真正回源。
     * 命中缓存时不执行 loader；需要回源时，每条执行路径只调用一次，数据库异常直接交给调用方。
     * Redis 的读取、加锁、版本或回填故障只降低缓存能力，不把数据库查询故障误当作缓存故障重试。
     */
    public MeetingRoom get(long roomId, Supplier<MeetingRoom> loader) {
        RoomCacheStore.Lease lease;
        try {
            var cached = store.read(roomId);
            if (cached.present()) return cached.room();
            lease = store.tryAcquire(roomId);
        } catch (RuntimeException failure) {
            unavailable(roomId, "read/lock", failure);
            return loader.get();
        }
        // 锁竞争失败时立即回源，避免请求一直排队；缓存重建锁只用于合并正常情况下的回源请求。
        // 这不会阻塞数据库的正常读取，是否允许回填则由是否持有租约决定。
        if (lease == null) return loader.get(); // 未拿到回源锁时只读数据库，不回填缓存。
        try {
            String version;
            try {
                // 第一次未命中到取得锁之间，其他请求可能已经完成回填，因此需要“锁后再查”。
                // 仍未命中才取版本快照；后续回填必须与这次快照一致，不能覆盖更新后的缓存状态。
                var cached = store.read(roomId);
                if (cached.present()) return cached.room();
                version = store.version(roomId);
            } catch (RuntimeException failure) {
                unavailable(roomId, "read/version", failure);
                return loader.get();
            }
            // loader 由调用方提供，例如查询会议室表；执行位置刻意放在 Redis 的 catch 之外。
            // 数据库失败直接上抛，不能因“缓存降级”再次查询，避免重复回源和掩盖真正的数据库故障。
            MeetingRoom room = loader.get();
            try {
                // 查询期间若缓存成功失效，版本会改变，旧查询结果便不能重新写入详情缓存。
                // 版本不符只放弃回填，当前请求仍返回自己查到的结果，不承诺跨数据库与 Redis 的强一致读取。
                store.putIfVersion(roomId, version, room);
            } catch (RuntimeException failure) {
                unavailable(roomId, "refill", failure);
            }
            return room;
        } finally {
            // 即使锁后命中而提前 return，或 loader 抛异常，也要关闭取得的租约。
            // 解锁失败只记日志，避免覆盖数据库原异常或使已查到的房间无法返回。
            try { lease.close(); }
            catch (RuntimeException failure) { unavailable(roomId, "unlock", failure); }
        }
    }

    // 失效失败不会回滚已经提交的数据库修改，旧详情可能继续保留至自身 TTL 到期。
    // 此时版本也可能尚未切换，版本校验不能补救这次失败，仍需依靠有限 TTL 收敛陈旧数据。
    public void invalidate(long roomId) {
        try { store.invalidate(roomId); }
        catch (RuntimeException failure) { unavailable(roomId, "invalidate", failure); }
    }

    private void unavailable(long roomId, String stage, RuntimeException failure) {
        log.warn("room cache unavailable roomId={} stage={} type={}, using database/TTL fallback",
                roomId, stage, failure.getClass().getSimpleName());
    }
}
