package com.geek.smartoffice.cache;

import com.geek.smartoffice.model.entity.MeetingRoom;

/**
 * 把缓存协调流程与 Redis 实现分开：上层决定何时回源，这里提供读、版本校验和租约操作。
 * 数据存储异常由实现抛出，RoomCache 决定降级；缓存版本只协调缓存回填，不代替数据库事务。
 */
public interface RoomCacheStore {
    // record 将命中标记与内容组成一个结果；仅用 room==null 无法区分“未命中”和“已确认不存在”。
    // present=true 且 room=null 表示命中空值缓存，调用方可直接返回 null，避免重复查不存在的 ID。
    record CachedRoom(boolean present, MeetingRoom room) {
        public static CachedRoom miss() {
            return new CachedRoom(false, null); }
    }

    // Lease 是成功取得回源锁后的释放凭据；未取得锁用 null 表示。
    // 继承 AutoCloseable 表达“使用后必须 close”，此处由 RoomCache 的 finally 确保释放。
    interface Lease extends AutoCloseable {
        @Override void close();
    }

    CachedRoom read(long roomId);
    // 读取或创建版本快照；只有快照未变化，putIfVersion 才允许写入此次回源结果。
    String version(long roomId);
    Lease tryAcquire(long roomId);
    boolean putIfVersion(long roomId, String version, MeetingRoom room);
    void invalidate(long roomId);
}
