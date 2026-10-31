package com.geek.smartoffice.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geek.smartoffice.model.entity.BookingOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 在预约事务内记录待发送事件，把数据库提交与 MQ 发送拆成两个阶段。
 * 订单提交后由 OutboxRelay 读取持久化快照发送，MQ 短时不可用时仍可保留后续重试依据。
 */
@Service
@RequiredArgsConstructor
public class BookingOutboxService {
    public static final String EVENT_TYPE = "BOOKING_CREATED_V1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * 由独立写入服务在插入订单并取得 ID 后调用，输入 order 已包含用于事件的完整预约快照。
     * MANDATORY 要求调用时已有事务，缺少事务会由 Spring 事务代理拒绝执行，
     * 有事务时与订单使用同一提交边界：二者一起提交或回滚，不在此处同步访问 Broker。
     * eventId 仅在创建这条 Outbox 记录时生成，后续重投复用已保存的 payload，
     * PENDING 和当前 available_at 使已提交事件可以被下一次定时扫描领取。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(BookingOrder order) {
        LocalDateTime now = LocalDateTime.now(clock);
        var event = new BookingCreatedEvent(1, UUID.randomUUID().toString(), order.getId(), order.getUserId(),
                order.getRoomId(),order.getTopic(),order.getStartTime(),order.getEndTime(),now);
        try {
            // 先序列化再插入，序列化和数据库写入任一失败都不能留下已提交订单却没有事件的结果。
            jdbc.update("""
                INSERT INTO booking_outbox(event_id,aggregate_id,event_type,payload,status,attempts,available_at,created_at,updated_at)
                VALUES(?,?,?,?,'PENDING',0,?,?,?)
                """,event.eventId(),event.bookingId(),EVENT_TYPE,json.writeValueAsString(event),now,now,now);
        } catch (JsonProcessingException ex) {
            // 将受检序列化异常转为运行时异常，让外层预约事务按默认规则回滚。
            throw new IllegalStateException("预约事件序列化失败，事务必须回滚",ex);
        }
    }
}
