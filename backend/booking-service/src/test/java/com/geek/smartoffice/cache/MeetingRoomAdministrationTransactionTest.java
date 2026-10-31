package com.geek.smartoffice.cache;

import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.integration.DatabaseTestConfig;
import com.geek.smartoffice.model.dto.RoomSaveDTO;
import com.geek.smartoffice.model.dto.BookingDTO;
import com.geek.smartoffice.service.BookingWriteService;
import com.geek.smartoffice.service.MeetingRoomService;
import com.geek.smartoffice.service.RoomBloomFilterService;
import com.geek.smartoffice.service.impl.MeetingRoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 使用真实数据库事务与缓存桩，检查提交后失效、回滚不失效，以及管理操作和预约的并发边界。
@SpringBootTest(classes = {DatabaseTestConfig.class, MeetingRoomAdministrationTransactionTest.CacheTestConfig.class})
@ActiveProfiles("test")
@Sql({"/db-test.sql", "/message-test.sql"})
class MeetingRoomAdministrationTransactionTest {
    @Autowired MeetingRoomService rooms;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoomCache cache;
    @Autowired RoomBloomFilterService bloom;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BookingWriteService bookings;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM booking_order");
        jdbc.update("DELETE FROM meeting_room");
        jdbc.update("INSERT INTO meeting_room(id,room_name,capacity,equipment,status) VALUES(1,'before',10,'[]',1)");
        clearInvocations(cache, bloom);
    }

    @Test void activeBookingPreventsLogicalDeletion() {
        jdbc.update("INSERT INTO booking_order(user_id,room_id,topic,start_time,end_time,status) VALUES(1,1,'future','2099-01-01 10:00:00','2099-01-01 11:00:00',1)");
        assertEquals(409, assertThrows(BusinessException.class, () -> rooms.deleteRoom(1L)).getStatus().value());
        assertEquals(0, jdbc.queryForObject("SELECT is_deleted FROM meeting_room WHERE id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM booking_order", Integer.class));
        verify(cache, never()).invalidate(anyLong());
    }

    @Test void deletionKeepsHistoricalRowsAndInvalidatesOnlyAfterCommit() {
        jdbc.update("INSERT INTO booking_order(user_id,room_id,topic,start_time,end_time,status) VALUES(1,1,'history','2000-01-01 10:00:00','2000-01-01 11:00:00',1)");
        // 显式开启外层事务，删除方法会加入该事务；回调内部尚未提交，缓存此时应保持不变。
        // 回调正常结束、事务提交后才检查失效调用，从而区分“方法执行完”和“事务已提交”。
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            rooms.deleteRoom(1L);
            verify(cache, never()).invalidate(anyLong());
        });
        assertEquals(1, jdbc.queryForObject("SELECT is_deleted FROM meeting_room WHERE id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM booking_order", Integer.class));
        verify(cache).invalidate(1L);
    }

    @Test void rolledBackUpdateDoesNotInvalidateCache() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            rooms.updateRoom(1L, dto());
            throw new IllegalStateException("rollback");
        }));
        assertEquals("before", jdbc.queryForObject("SELECT room_name FROM meeting_room WHERE id=1", String.class));
        verify(cache, never()).invalidate(anyLong());
    }

    @Test void maintenanceUpdatePreservesExistingBooking() {
        jdbc.update("INSERT INTO booking_order(user_id,room_id,topic,start_time,end_time,status) VALUES(1,1,'future','2099-01-01 10:00:00','2099-01-01 11:00:00',1)");
        rooms.updateRoom(1L, dto());
        assertEquals(0, jdbc.queryForObject("SELECT status FROM meeting_room WHERE id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM booking_order WHERE room_id=1", Integer.class));
        verify(cache).invalidate(1L);
    }

    @Test void equipmentAtStorageBoundaryCanBeCreatedAndUpdated() {
        var boundary = dto();
        boundary.setEquipment(List.of("x".repeat(496))); // JSON 数组括号与引号还会占 4 个字符。
        Long id = rooms.createRoom(boundary);
        assertEquals("[\"" + "x".repeat(496) + "\"]",
                jdbc.queryForObject("SELECT equipment FROM meeting_room WHERE id=?", String.class, id));
        boundary.setEquipment(List.of("\"".repeat(248))); // 每个引号转义后占 2 个字符，总长为 248 × 2 + 4 = 500。
        rooms.updateRoom(id, boundary);
        assertEquals("[\"" + "\\\"".repeat(248) + "\"]",
                jdbc.queryForObject("SELECT equipment FROM meeting_room WHERE id=?", String.class, id));
    }

    @Test void oversizedEquipmentIsBadRequestWithoutDatabaseOrCacheChanges() {
        for (List<String> equipment : List.of(List.of("x".repeat(497)), List.of("\"".repeat(249)),
                List.of("x".repeat(247), "y".repeat(247)))) { // 两个字符串加上 JSON 结构共 501 个字符。
            var invalid = dto();
            invalid.setEquipment(equipment);
            assertEquals(400, assertThrows(BusinessException.class, () -> rooms.createRoom(invalid)).getStatus().value());
            assertEquals(400, assertThrows(BusinessException.class, () -> rooms.updateRoom(1L, invalid)).getStatus().value());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM meeting_room", Integer.class));
        assertEquals("[]", jdbc.queryForObject("SELECT equipment FROM meeting_room WHERE id=1", String.class));
        verify(cache, never()).invalidate(anyLong());
        verify(bloom, never()).put(anyLong());
    }

    @Test void updateAdvancesModificationTimeAndRollbackPreservesBothTimestamps() {
        var old = LocalDateTime.of(2000, 1, 1, 0, 0);
        jdbc.update("UPDATE meeting_room SET created_at=?,updated_at=? WHERE id=1", old, old);
        rooms.updateRoom(1L, dto());
        var updated = jdbc.queryForObject("SELECT updated_at FROM meeting_room WHERE id=1", LocalDateTime.class);
        assertTrue(updated.isAfter(old));
        assertEquals(old, jdbc.queryForObject("SELECT created_at FROM meeting_room WHERE id=1", LocalDateTime.class));
        clearInvocations(cache);
        var rollback = dto();
        rollback.setRoomName("rollback-name");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            rooms.updateRoom(1L, rollback);
            status.setRollbackOnly();
        });
        assertEquals("after", jdbc.queryForObject("SELECT room_name FROM meeting_room WHERE id=1", String.class));
        assertEquals(updated, jdbc.queryForObject("SELECT updated_at FROM meeting_room WHERE id=1", LocalDateTime.class));
        assertEquals(old, jdbc.queryForObject("SELECT created_at FROM meeting_room WHERE id=1", LocalDateTime.class));
        verify(cache, never()).invalidate(anyLong());
    }

    @Test void afterCommitBloomFailureStillReturnsPersistedRoomId() {
        doThrow(new IllegalStateException("redis down")).when(bloom).put(anyLong());
        try {
            Long id = rooms.createRoom(dto());
            assertNotNull(id);
            assertEquals("after", jdbc.queryForObject("SELECT room_name FROM meeting_room WHERE id=?", String.class, id));
        } finally { org.mockito.Mockito.reset(bloom); }
    }

    @Test void concurrentDeleteAndBookingCannotLeaveActiveBookingOnDeletedRoom() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<Integer> booking = pool.submit(() -> {
                start.await();
                BookingDTO dto = new BookingDTO();
                dto.setRoomId(1L);
                dto.setTopic("concurrent");
                dto.setStartTime(LocalDateTime.of(2099, 1, 1, 10, 0));
                dto.setEndTime(LocalDateTime.of(2099, 1, 1, 11, 0));
                try { bookings.createBookingInsideLock(dto, 1L); return 200; }
                catch (BusinessException failure) { return failure.getStatus().value(); }
            });
            Future<Integer> deletion = pool.submit(() -> {
                start.await();
                try { rooms.deleteRoom(1L); return 200; }
                catch (BusinessException failure) { return failure.getStatus().value(); }
            });
            start.countDown();
            int bookingStatus = booking.get(15, TimeUnit.SECONDS);
            int deletionStatus = deletion.get(15, TimeUnit.SECONDS);
            assertTrue((bookingStatus == 200 && deletionStatus == 409) || (bookingStatus == 404 && deletionStatus == 200),
                    "booking=" + bookingStatus + ", delete=" + deletionStatus);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM booking_order b JOIN meeting_room r ON b.room_id=r.id WHERE b.status=1 AND r.is_deleted=1", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    private RoomSaveDTO dto() {
        RoomSaveDTO dto = new RoomSaveDTO();
        dto.setRoomName("after");
        dto.setCapacity(12);
        dto.setEquipment(List.of("白板"));
        dto.setStatus(0);
        return dto;
    }

    @TestConfiguration
    @Import(MeetingRoomServiceImpl.class)
    static class CacheTestConfig {
        @Bean RoomBloomFilterService bloom() { return mock(RoomBloomFilterService.class); }
        @Bean RoomCache cache() {
            RoomCache cache = mock(RoomCache.class);
            // thenAnswer 在缓存桩每次被调用时执行；取第二个参数中的 Supplier 并调用 get()。
            // 这样仍会走真实数据库回源，测试只替换缓存交互，不会把数据查询也一并跳过。
            when(cache.get(anyLong(), any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
            return cache;
        }
    }
}
