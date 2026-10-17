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
        if (lease == null) return loader.get(); // A busy lock is never permission to refill.
        try {
            String version;
            try {
                var cached = store.read(roomId);
                if (cached.present()) return cached.room();
                version = store.version(roomId);
            } catch (RuntimeException failure) {
                unavailable(roomId, "read/version", failure);
                return loader.get();
            }
            // Keep database failures out of Redis fallback handling: never execute the loader twice.
            MeetingRoom room = loader.get();
            try {
                store.putIfVersion(roomId, version, room);
            } catch (RuntimeException failure) {
                unavailable(roomId, "refill", failure);
            }
            return room;
        } finally {
            try { lease.close(); }
            catch (RuntimeException failure) { unavailable(roomId, "unlock", failure); }
        }
    }

    public void invalidate(long roomId) {
        try { store.invalidate(roomId); }
        catch (RuntimeException failure) { unavailable(roomId, "invalidate", failure); }
    }

    private void unavailable(long roomId, String stage, RuntimeException failure) {
        log.warn("room cache unavailable roomId={} stage={} type={}, using database/TTL fallback",
                roomId, stage, failure.getClass().getSimpleName());
    }
}
