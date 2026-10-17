package com.geek.smartoffice.cache;

import com.geek.smartoffice.model.entity.MeetingRoom;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RoomCacheTest {
    @Test
    void positiveCacheAvoidsSecondDatabaseLookup() {
        FakeStore store = new FakeStore();
        RoomCache cache = new RoomCache(store);
        AtomicInteger loads = new AtomicInteger();
        MeetingRoom room = room("current");
        assertSame(room, cache.get(1, () -> { loads.incrementAndGet(); return room; }));
        assertSame(room, cache.get(1, () -> { loads.incrementAndGet(); return room; }));
        assertEquals(1, loads.get());
        assertEquals(1, store.closedLeases);
    }

    @Test
    void missingRoomIsCachedAndExpiresAfterShortTtl() {
        FakeStore store = new FakeStore();
        RoomCache cache = new RoomCache(store);
        AtomicInteger loads = new AtomicInteger();
        assertNull(cache.get(1, () -> { loads.incrementAndGet(); return null; }));
        assertNull(cache.get(1, () -> { loads.incrementAndGet(); return null; }));
        assertEquals(1, loads.get());
        store.advanceSeconds(31);
        assertEquals("created", cache.get(1, () -> { loads.incrementAndGet(); return room("created"); }).getRoomName());
        assertEquals(2, loads.get());
    }

    @Test
    void invalidationDuringDatabaseLoadRejectsOldRefill() {
        FakeStore store = new FakeStore();
        RoomCache cache = new RoomCache(store);
        MeetingRoom result = cache.get(1, () -> { cache.invalidate(1); return room("old"); });
        assertEquals("old", result.getRoomName()); // In-flight read may complete; it must not pollute future reads.
        assertEquals(1, store.rejectedRefills);
        assertFalse(store.read(1).present());
        assertEquals("new", cache.get(1, () -> room("new")).getRoomName());
        assertEquals("new", store.read(1).room().getRoomName());
    }

    @Test
    void busyLoadingLockQueriesDatabaseWithoutAnyRefill() {
        FakeStore store = new FakeStore();
        store.busy = true;
        RoomCache cache = new RoomCache(store);
        assertEquals("database", cache.get(1, () -> room("database")).getRoomName());
        assertEquals(0, store.puts);
        assertFalse(store.read(1).present());
    }

    @Test
    void readAndLockRedisFailuresFallBackToDatabase() {
        for (String stage : new String[] { "read", "lock", "version", "put" }) {
            FakeStore store = new FakeStore();
            store.failStage = stage;
            AtomicInteger loads = new AtomicInteger();
            MeetingRoom result = new RoomCache(store).get(1, () -> { loads.incrementAndGet(); return room("database"); });
            assertEquals("database", result.getRoomName(), stage);
            assertEquals(1, loads.get(), stage);
        }
    }

    @Test
    void databaseFailurePropagatesOnceAndReleasesLoadingLease() {
        FakeStore store = new FakeStore();
        AtomicInteger loads = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> new RoomCache(store).get(1, () -> { loads.incrementAndGet(); throw new IllegalStateException("database down"); }));
        assertEquals(1, loads.get());
        assertEquals(1, store.closedLeases);
    }

    @Test
    void invalidationFailureDoesNotFailCommittedOperation() {
        FakeStore store = new FakeStore();
        store.failStage = "invalidate";
        assertDoesNotThrow(() -> new RoomCache(store).invalidate(1));
    }

    @Test
    void expiredVersionNeverLetsOldLoaderRestoreCache() {
        FakeStore store = new FakeStore();
        RoomCache cache = new RoomCache(store);
        cache.get(1, () -> { store.advanceSeconds(601); return room("old"); });
        assertFalse(store.read(1).present());
        assertEquals(1, store.rejectedRefills);
    }

    private static MeetingRoom room(String name) {
        MeetingRoom room = new MeetingRoom();
        room.setId(1L);
        room.setRoomName(name);
        room.setIsDeleted(0);
        return room;
    }

    private static final class FakeStore implements RoomCacheStore {
        private final Map<Long, CachedRoom> values = new HashMap<>();
        private final Map<Long, Long> valueExpiry = new HashMap<>();
        private final Map<Long, String> versions = new HashMap<>();
        private final Map<Long, Long> versionExpiry = new HashMap<>();
        private long now;
        private int sequence;
        private boolean busy;
        private String failStage;
        private int puts;
        private int closedLeases;
        private int rejectedRefills;

        void advanceSeconds(long seconds) { now += seconds; }
        void fail(String stage) { if (stage.equals(failStage)) throw new IllegalStateException("redis " + stage); }
        @Override public CachedRoom read(long id) {
            fail("read");
            if (valueExpiry.getOrDefault(id, 0L) <= now) values.remove(id);
            return values.getOrDefault(id, CachedRoom.miss());
        }
        @Override public String version(long id) {
            fail("version");
            if (versionExpiry.getOrDefault(id, 0L) <= now) versions.remove(id);
            if (!versions.containsKey(id)) {
                versions.put(id, "version-" + ++sequence);
                versionExpiry.put(id, now + 600);
            }
            return versions.get(id);
        }
        @Override public Lease tryAcquire(long id) { fail("lock"); return busy ? null : () -> closedLeases++; }
        @Override public boolean putIfVersion(long id, String version, MeetingRoom room) {
            fail("put");
            puts++;
            if (versionExpiry.getOrDefault(id, 0L) <= now || !version.equals(versions.get(id))) { rejectedRefills++; return false; }
            values.put(id, new CachedRoom(true, room));
            valueExpiry.put(id, now + (room == null ? 30 : 180));
            return true;
        }
        @Override public void invalidate(long id) {
            fail("invalidate");
            versions.put(id, "version-" + ++sequence);
            versionExpiry.put(id, now + 600);
            values.remove(id);
            valueExpiry.remove(id);
        }
    }
}
