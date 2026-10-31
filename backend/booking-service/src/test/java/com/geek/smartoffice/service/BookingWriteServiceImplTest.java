package com.geek.smartoffice.service;

import com.geek.smartoffice.common.enums.BookingStatus;
import com.geek.smartoffice.common.enums.RoomStatus;
import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.mapper.BookingOrderMapper;
import com.geek.smartoffice.mapper.MeetingRoomMapper;
import com.geek.smartoffice.model.dto.BookingDTO;
import com.geek.smartoffice.model.entity.BookingOrder;
import com.geek.smartoffice.model.entity.MeetingRoom;
import com.geek.smartoffice.service.impl.BookingWriteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookingWriteServiceImplTest {

    private MeetingRoomMapper roomMapper;
    private BookingOrderMapper orderMapper;
    private BookingWriteServiceImpl service;

    @BeforeEach
    void setUp() {
        roomMapper = mock(MeetingRoomMapper.class);
        orderMapper = mock(BookingOrderMapper.class);
        service = new BookingWriteServiceImpl(roomMapper, orderMapper, java.time.Clock.fixed(
                java.time.Instant.parse("2026-08-22T08:00:00Z"),java.time.ZoneOffset.UTC),
                mock(com.geek.smartoffice.messaging.BookingOutboxService.class));
    }

    @Test
    void missingRoomReturns404() {
        when(roomMapper.selectForUpdate(1L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBookingInsideLock(booking(), 2L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(orderMapper, never()).insert(any(BookingOrder.class));
    }

    @Test
    void maintenanceRoomIsRejected() {
        MeetingRoom room = room(RoomStatus.MAINTENANCE.getCode());
        when(roomMapper.selectForUpdate(1L)).thenReturn(room);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBookingInsideLock(booking(), 2L));

        assertEquals(HttpStatus.LOCKED, ex.getStatus());
        verify(orderMapper, never()).insert(any(BookingOrder.class));
    }

    @Test
    void overlappingBookingReturns409() {
        when(roomMapper.selectForUpdate(1L)).thenReturn(room(RoomStatus.AVAILABLE.getCode()));
        when(orderMapper.countConflicts(eq(1L), eq(BookingStatus.BOOKED.getCode()), any(), any()))
                .thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBookingInsideLock(booking(), 2L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(orderMapper, never()).insert(any(BookingOrder.class));
    }

    @Test
    void nonOverlappingBookingIsInserted() {
        when(roomMapper.selectForUpdate(1L)).thenReturn(room(RoomStatus.AVAILABLE.getCode()));
        when(orderMapper.countConflicts(eq(1L), eq(BookingStatus.BOOKED.getCode()), any(), any()))
                .thenReturn(0L);

        service.createBookingInsideLock(booking(), 2L);

        // <BookingOrder> 显式指定泛型，让 argThat 的 Lambda 参数按订单实体解析。
        // 这里检查传给 Mapper 的对象内容；Mapper 是 Mock，因此不会执行真实 INSERT。
        verify(orderMapper).insert(org.mockito.ArgumentMatchers.<BookingOrder>argThat(order ->
                order.getRoomId().equals(1L)
                        && order.getUserId().equals(2L)
                        && order.getStatus().equals(BookingStatus.BOOKED.getCode())));
    }

    private MeetingRoom room(Integer status) {
        MeetingRoom room = new MeetingRoom();
        room.setId(1L);
        room.setRoomName("星云会议室");
        room.setStatus(status);
        return room;
    }

    private BookingDTO booking() {
        BookingDTO dto = new BookingDTO();
        dto.setRoomId(1L);
        dto.setTopic("架构评审");
        dto.setStartTime(LocalDateTime.of(2026, 8, 23, 10, 0));
        dto.setEndTime(LocalDateTime.of(2026, 8, 23, 11, 0));
        return dto;
    }
}
