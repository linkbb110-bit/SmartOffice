package com.geek.smartoffice.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用数据库状态和带 owner 的租约协调多个投递实例，不在发送网络请求期间持有数据库行锁。
 * 状态依次为到期的 PENDING、已领取的 SENDING、发送成功的 SENT；失败则安排下一次 PENDING。
 */
@Repository
@RequiredArgsConstructor
public class OutboxStore {
    private final JdbcTemplate jdbc;
    // 保存领取时的事件快照与历史失败次数，供 relay 解码发送并计算本次失败后的退避。
    public record Claimed(String eventId,String payload,int attempts) {}
    /**
     * 每轮最多返回 20 个可投递 ID：等待时间已到的 PENDING，或租约过期的 SENDING。
     * 后者用于恢复发送进程退出或状态回写失败留下的事件；扫描只列出候选，不代表已取得发送权。
     */
    public List<String> dueIds(LocalDateTime now) {
        return jdbc.queryForList("""
            SELECT event_id FROM booking_outbox
            WHERE (status='PENDING' AND available_at <= ?) OR (status='SENDING' AND lease_until <= ?)
            ORDER BY created_at LIMIT 20
            """,String.class,now,now);
    }
    /**
     * 用单条条件 UPDATE 抢占 60 秒租约，再取回事件载荷；竞争失败返回 null，relay 跳过它。
     * 到期条件必须在更新时再次检查，因为同一候选 ID 可能被多个实例同时扫描到。
     * owner 是每次领取生成的唯一标识，后续 sent/retry 只有仍持有相同 owner 才能回写。
     * 超时会允许其他投递者接手，因此租约协调不能消除原投递仍在途时产生的重复消息。
     */
    public Claimed claim(String id,String owner,LocalDateTime now) {
        int changed=jdbc.update("""
            UPDATE booking_outbox SET status='SENDING',lease_owner=?,lease_until=?,updated_at=?
            WHERE event_id=? AND ((status='PENDING' AND available_at <= ?) OR (status='SENDING' AND lease_until <= ?))
            """,owner,now.plusSeconds(60),now,id,now,now);
        if(changed==0) return null;
        return jdbc.queryForObject("SELECT event_id,payload,attempts FROM booking_outbox WHERE event_id=? AND lease_owner=?",
                (rs,row)->new Claimed(rs.getString(1),rs.getString(2),rs.getInt(3)),id,owner);
    }
    /**
     * Broker 发送成功后记录消息 ID 并清理租约；SENT 表示生产端发送完成，不代表业务已被消费。
     * 条件中的 SENDING 与 owner 防止旧投递者覆盖新租约，本方法不另行检查受影响行数。
     */
    public void sent(String id,String owner,String brokerId,LocalDateTime now) {
        jdbc.update("""
            UPDATE booking_outbox SET status='SENT',broker_message_id=?,lease_until=NULL,lease_owner=NULL,last_error=NULL,updated_at=?
            WHERE event_id=? AND status='SENDING' AND lease_owner=?
            """,brokerId,now,id,owner);
    }
    /**
     * 失败后增加 attempts，将同一事件恢复为 PENDING 并推迟 available_at，释放本次租约。
     * 首次失败等待 2 秒，随后 4、8 秒递增，最终上限 300 秒，减少故障期间的密集发送。
     * attempts 最大保存 1,000,000；该上限只防止计数持续增长，不是停止重试或进入死信的条件。
     */
    public void retry(Claimed event,String owner,String error,LocalDateTime now) {
        int attempts=Math.min(event.attempts()+1,1_000_000);
        long delay=Math.min(300,1L << Math.min(attempts,9));
        jdbc.update("""
            UPDATE booking_outbox SET status='PENDING',attempts=?,available_at=?,lease_until=NULL,lease_owner=NULL,last_error=?,updated_at=?
            WHERE event_id=? AND status='SENDING' AND lease_owner=?
            """,attempts,now.plusSeconds(delay),error,now,event.eventId(),owner);
    }
}
