package com.geek.smartoffice.service;

import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.model.dto.BookingDTO;
import com.geek.smartoffice.service.impl.BookingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class BookingServiceImplTest {

    private RoomBloomFilterService bloom;
    private RedissonClient redissonClient;
    private RLock lock;
    private BookingWriteService bookingWriteService;
    private BookingServiceImpl service;

    @BeforeEach
    void setUp() {
        bloom = mock(RoomBloomFilterService.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        bookingWriteService = mock(BookingWriteService.class);
        // 固定在样例预约之前的时间，服务校验读取的也是这个注入时钟。
        // 避免真实日期推进后，同一个测试意外变成“预约时间已过去”的失败场景。
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T08:00:00Z"), ZoneOffset.UTC);
        service = new BookingServiceImpl(bloom, redissonClient, bookingWriteService, null, clock);
    }

    @Test
    void bloomLagCannotRejectARealRoom() throws Exception {
        BookingDTO dto = booking(99L);
        when(bloom.mightContain(99L)).thenReturn(false);

        when(redissonClient.getLock("smartoffice:booking:room:99")).thenReturn(lock);
        when(lock.tryLock(2,TimeUnit.SECONDS)).thenReturn(true);
        when(bookingWriteService.createBookingInsideLock(dto,1L)).thenReturn(42L);
        assertEquals(42L,service.book(dto,1L));
    }

    @Test
    void lockAcquisitionFailureDoesNotWrite() throws Exception {
        BookingDTO dto = booking(1L);
        when(bloom.mightContain(1L)).thenReturn(true);
        when(redissonClient.getLock("smartoffice:booking:room:1")).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.book(dto, 1L));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        verifyNoInteractions(bookingWriteService);
        verify(lock, never()).unlock();
    }

    @Test
    void successfulWriteCommitsBeforeUnlock() throws Exception {
        BookingDTO dto = booking(1L);
        when(bloom.mightContain(1L)).thenReturn(true);
        when(redissonClient.getLock("smartoffice:booking:room:1")).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        service.book(dto, 8L);

        // 用调用顺序验证先完成写入再解锁；此处使用 Mock，不验证真实事务提交。
        var order = inOrder(bookingWriteService, lock);
        order.verify(bookingWriteService).createBookingInsideLock(dto, 8L);
        order.verify(lock).unlock();
    }

    @Test
    void writeFailureStillUnlocksAndPropagates() throws Exception {
        BookingDTO dto = booking(1L);
        when(bloom.mightContain(1L)).thenReturn(true);
        when(redissonClient.getLock("smartoffice:booking:room:1")).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(bookingWriteService.createBookingInsideLock(dto, 8L))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT, "预约时间冲突"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.book(dto, 8L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(lock).unlock();
    }

    private BookingDTO booking(Long roomId) {
        BookingDTO dto = new BookingDTO();
        dto.setRoomId(roomId);
        dto.setTopic("架构评审");
        dto.setStartTime(LocalDateTime.of(2026, 8, 23, 10, 0));
        dto.setEndTime(LocalDateTime.of(2026, 8, 23, 11, 0));
        return dto;
    }
}
