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

@Service
@RequiredArgsConstructor
public class BookingEventConsumer {
    private final JdbcTemplate jdbc;
    private final BookingOrderMapper orderMapper;
    private final ObjectMapper json;
    private final Clock clock;
    @Value("${smartoffice.mq.consumer-group:smartoffice-notification-v1}")
    private String consumerGroup;

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void consume(BookingCreatedEvent event) {
        if(event.schemaVersion()!=1 || event.bookingId()==null || event.userId()==null) {
            throw new IllegalArgumentException("unsupported booking event");
        }
        UUID.fromString(event.eventId());
        BookingOrder order=orderMapper.selectForUpdate(event.bookingId());
        if(order==null || !Objects.equals(order.getUserId(),event.userId())
                || !Objects.equals(order.getRoomId(),event.roomId()) || !Objects.equals(order.getTopic(),event.topic())
                || !Objects.equals(order.getStartTime(),event.startTime()) || !Objects.equals(order.getEndTime(),event.endTime())) {
            throw new IllegalArgumentException("booking event does not match database");
        }
        LocalDateTime now=LocalDateTime.now(clock);
        // The order row lock serializes consumers across groups. Business effects belong to
        // the event, whereas each group's receipt records its own delivery acknowledgement.
        ensureNotification(event, now);
        ensureAudit(event, now);
        Integer seen=jdbc.queryForObject("SELECT COUNT(*) FROM message_consumption WHERE event_id=? AND consumer_group=?",Integer.class,event.eventId(),consumerGroup);
        if(seen==null || seen==0) {
            jdbc.update("INSERT INTO message_consumption(event_id,consumer_group,consumed_at) VALUES(?,?,?)",event.eventId(),consumerGroup,now);
        }
    }

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

    private boolean sameEvent(String payload, BookingCreatedEvent event) {
        try { return event.equals(json.readValue(payload,BookingCreatedEvent.class)); }
        catch(JsonProcessingException ex) { throw new IllegalStateException("existing audit payload is invalid",ex); }
    }
}
