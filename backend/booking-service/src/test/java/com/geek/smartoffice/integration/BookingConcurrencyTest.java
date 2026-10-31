package com.geek.smartoffice.integration;

import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.model.dto.BookingDTO;
import com.geek.smartoffice.service.BookingWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

// 绕过 Redis 直接并发调用数据库写服务，验证数据库自身仍能防止重叠预约并支持幂等重试。
@SpringBootTest(classes = DatabaseTestConfig.class)
@ActiveProfiles("test")
@Sql({"/db-test.sql","/message-test.sql"})
class BookingConcurrencyTest {
    @Autowired BookingWriteService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM booking_outbox");
        jdbc.update("DELETE FROM booking_order");
        jdbc.update("DELETE FROM meeting_room");
        jdbc.update("INSERT INTO meeting_room(id,room_name,capacity,equipment,status) VALUES(1,'test',10,'[]',1)");
    }

    @Test
    void overlappingWritesHaveExactlyOneWinnerWithoutRedis() throws Exception {
        // CountDownLatch 是一次性的起跑闸门：任务先 await，主线程 countDown 后一起竞争预约。
        // Future.get 等待每个任务完成，并把工作线程中的异常带回测试线程，避免漏掉并发失败。
        var pool = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try {
            for (long user = 1; user <= 12; user++) {
                // Lambda 只能捕获不再变化的局部变量，因此为本轮用户 ID 保存一个 final 副本。
                final long userId = user;
                results.add(pool.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        service.createBookingInsideLock(booking(10,11), userId);
                        return true;
                    } catch (BusinessException ex) {
                        assertEquals(409, ex.getStatus().value());
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (var result : results) if (result.get(20, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners, "数据库自身必须保证重叠预约只有一个成功");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM booking_order", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void adjacentIntervalsAreAllowedAndNestedIntervalRejected() {
        service.createBookingInsideLock(booking(10,11), 1L);
        service.createBookingInsideLock(booking(11,12), 2L);
        BookingDTO nested = booking(10,11);
        nested.setStartTime(nested.getStartTime().plusMinutes(15));
        nested.setEndTime(nested.getEndTime().minusMinutes(15));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.createBookingInsideLock(nested, 3L)).getStatus().value());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM booking_order", Integer.class));
    }

    static BookingDTO booking(int start, int end) {
        BookingDTO dto = new BookingDTO();
        dto.setRoomId(1L);
        dto.setTopic("测试会议");
        dto.setStartTime(LocalDateTime.of(2099,1,1,start,0));
        dto.setEndTime(LocalDateTime.of(2099,1,1,end,0));
        return dto;
    }

    @Test
    void identicalRequestCanBeRetriedWithoutCreatingAnotherOrder() {
        BookingDTO dto = booking(10,11);
        dto.setRequestId("retry-1");
        service.createBookingInsideLock(dto, 1L);
        assertDoesNotThrow(() -> service.createBookingInsideLock(dto, 1L));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM booking_order", Integer.class));
    }

    @Test
    void reusingRequestKeyForDifferentTimeMustBeRejected() {
        BookingDTO first = booking(10,11);
        first.setRequestId("retry-2");
        service.createBookingInsideLock(first, 1L);
        BookingDTO second = booking(12,13);
        second.setRequestId("retry-2");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.createBookingInsideLock(second, 1L)).getStatus().value());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM booking_order", Integer.class));
    }
}
