package com.geek.smartoffice.messaging;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class RocketMqConsumerConfigurationTest {
    @Test void aNewGroupMustReadEventsPublishedBeforeConsumerStartup() {
        // 仅检查真实 SDK 实例的消费起点配置，不启动网络连接；历史消息回放需另做 Broker 验证。
        var consumer=RocketMqRuntime.newConsumer("new-notification-group","127.0.0.1:9876");
        assertEquals(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET,consumer.getConsumeFromWhere());
    }
}
