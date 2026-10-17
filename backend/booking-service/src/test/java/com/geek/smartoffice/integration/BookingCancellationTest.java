package com.geek.smartoffice.integration;

import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.service.BookingWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = DatabaseTestConfig.class)
@ActiveProfiles("test")
@Sql({"/db-test.sql","/message-test.sql"})
class BookingCancellationTest {
    @Autowired BookingWriteService service;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM booking_outbox");
        jdbc.update("DELETE FROM booking_order");
        jdbc.update("DELETE FROM meeting_room");
        jdbc.update("INSERT INTO meeting_room(id,room_name,capacity,equipment,status) VALUES(1,'test',10,'[]',1)");
    }
    @Test void onlyOwnerOrAdminCanCancelAndRetriesSucceed() {
        Long id = service.createBookingInsideLock(BookingConcurrencyTest.booking(10,11), 1L);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.cancel(id, 2L, false)).getStatus().value());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?", Integer.class, id));
        service.cancel(id, 1L, false);
        service.cancel(id, 1L, false);
        assertEquals(2, jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?", Integer.class, id));
        service.createBookingInsideLock(BookingConcurrencyTest.booking(10,11), 2L);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM booking_order", Integer.class));
    }
    @Test void concurrentCancelAndReplacementKeepOneActiveOrder() throws Exception {
        Long id = service.createBookingInsideLock(BookingConcurrencyTest.booking(10,11), 1L);
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            var cancels = new java.util.ArrayList<Future<?>>();
            for (int i=0;i<7;i++) cancels.add(pool.submit(() -> {
                start.await(); service.cancel(id,1L,false); return null;
            }));
            Future<?> replacement = pool.submit(() -> {
                start.await();
                try { service.createBookingInsideLock(BookingConcurrencyTest.booking(10,11),2L); }
                catch (BusinessException ex) { assertEquals(409,ex.getStatus().value()); }
                return null;
            });
            start.countDown();
            for (var cancel:cancels) cancel.get(15,TimeUnit.SECONDS);
            replacement.get(15,TimeUnit.SECONDS);
            assertEquals(2,jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?",Integer.class,id));
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE status=1",Integer.class)<=1);
        } finally { pool.shutdownNow(); }
    }
}
