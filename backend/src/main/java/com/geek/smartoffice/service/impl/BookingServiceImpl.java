package com.geek.smartoffice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.mapper.BookingOrderMapper;
import com.geek.smartoffice.model.dto.BookingDTO;
import com.geek.smartoffice.model.entity.BookingOrder;
import com.geek.smartoffice.model.vo.BookingVO;
import com.geek.smartoffice.service.BookingService;
import com.geek.smartoffice.service.BookingWriteService;
import com.geek.smartoffice.service.RoomBloomFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private static final String LOCK_PREFIX = "smartoffice:booking:room:";

    private final RoomBloomFilterService roomBloomFilterService;
    private final RedissonClient redissonClient;
    private final BookingWriteService bookingWriteService;
    private final BookingOrderMapper bookingOrderMapper;
    private final Clock clock;

    @Override
    public Long book(BookingDTO dto, Long currentUserId) {
        if (dto.getRequestId() == null) validateBookingTime(dto);
        // Bloom can lag behind commits or Redis recovery; never reject based on its negative alone.
        RLock lock;
        boolean locked;
        try {
            lock = redissonClient.getLock(LOCK_PREFIX + dto.getRoomId());
            locked = lock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "预约繁忙，请稍后重试");
        } catch (RuntimeException redisFailure) {
            log.warn("booking lock unavailable; using database row lock, roomId={}", dto.getRoomId());
            return bookingWriteService.createBookingInsideLock(dto, currentUserId);
        }

        if (!locked) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "预约繁忙，请稍后重试");
        }

        try {
            // The nested transactional service returns only after its transaction commits.
            // This ensures database commit happens before the distributed lock is released.
            return bookingWriteService.createBookingInsideLock(dto, currentUserId);
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            } catch (RuntimeException redisFailure) {
                log.warn("booking lock release failed; database transaction already completed, roomId={}",dto.getRoomId());
            }
        }
    }

    @Override
    public void cancel(Long orderId, Long currentUserId, boolean admin) {
        bookingWriteService.cancel(orderId, currentUserId, admin);
    }

    @Override
    public List<BookingVO> listMyBookings(Long userId) {
        return bookingOrderMapper.selectList(new LambdaQueryWrapper<BookingOrder>()
                        .eq(BookingOrder::getUserId, userId)
                        .orderByDesc(BookingOrder::getStartTime))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public List<BookingVO> listAllBookings() {
        return bookingOrderMapper.selectList(new LambdaQueryWrapper<BookingOrder>()
                        .orderByDesc(BookingOrder::getStartTime))
                .stream()
                .map(this::toVO)
                .toList();
    }

    private void validateBookingTime(BookingDTO dto) {
        if (!dto.getStartTime().isBefore(dto.getEndTime())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "结束时间必须晚于开始时间");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (dto.getStartTime().isBefore(now)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不能预约已经过去的时间");
        }
    }

    private BookingVO toVO(BookingOrder order) {
        return BookingVO.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .roomId(order.getRoomId())
                .topic(order.getTopic())
                .startTime(order.getStartTime())
                .endTime(order.getEndTime())
                .status(order.getStatus())
                .build();
    }
}
