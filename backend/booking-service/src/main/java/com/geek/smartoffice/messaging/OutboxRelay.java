package com.geek.smartoffice.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 执行一轮持久化事件投递，把候选查询、租约竞争、发送和状态回写串联起来。
 * 调度时机由 RocketMqRuntime 决定，本类通过 EventPublisher 接收具体发送能力。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {
    private final OutboxStore store;
    private final ObjectMapper json;
    private final Clock clock;
    /**
     * 扫描到期事件，每个候选使用新的 owner 竞争租约；只有领取成功的事件才会解码并发送。
     * publisher 返回 Broker 消息 ID 后再写 SENT，解码、发送或回写抛异常都会尝试安排退避重试。
     * Broker 已收到消息而 SENT 尚未落库存在失败窗口，恢复后可能再次发送相同 eventId，
     * 因此消费端必须幂等处理，整个链路并不保证消息恰好投递一次。
     * 若扫描、领取或 retry 自身失败，异常退出本轮交给调度层记录；持久化状态供后续轮次恢复。
     */
    public void dispatchOnce(EventPublisher publisher) {
        for(String id:store.dueIds(LocalDateTime.now(clock))) {
            String owner=UUID.randomUUID().toString();
            var message=store.claim(id,owner,LocalDateTime.now(clock));
            if(message==null) continue;
            try {
                // 始终从原 payload 还原事件，重试不会重新生成 eventId 或使用变化后的订单字段。
                var event=json.readValue(message.payload(),BookingCreatedEvent.class);
                String brokerId=publisher.publish(event);
                store.sent(id,owner,brokerId,LocalDateTime.now(clock));
            } catch(Exception failure) {
                // 这里只保存异常类型供排查；发送后状态回写失败也走此路径，可能触发重复投递。
                log.warn("outbox delivery failed eventId={} attempts={} type={}",id,message.attempts()+1,failure.getClass().getSimpleName());
                store.retry(message,owner,failure.getClass().getSimpleName(),LocalDateTime.now(clock));
            }
        }
    }
}
