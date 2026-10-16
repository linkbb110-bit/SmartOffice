package com.geek.smartoffice.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geek.smartoffice.model.entity.BookingOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingOutboxService {
    public static final String EVENT_TYPE = "BOOKING_CREATED_V1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(BookingOrder order) {
        LocalDateTime now = LocalDateTime.now(clock);
        var event = new BookingCreatedEvent(1, UUID.randomUUID().toString(), order.getId(), order.getUserId(),
                order.getRoomId(),order.getTopic(),order.getStartTime(),order.getEndTime(),now);
        try {
            jdbc.update("""
                INSERT INTO booking_outbox(event_id,aggregate_id,event_type,payload,status,attempts,available_at,created_at,updated_at)
                VALUES(?,?,?,?,'PENDING',0,?,?,?)
                """,event.eventId(),event.bookingId(),EVENT_TYPE,json.writeValueAsString(event),now,now,now);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("预约事件序列化失败，事务必须回滚",ex);
        }
    }
}
