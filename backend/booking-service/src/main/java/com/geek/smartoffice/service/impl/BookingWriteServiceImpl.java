package com.geek.smartoffice.service.impl;

import com.geek.smartoffice.common.enums.BookingStatus;
import com.geek.smartoffice.common.enums.RoomStatus;
import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.mapper.BookingOrderMapper;
import com.geek.smartoffice.mapper.MeetingRoomMapper;
import com.geek.smartoffice.messaging.BookingOutboxService;
import com.geek.smartoffice.model.dto.BookingDTO;
import com.geek.smartoffice.model.entity.BookingOrder;
import com.geek.smartoffice.model.entity.MeetingRoom;
import com.geek.smartoffice.service.BookingWriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class BookingWriteServiceImpl implements BookingWriteService {

    private final MeetingRoomMapper meetingRoomMapper;
    private final BookingOrderMapper bookingOrderMapper;
    private final Clock clock;
    private final BookingOutboxService outbox;

    /**
     * 在一个 READ_COMMITTED 事务中完成幂等重放、房间加锁、时间冲突检查、订单与 Outbox 写入。
     * 房间行锁是预约、取消和房间管理共用的协调点；即使 Redis 锁不可用也必须经过这里。
     * 无外层事务时，Spring 代理会在本方法正常返回后提交，异常则按事务规则回滚，随后才返回入口层。
     * 默认 REQUIRED 会加入已有事务；此时最终提交与行锁释放由外层事务决定，不由方法返回决定。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long createBookingInsideLock(BookingDTO dto, Long currentUserId) {
        // 第一次查询是快速重放路径，requestId 的作用域是“同一用户”，不能借此读取其他人的订单。
        // 找到相同请求就返回原 ID，不再校验当前时间、房间状态，已取消的原订单也不会因此重新创建。
        BookingOrder prior = findPrior(dto, currentUserId);
        if (prior != null)
            return matchingReplay(prior, dto);
        // SELECT ... FOR UPDATE 锁住房间行，锁一直持有到当前数据库事务提交或回滚。
        // 所有遵守同一加锁顺序的预约、取消和房间修改因此串行，避免两个事务同时“查无冲突”后都插入。
        MeetingRoom room = meetingRoomMapper.selectForUpdate(dto.getRoomId());
        if (room == null || Integer.valueOf(1).equals(room.getIsDeleted())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "会议室不存在");
        }
        // 第一次查无记录后，等待房间锁期间可能已有同请求事务提交，所以拿到锁还需再次查询。
        // READ_COMMITTED 让本次语句看到已提交的新记录；Mapper 同时刷新 MyBatis 缓存，防止复用初查结果。
        prior = findPrior(dto, currentUserId);

        if (prior != null)
            return matchingReplay(prior, dto);
        // 只有确认不是幂等重放才校验新预约，且此时已经拿锁，避免把锁等待前的时间判断当成最终结果。
        // 要求时间不为空、开始不早于现在、结束晚于开始，并精确到整秒，防止数据库精度截断改变时段。
        if (dto.getStartTime() == null || dto.getEndTime() == null
                || !dto.getStartTime().isBefore(dto.getEndTime())
                || dto.getStartTime().getNano() != 0 || dto.getEndTime().getNano() != 0
                || dto.getStartTime().isBefore(LocalDateTime.now(clock))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "预约时间无效");
        }

        if (!Integer.valueOf(RoomStatus.AVAILABLE.getCode()).equals(room.getStatus())) {
            throw new BusinessException(HttpStatus.LOCKED, "会议室维护中，暂不可预约");
        }

        // 在房间行锁内只统计 BOOKED 订单，已取消订单不再占用时段。
        // Mapper 使用 [start, end) 半开区间：两场会议首尾相接不冲突，只有真正交叠才拒绝新预约。
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
        // insert 后实体取得数据库生成的订单 ID，再把该 ID 写入待投递事件。
        // Outbox 的 record 要求加入已有事务，订单和事件一起提交或回滚；序列化或写入异常也会使本次失败。
        // 本事务只保证事件已落库，真正发送由后续异步流程执行，不表示消息只会投递一次。
        outbox.record(order);

        return order.getId();
    }

    private BookingOrder findPrior(BookingDTO dto, Long userId) {
        return dto.getRequestId() == null ? null : bookingOrderMapper.findByRequest(userId, dto.getRequestId());
    }

    // 复用 requestId 必须保持房间、去除首尾空白后的主题和起止时间相同，否则以冲突拒绝。
    // 这是“重放原结果”，所以不比较当前订单状态；取消后的重试仍返回原 ID，不恢复预约。
    private Long matchingReplay(BookingOrder order, BookingDTO dto) {
        if (!Objects.equals(order.getRoomId(), dto.getRoomId())
                || !Objects.equals(order.getTopic(), dto.getTopic().trim())
                || !Objects.equals(order.getStartTime(), dto.getStartTime())
                || !Objects.equals(order.getEndTime(), dto.getEndTime())) {
            throw new BusinessException(HttpStatus.CONFLICT, "requestId 已用于其他预约，请生成新的请求ID");
        }
        return order.getId();
    }

    /**
     * 校验订单存在及取消权限后，取得对应房间行锁，再用条件更新取消仍为 BOOKED 的订单。
     * 管理员可取消其他用户的预约；普通用户只能取消本人订单，失败以业务异常结束事务。
     * 使用与创建预约、房间删除相同的房间锁，让“释放时段”与其他操作的检查保持顺序。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancel(Long orderId, Long currentUserId, boolean admin) {
        BookingOrder order = bookingOrderMapper.selectById(orderId);
        if (order == null) throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        if (!admin && !Objects.equals(currentUserId, order.getUserId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只能取消本人的预约");
        }
        // 初查用于定位房间和校验权限；真正修改前再锁房间，锁持有到事务结束。
        // 取消不依赖 Redis 预约锁，仍能与新预约和房间删除协调，因为这些路径都获取同一房间行锁。
        meetingRoomMapper.selectForUpdate(order.getRoomId());
        // SQL 的 WHERE status=1 在数据库端判断，重复取消或并发先完成取消时，影响行数为 0。
        // 业务把这当作已达到取消状态，不重复修改时间，也不将幂等取消误报为失败。
        bookingOrderMapper.cancelBooked(orderId);
    }
}
