package com.geek.smartoffice.messaging;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class RocketMqConsumerConfigurationTest {
    @Test void aNewGroupMustReadEventsPublishedBeforeConsumerStartup() {
        // Real SDK instance, no network/start. Transport replay must be verified against a broker separately.
        var consumer=RocketMqRuntime.newConsumer("new-notification-group","127.0.0.1:9876");
        assertEquals(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET,consumer.getConsumeFromWhere());
    }
}
