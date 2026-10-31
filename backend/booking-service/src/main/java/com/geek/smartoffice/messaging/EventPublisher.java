package com.geek.smartoffice.messaging;

/**
 * 为 OutboxRelay 提供一次发送的抽象，运行时实现负责与 Broker 通信。
 * 函数式接口也允许测试或其他调用方传入 Lambda，而不让投递流程依赖具体 MQ 客户端。
 */
@FunctionalInterface
public interface EventPublisher {
    // 返回本次发送成功的 Broker 消息 ID，供 Outbox 记录投递结果；eventId 仍由事件自身保留。
    // 发送异常交给 relay 安排持久化重试，成功返回也不意味着消费端已经处理完毕。
    String publish(BookingCreatedEvent event) throws Exception;
}
