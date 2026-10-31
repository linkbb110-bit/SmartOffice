package com.geek.smartoffice.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geek.smartoffice.mapper.BookingOrderMapper;
import com.geek.smartoffice.model.entity.BookingOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 把预约创建事件转换为站内通知、业务审计和消费组回执，所有结果写在同一个数据库事务中。
 * 通知与审计按 eventId 跨消费组复用；回执按 eventId 和 consumerGroup 分别记录各组处理情况。
 */
@Service
@RequiredArgsConstructor
public class BookingEventConsumer {
    private final JdbcTemplate jdbc;
    private final BookingOrderMapper orderMapper;
    private final ObjectMapper json;
    private final Clock clock;
    // 与 MQ 运行时使用同一配置项，让数据库回执归属实际订阅该事件的消费组。
    @Value("${smartoffice.mq.consumer-group:smartoffice-notification-v1}")
    private String consumerGroup;

    /**
     * 监听器通过注入的 Spring Bean 代理调用本方法，事务在进入方法前开始，返回调用方前提交。
     * 先校验 V1 事件及 UUID 格式，再锁定订单核对事件快照，随后补齐或验证通知、审计与组回执。
     * READ_COMMITTED 配合订单行锁，让同一订单的并发消费者在前一事务提交后再读取已有结果。
     * 即使已存在本组回执也继续核对业务结果，可补齐缺失记录，并拒绝内容不一致的重复消息。
     * 全部写入和回执一起提交；校验、序列化或数据库异常向外传播时回滚，
     * 监听器收到异常后请求 Broker 重投，避免业务未完成却提前确认消费成功。
     */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void consume(BookingCreatedEvent event) {
        if(event.schemaVersion()!=1 || event.bookingId()==null || event.userId()==null) {
            throw new IllegalArgumentException("unsupported booking event");
        }
        UUID.fromString(event.eventId());
        // 订单行锁使同一订单的多个消费事务串行进入结果写入阶段，跨消费组也遵守这把锁。
        // 对比用户、房间、主题与起止时间，拒绝与实际预约不一致的事件，状态不在此快照比较范围内。
        BookingOrder order=orderMapper.selectForUpdate(event.bookingId());
        if(order==null || !Objects.equals(order.getUserId(),event.userId())
                || !Objects.equals(order.getRoomId(),event.roomId()) || !Objects.equals(order.getTopic(),event.topic())
                || !Objects.equals(order.getStartTime(),event.startTime()) || !Objects.equals(order.getEndTime(),event.endTime())) {
            throw new IllegalArgumentException("booking event does not match database");
        }
        LocalDateTime now=LocalDateTime.now(clock);
        // 通知和审计表各自对 eventId 唯一，同一事件被其他组再消费时复用已有业务结果。
        // 回执的唯一键则是 (eventId, consumerGroup)，所以新消费组仍会留下自己的处理记录。
        ensureNotification(event, now);
        ensureAudit(event, now);
        Integer seen=jdbc.queryForObject("SELECT COUNT(*) FROM message_consumption WHERE event_id=? AND consumer_group=?",Integer.class,event.eventId(),consumerGroup);
        if(seen==null || seen==0) {
            jdbc.update("INSERT INTO message_consumption(event_id,consumer_group,consumed_at) VALUES(?,?,?)",event.eventId(),consumerGroup,now);
        }
    }

    /**
     * 以 eventId 查询通知，首次消费写入预约 ID、用户与固定格式的内容，重复消费核对这些字段。
     * 已存内容一致时保持原记录和创建时间；内容冲突则抛异常，让整次消费回滚而非静默跳过。
     */
    private void ensureNotification(BookingCreatedEvent event, LocalDateTime now) {
        String content="预约创建成功："+event.topic();
        var matches=jdbc.query("SELECT booking_id,user_id,content FROM booking_notification WHERE event_id=?",
                (rs,row)->Objects.equals(rs.getLong("booking_id"),event.bookingId())
                        && Objects.equals(rs.getLong("user_id"),event.userId())
                        && Objects.equals(rs.getString("content"),content),event.eventId());
        if(matches.isEmpty()) {
            jdbc.update("INSERT INTO booking_notification(event_id,booking_id,user_id,content,created_at) VALUES(?,?,?,?,?)",
                    event.eventId(),event.bookingId(),event.userId(),content,now);
        } else if(!matches.get(0)) {
            throw new IllegalStateException("existing notification does not match booking event");
        }
    }

    /**
     * 审计以 eventId 去重，同时校验预约 ID、事件类型及完整载荷，保证重投对应同一业务事实。
     * 缺失时序列化当前事件并插入；已存在且一致时直接返回，序列化失败转成运行时异常触发回滚。
     */
    private void ensureAudit(BookingCreatedEvent event, LocalDateTime now) {
        var matches=jdbc.query("SELECT booking_id,event_type,payload FROM business_event_audit WHERE event_id=?",
                (rs,row)->Objects.equals(rs.getLong("booking_id"),event.bookingId())
                        && BookingOutboxService.EVENT_TYPE.equals(rs.getString("event_type"))
                        && sameEvent(rs.getString("payload"),event),event.eventId());
        if(!matches.isEmpty()) {
            if(!matches.get(0)) throw new IllegalStateException("existing audit does not match booking event");
            return;
        }
        try {
            jdbc.update("INSERT INTO business_event_audit(event_id,booking_id,event_type,payload,created_at) VALUES(?,?,?,?,?)",
                    event.eventId(),event.bookingId(),BookingOutboxService.EVENT_TYPE,json.writeValueAsString(event),now);
        } catch(JsonProcessingException ex) { throw new IllegalStateException("audit serialization failed",ex); }
    }

    // 先还原为 record 再用 equals 逐字段比较，JSON 字段顺序差异不会被误判为事件变化。
    // 历史载荷若无法解码则视为数据异常并回滚，不把损坏审计当作可忽略的重复结果。
    private boolean sameEvent(String payload, BookingCreatedEvent event) {
        try { return event.equals(json.readValue(payload,BookingCreatedEvent.class)); }
        catch(JsonProcessingException ex) { throw new IllegalStateException("existing audit payload is invalid",ex); }
    }
}
