package com.geek.smartoffice.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class OutboxStore {
    private final JdbcTemplate jdbc;
    public record Claimed(String eventId,String payload,int attempts) {}
    public List<String> dueIds(LocalDateTime now) {
        return jdbc.queryForList("""
            SELECT event_id FROM booking_outbox
            WHERE (status='PENDING' AND available_at <= ?) OR (status='SENDING' AND lease_until <= ?)
            ORDER BY created_at LIMIT 20
            """,String.class,now,now);
    }
    public Claimed claim(String id,String owner,LocalDateTime now) {
        int changed=jdbc.update("""
            UPDATE booking_outbox SET status='SENDING',lease_owner=?,lease_until=?,updated_at=?
            WHERE event_id=? AND ((status='PENDING' AND available_at <= ?) OR (status='SENDING' AND lease_until <= ?))
            """,owner,now.plusSeconds(60),now,id,now,now);
        if(changed==0) return null;
        return jdbc.queryForObject("SELECT event_id,payload,attempts FROM booking_outbox WHERE event_id=? AND lease_owner=?",
                (rs,row)->new Claimed(rs.getString(1),rs.getString(2),rs.getInt(3)),id,owner);
    }
    public void sent(String id,String owner,String brokerId,LocalDateTime now) {
        jdbc.update("""
            UPDATE booking_outbox SET status='SENT',broker_message_id=?,lease_until=NULL,lease_owner=NULL,last_error=NULL,updated_at=?
            WHERE event_id=? AND status='SENDING' AND lease_owner=?
            """,brokerId,now,id,owner);
    }
    public void retry(Claimed event,String owner,String error,LocalDateTime now) {
        int attempts=Math.min(event.attempts()+1,1_000_000);
        long delay=Math.min(300,1L << Math.min(attempts,9));
        jdbc.update("""
            UPDATE booking_outbox SET status='PENDING',attempts=?,available_at=?,lease_until=NULL,lease_owner=NULL,last_error=?,updated_at=?
            WHERE event_id=? AND status='SENDING' AND lease_owner=?
            """,attempts,now.plusSeconds(delay),error,now,event.eventId(),owner);
    }
}
