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

/**
 * 仅在 smartoffice.mq.enabled=true 时创建，将 Outbox 定时投递与 RocketMQ 消费监听连接起来。
 * 生产者延迟到实际发送时创建，消费者由单独调度尝试启动；两侧短时故障分别等待后续重试。
 * 数据库事务由注入的消费处理器代理管理，MQ 的确认或重投状态由这里的监听回调返回。
 */
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
    /**
     * 启动 3 秒后扫描一次 Outbox，之后每轮结束再等待配置间隔，缺省为 2 秒。
     * 将本对象作为 EventPublisher 交给 relay，只有实际领取到事件才会触发 publish。
     * 一轮扫描失败只记录告警，不删除事件，下一轮仍按数据库状态和租约条件恢复投递。
     */
    @Scheduled(fixedDelayString="${smartoffice.mq.poll-delay-ms:2000}",initialDelay=3000)
    public void relay() {
        try { relay.dispatchOnce(this); }
        catch(Exception ex) { log.warn("outbox scan failed type={}",ex.getClass().getSimpleName()); }
    }
    /**
     * 启动 1 秒后尝试创建消费者，以后每轮结束等待 10 秒；已创建或正在关闭时直接返回。
     * 先配置订阅与监听器再 start，成功才保存 consumer；启动失败关闭候选实例，留待下轮重建。
     * synchronized 与 publish、close 共用对象锁，避免客户端创建和销毁交错。
     */
    @Scheduled(fixedDelay=10000,initialDelay=1000)
    public synchronized void ensureConsumer() {
        if(closed || consumer!=null) return;
        DefaultMQPushConsumer candidate=newConsumer(consumerGroup,namesrv);
        try {
            candidate.subscribe(topic,"*");
            candidate.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
                for(var message:messages) {
                    try {
                        // 回调在 MQ 消费线程运行，JSON 解码后调用另一个 Bean 的事务代理。
                        // consume 返回时其事务已提交，只有循环全部成功才向 Broker 确认本批消费完成。
                        handler.consume(json.readValue(message.getBody(),BookingCreatedEvent.class));
                    } catch(Exception ex) {
                        log.warn("booking event consumption failed eventKey={}, messageId={}, reconsumeTimes={}, type={}; broker retry requested",
                                message.getKeys(), message.getMsgId(), message.getReconsumeTimes(), ex.getClass().getSimpleName());
                        // 解码失败或消费事务异常均请求 Broker 稍后重投，重试次数受客户端配置约束。
                        // 提交后确认丢失也可能带来重复消息，处理器依赖数据库幂等承接。
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
    /**
     * 构造尚未启动的消费者，设置消费线程范围、单次回调批量和最大重新消费次数。
     * 单条批量使一次失败对应当前消息；16 次重试上限意味着无法无限依赖 Broker 自动重投。
     */
    static DefaultMQPushConsumer newConsumer(String group,String address) {
        var candidate=new DefaultMQPushConsumer(group);
        // 没有既有消费位点的新组从最早可用位点开始；已有组仍按已保存的位点继续。
        // 能补读的范围取决于 Broker 尚保留的消息，不保证找回已经过期清理的历史事件。
        candidate.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        candidate.setNamesrvAddr(address);
        candidate.setConsumeThreadMin(2);
        candidate.setConsumeThreadMax(4);
        candidate.setConsumeMessageBatchMaxSize(1);
        candidate.setMaxReconsumeTimes(16);
        return candidate;
    }
    /**
     * 首次发送时创建并启动生产者，以同步 send 将 UTF-8 JSON 发到配置 topic，消息 key 使用 eventId。
     * 客户端发送重试关闭，由持久化 Outbox 决定失败后的退避和重发，单次发送超时为 3 秒。
     * 仅 SEND_OK 才返回 Broker 消息 ID，其余结果抛异常；即便抛异常也可能已发送，需容忍重投。
     * 成功返回只完成生产端确认，relay 随后写 SENT，消费端事务完成由监听器另行确认。
     */
    @Override
    public synchronized String publish(BookingCreatedEvent event) throws Exception {
        if(closed) throw new IllegalStateException("RocketMQ runtime stopped");
        if(producer==null) {
            DefaultMQProducer candidate=new DefaultMQProducer(producerGroup);
            candidate.setNamesrvAddr(namesrv);
            candidate.setSendMsgTimeout(3000);
            candidate.setRetryTimesWhenSendFailed(0); // 单次调用失败后交给 Outbox 持久化安排下一次发送。
            try { candidate.start(); producer=candidate; }
            catch(Exception ex) { candidate.shutdown(); throw ex; }
        }
        byte[] payload=json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        var result=producer.send(new Message(topic,"BOOKING_CREATED",event.eventId(),payload),3000);
        if(result.getSendStatus()!=SendStatus.SEND_OK) throw new IllegalStateException("broker did not acknowledge durable send");
        return result.getMsgId();
    }
    // 容器销毁前先标记关闭，阻止后续调度创建客户端，再释放已存在的生产者与消费者资源。
    @PreDestroy
    public synchronized void close() {
        closed=true;
        if(consumer!=null) consumer.shutdown();
        if(producer!=null) producer.shutdown();
    }
}
