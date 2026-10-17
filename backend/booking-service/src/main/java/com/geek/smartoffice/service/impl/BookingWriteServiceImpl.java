package com.geek.smartoffice.service.impl;

import com.geek.smartoffice.common.enums.BookingStatus;
import com.geek.smartoffice.common.enums.RoomStatus;
import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.mapper.BookingOrderMapper;
import com.geek.smartoffice.mapper.MeetingRoomMapper;
import com.geek.smartoffice.model.dto.BookingDTO;
import com.geek.smartoffice.model.entity.BookingOrder;
import com.geek.smartoffice.model.entity.MeetingRoom;
import com.geek.smartoffice.service.BookingWriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import com.geek.smartoffice.messaging.BookingOutboxService;

@Service
@RequiredArgsConstructor
public class BookingWriteServiceImpl implements BookingWriteService {

    private final MeetingRoomMapper meetingRoomMapper;
    private final BookingOrderMapper bookingOrderMapper;
    private final Clock clock;
    private final BookingOutboxService outbox;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long createBookingInsideLock(BookingDTO dto, Long currentUserId) {
        BookingOrder prior = findPrior(dto, currentUserId);
        if (prior != null) return matchingReplay(prior, dto);
        MeetingRoom room = meetingRoomMapper.selectForUpdate(dto.getRoomId());
        if (room == null || Integer.valueOf(1).equals(room.getIsDeleted())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "会议室不存在");
        }
        // Recheck after acquiring the database lock; another transaction may have committed.
        prior = findPrior(dto, currentUserId);
        if (prior != null) return matchingReplay(prior, dto);
        if (dto.getStartTime() == null || dto.getEndTime() == null
                || !dto.getStartTime().isBefore(dto.getEndTime())
                || dto.getStartTime().getNano()!=0 || dto.getEndTime().getNano()!=0
                || dto.getStartTime().isBefore(LocalDateTime.now(clock))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "预约时间无效");
        }
        if (!Integer.valueOf(RoomStatus.AVAILABLE.getCode()).equals(room.getStatus())) {
            throw new BusinessException(HttpStatus.LOCKED, "会议室维护中，暂不可预约");
        }

        Long conflicts = bookingOrderMapper.countConflicts(
                dto.getRoomId(),
                BookingStatus.BOOKED.getCode(),
                dto.getStartTime(),
                dto.getEndTime()
        );
        if (conflicts != null && conflicts > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约时间冲突，请选择其他时间段");
        }

        BookingOrder order = new BookingOrder();
        order.setUserId(currentUserId);
        order.setRoomId(dto.getRoomId());
        order.setTopic(dto.getTopic().trim());
        order.setStartTime(dto.getStartTime());
        order.setEndTime(dto.getEndTime());
        order.setStatus(BookingStatus.BOOKED.getCode());
        order.setRequestId(dto.getRequestId());
        bookingOrderMapper.insert(order);
        outbox.record(order);
        return order.getId();
    }

    private BookingOrder findPrior(BookingDTO dto, Long userId) {
        return dto.getRequestId() == null ? null : bookingOrderMapper.findByRequest(userId, dto.getRequestId());
    }

    private Long matchingReplay(BookingOrder order, BookingDTO dto) {
        if (!Objects.equals(order.getRoomId(), dto.getRoomId())
                || !Objects.equals(order.getTopic(), dto.getTopic().trim())
                || !Objects.equals(order.getStartTime(), dto.getStartTime())
                || !Objects.equals(order.getEndTime(), dto.getEndTime())) {
            throw new BusinessException(HttpStatus.CONFLICT, "requestId 已用于其他预约，请生成新的请求ID");
        }
        return order.getId();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancel(Long orderId, Long currentUserId, boolean admin) {
        BookingOrder order = bookingOrderMapper.selectById(orderId);
        if (order == null) throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        if (!admin && !Objects.equals(currentUserId, order.getUserId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只能取消本人的预约");
        }
        meetingRoomMapper.selectForUpdate(order.getRoomId());
        // CAS makes repeated cancellation a successful no-op. No Redis stock exists to restore.
        bookingOrderMapper.cancelBooked(orderId);
    }
}
