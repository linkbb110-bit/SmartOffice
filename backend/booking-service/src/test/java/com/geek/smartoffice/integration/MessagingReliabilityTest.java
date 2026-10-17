package com.geek.smartoffice.integration;

import com.geek.smartoffice.messaging.*;
import com.geek.smartoffice.service.BookingWriteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=DatabaseTestConfig.class)
@ActiveProfiles("test")
@Sql({"/db-test.sql","/message-test.sql"})
class MessagingReliabilityTest {
    @Autowired BookingWriteService booking;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired BookingEventConsumer consumer;
    @Autowired OutboxRelay relay;
    @Autowired PlatformTransactionManager tx;
    @BeforeEach void reset() {
        for(String table:new String[]{"message_consumption","booking_notification","business_event_audit","booking_outbox","booking_order","meeting_room"}) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO meeting_room(id,room_name,capacity,equipment,status) VALUES(1,'test',10,'[]',1)");
    }
    BookingCreatedEvent event() throws Exception {
        booking.createBookingInsideLock(BookingConcurrencyTest.booking(10,11),1L);
        return json.readValue(jdbc.queryForObject("SELECT payload FROM booking_outbox",String.class),BookingCreatedEvent.class);
    }
    @Test void bookingRollbackAlsoRollsBackOutbox() {
        var template=new TransactionTemplate(tx);
        template.executeWithoutResult(status->{ booking.createBookingInsideLock(BookingConcurrencyTest.booking(10,11),1L); status.setRollbackOnly(); });
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM booking_order",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM booking_outbox",Integer.class));
    }
    @Test void duplicateMessageCreatesExactlyOneNotificationAndAudit() throws Exception {
        var event=event();
        consumer.consume(event);
        consumer.consume(event);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM booking_notification",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM business_event_audit",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM message_consumption",Integer.class));
    }
    @Test void notificationFailureRollsBackReceiptAndCanBeRetried() throws Exception {
        var event=event();
        jdbc.execute("ALTER TABLE booking_notification ADD CONSTRAINT reject_notify CHECK(user_id <> 1)");
        try {
            assertThrows(RuntimeException.class,()->consumer.consume(event));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM message_consumption",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM business_event_audit",Integer.class));
        } finally { jdbc.execute("ALTER TABLE booking_notification DROP CONSTRAINT reject_notify"); }
        consumer.consume(event);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM booking_notification",Integer.class));
    }
    @Test void publishFailureIsRetriedAndCommittedOrderSurvives() throws Exception {
        event();
        // Make this delivery attempt due explicitly. H2 TIMESTAMP rounds nanoseconds
        // to microseconds, so an immediate scan can otherwise precede available_at.
        jdbc.update("UPDATE booking_outbox SET available_at = '2000-01-01 00:00:00'");
        relay.dispatchOnce(e->{throw new IllegalStateException("broker unavailable");});
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM booking_outbox",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM booking_outbox",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM booking_order",Integer.class));
        jdbc.update("UPDATE booking_outbox SET available_at = '2000-01-01 00:00:00'");
        AtomicInteger sends=new AtomicInteger();
        relay.dispatchOnce(e->{sends.incrementAndGet();return "broker-id";});
        relay.dispatchOnce(e->{fail("SENT event must not be republished");return "unused";});
        assertEquals(1,sends.get());
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM booking_outbox",String.class));
    }
    @Test void expiredSendingLeaseIsRecovered() throws Exception {
        event();
        jdbc.update("UPDATE booking_outbox SET status='SENDING',lease_owner='old-owner',lease_until='2000-01-01 00:00:00'");
        relay.dispatchOnce(e->"recovered");
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM booking_outbox",String.class));
    }
}
