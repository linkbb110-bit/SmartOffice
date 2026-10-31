package com.geek.smartoffice.messaging;

import java.time.LocalDateTime;

/**
 * 预约创建时的不可变事件快照：订单与用户、房间、主题和时段供消费端核对数据库及生成通知。
 * schemaVersion 标识载荷版本，eventId 是写入 Outbox 时生成的业务事件 ID，重投时保持不变，
 * 与每次发送返回的 Broker 消息 ID 分开使用；消费幂等依据 eventId 而不是 Broker 消息 ID。
 * occurredAt 表示事件记录时间，record 自动生成的 equals 也用于比较已存审计载荷是否一致。
 */
public record BookingCreatedEvent(int schemaVersion, String eventId, Long bookingId, Long userId,
                                  Long roomId, String topic, LocalDateTime startTime,
                                  LocalDateTime endTime, LocalDateTime occurredAt) {
}
