package com.geek.smartoffice.cache;

import com.geek.smartoffice.model.entity.MeetingRoom;

public interface RoomCacheStore {
    record CachedRoom(boolean present, MeetingRoom room) {
        public static CachedRoom miss() { return new CachedRoom(false, null); }
    }

    interface Lease extends AutoCloseable {
        @Override void close();
    }

    CachedRoom read(long roomId);
    String version(long roomId);
    Lease tryAcquire(long roomId);
    boolean putIfVersion(long roomId, String version, MeetingRoom room);
    void invalidate(long roomId);
}
