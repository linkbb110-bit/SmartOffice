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

    /**
     * 预约入口负责房间粒度的 Redis 锁，实际校验、冲突检查和写入由独立的 BookingWriteService 完成。
     * 拆成两个 Spring Bean，使调用经过事务代理，避免同类内部调用绕过 @Transactional。
     * 入口自身不声明事务：通常先取得 Redis 锁，再开启数据库事务；Redis 不可用时仍走数据库行锁。
     * 返回值是新订单 ID，或相同 requestId 对应的原订单 ID；竞争超时与业务校验失败会抛业务异常。
     */
    @Override
    public Long book(BookingDTO dto, Long currentUserId) {
//        requestId 避免重复预约
        // 无 requestId 时可先拒绝明显无效的时间，减少进入锁的请求。
        // 带 requestId 时推迟到写入服务先查历史订单，避免成功请求在原预约开始后重试却被时间校验拒绝。
        if (dto.getRequestId() == null) validateBookingTime(dto);
        // 不同房间使用不同键，同房间的正常预约请求在进入数据库写事务前先串行化。
        // Redis 主要缓解数据库锁竞争，真正的并发正确性仍由写入服务的房间行锁与事务保证。
        RLock lock;
        boolean locked;
        try {
            lock = redissonClient.getLock(LOCK_PREFIX + dto.getRoomId());
            // 这个重载只有“最多等待 2 秒”，没有固定租期；获取成功后使用 Redisson 看门狗续期。
            // 看门狗依赖客户端正常运行，不能替代数据库锁；等待超时则返回 false，不代表 Redis 故障。
            locked = lock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            // 恢复中断标记，让上层线程调度仍能感知中断；此路径拒绝请求，不降级创建订单。
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "预约繁忙，请稍后重试");
        } catch (RuntimeException redisFailure) {
            log.warn("booking lock unavailable; using database row lock, roomId={}", dto.getRoomId());
            // 获取 Redis 锁出现运行时异常才降级，写服务仍会锁数据库房间行后检查冲突并写入。
            return bookingWriteService.createBookingInsideLock(dto, currentUserId);
        }

        if (!locked) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "预约繁忙，请稍后重试");
        }

        try {
            // 独立 Bean 的事务代理在方法正常结束后提交；本调用链没有外层事务时，提交完成才回到这里。
            // @Transactional 默认 REQUIRED，若未来被外层事务包住，会加入该事务，提交便可能晚于这里解锁。
            return bookingWriteService.createBookingInsideLock(dto, currentUserId);

        } finally {
            try {
                // 业务异常同样会进入 finally；仅在当前线程仍持有锁时释放，避免过期后再次解锁。
                if (lock.isHeldByCurrentThread())
                    lock.unlock();
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
        // LambdaQueryWrapper<BookingOrder> 以实体类型约束查询，字段方法引用由 MyBatis-Plus 解析成列名。
        // stream 的 map(this::toVO) 逐条转换查询结果；它在终结操作 toList() 消费时执行。
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
