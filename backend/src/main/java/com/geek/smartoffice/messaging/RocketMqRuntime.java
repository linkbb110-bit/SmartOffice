package com.geek.smartoffice.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Component
@EnableScheduling
@Slf4j
@ConditionalOnProperty(name="smartoffice.mq.enabled",havingValue="true")
public class RocketMqRuntime implements EventPublisher {
    private final ObjectMapper json;
    private final BookingEventConsumer handler;
    private final OutboxRelay relay;
    private final String namesrv;
    private final String topic;
    private final String consumerGroup;
    private final String producerGroup;
    private DefaultMQProducer producer;
    private DefaultMQPushConsumer consumer;
    private boolean closed;
    public RocketMqRuntime(ObjectMapper json,BookingEventConsumer handler,OutboxRelay relay,
            @Value("${smartoffice.mq.namesrv-addr:localhost:9876}") String namesrv,
            @Value("${smartoffice.mq.topic:smartoffice-booking-created-v1}") String topic,
            @Value("${smartoffice.mq.consumer-group:smartoffice-notification-v1}") String consumerGroup,
            @Value("${smartoffice.mq.producer-group:smartoffice-outbox-producer}") String producerGroup) {
        this.json=json; this.handler=handler; this.relay=relay; this.namesrv=namesrv;
        this.topic=topic; this.consumerGroup=consumerGroup; this.producerGroup=producerGroup;
    }
    @Scheduled(fixedDelayString="${smartoffice.mq.poll-delay-ms:2000}",initialDelay=3000)
    public void relay() {
        try { relay.dispatchOnce(this); }
        catch(Exception ex) { log.warn("outbox scan failed type={}",ex.getClass().getSimpleName()); }
    }
    @Scheduled(fixedDelay=10000,initialDelay=1000)
    public synchronized void ensureConsumer() {
        if(closed || consumer!=null) return;
        DefaultMQPushConsumer candidate=newConsumer(consumerGroup,namesrv);
        try {
            candidate.subscribe(topic,"*");
            candidate.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
                for(var message:messages) {
                    try {
                        handler.consume(json.readValue(message.getBody(),BookingCreatedEvent.class));
                    } catch(Exception ex) {
                        log.warn("booking event consumption failed eventKey={}, messageId={}, reconsumeTimes={}, type={}; broker retry requested",
                                message.getKeys(), message.getMsgId(), message.getReconsumeTimes(), ex.getClass().getSimpleName());
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            candidate.start(); consumer=candidate;
            log.info("RocketMQ consumer started topic={} group={}",topic,consumerGroup);
        } catch(Exception ex) {
            candidate.shutdown();
            log.warn("RocketMQ consumer unavailable type={}; will retry startup",ex.getClass().getSimpleName());
        }
    }
    static DefaultMQPushConsumer newConsumer(String group,String address) {
        var candidate=new DefaultMQPushConsumer(group);
        // A fresh group must consume outbox events published before queue assignment.
        candidate.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        candidate.setNamesrvAddr(address);
        candidate.setConsumeThreadMin(2);
        candidate.setConsumeThreadMax(4);
        candidate.setConsumeMessageBatchMaxSize(1);
        candidate.setMaxReconsumeTimes(16);
        return candidate;
    }
    @Override
    public synchronized String publish(BookingCreatedEvent event) throws Exception {
        if(closed) throw new IllegalStateException("RocketMQ runtime stopped");
        if(producer==null) {
            DefaultMQProducer candidate=new DefaultMQProducer(producerGroup);
            candidate.setNamesrvAddr(namesrv);
            candidate.setSendMsgTimeout(3000);
            candidate.setRetryTimesWhenSendFailed(0); // Persisted outbox owns retry/backoff.
            try { candidate.start(); producer=candidate; }
            catch(Exception ex) { candidate.shutdown(); throw ex; }
        }
        byte[] payload=json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        var result=producer.send(new Message(topic,"BOOKING_CREATED",event.eventId(),payload),3000);
        if(result.getSendStatus()!=SendStatus.SEND_OK) throw new IllegalStateException("broker did not acknowledge durable send");
        return result.getMsgId();
    }
    @PreDestroy
    public synchronized void close() {
        closed=true;
        if(consumer!=null) consumer.shutdown();
        if(producer!=null) producer.shutdown();
    }
}
