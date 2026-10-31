package com.geek.smartoffice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geek.smartoffice.messaging.BookingCreatedEvent;
import com.geek.smartoffice.messaging.BookingEventConsumer;
import com.geek.smartoffice.service.BookingWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.geek.smartoffice.mapper.BookingOrderMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 在隔离的 H2 中验证真实消费逻辑与事务；覆盖跨消费组幂等，不代表真实 Broker 已验收。 */
@SpringBootTest(classes=DatabaseTestConfig.class)
@ActiveProfiles("test")
@Sql({"/db-test.sql", "/message-test.sql"})
class ConsumerGroupIdempotencyTest {
    @Autowired BookingWriteService booking;
    @Autowired BookingEventConsumer consumer;
    @Autowired BookingOrderMapper orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void reset() {
        for (String table : new String[]{"message_consumption", "booking_notification", "business_event_audit",
                "booking_outbox", "booking_order", "meeting_room"}) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO meeting_room(id,room_name,capacity,equipment,status) VALUES(1,'test',10,'[]',1)");
    }

    BookingCreatedEvent event() throws Exception {
        booking.createBookingInsideLock(BookingConcurrencyTest.booking(10, 11), 1L);
        return json.readValue(jdbc.queryForObject("SELECT payload FROM booking_outbox", String.class), BookingCreatedEvent.class);
    }

    /**
     * 每个消费组使用独立实例，避免并发改写单例组件的消费组属性。
     * 手动 new 不会触发 @Value 注入或 Spring 事务代理，因此用反射补入消费组配置。
     * 再由 TransactionTemplate 包装真实消费：回调正常结束后提交，运行时异常向外传播时回滚。
     */
    void consumeAs(String group, BookingCreatedEvent event) {
        var handler = new BookingEventConsumer(jdbc, orders, json, clock);
        ReflectionTestUtils.setField(handler, "consumerGroup", group);
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> handler.consume(event));
    }

    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    void oneBusinessEffect(int receipts) {
        assertEquals(1, count("booking_notification"));
        assertEquals(1, count("business_event_audit"));
        assertEquals(receipts, count("message_consumption"));
    }

    @Test void newAndOldGroupsCanReplayWithoutDuplicatingExistingBusinessRows() throws Exception {
        var event = event();
        consumer.consume(event); // 先通过 Spring 事务代理执行一次真实消费。
        var before = jdbc.queryForMap("SELECT id,content,created_at FROM booking_notification");
        consumeAs("new-group", event);
        consumeAs("new-group", event);
        consumer.consume(event);
        oneBusinessEffect(2);
        assertEquals(before, jdbc.queryForMap("SELECT id,content,created_at FROM booking_notification"));
    }

    @Test void concurrentGroupsProduceOneBusinessEffectAndOneReceiptPerGroup() throws Exception {
        var event = event();
        var pool = Executors.newFixedThreadPool(8);
        var gate = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int i=0; i<8; i++) {
                final String group = i % 2 == 0 ? "group-a" : "group-b";
                futures.add(pool.submit(() -> {
                    if (!gate.await(10, TimeUnit.SECONDS)) throw new AssertionError("start gate timed out");
                    consumeAs(group, event);
                    return null;
                }));
            }
            gate.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        oneBusinessEffect(2);
    }

    @Test void existingConsistentNotificationOrAuditCanBeCompletedWithoutDuplicateRows() throws Exception {
        var event = event();
        consumeAs("old-group", event);
        jdbc.update("DELETE FROM business_event_audit"); // 仅在隔离测试库中模拟历史数据缺失。
        consumeAs("new-group", event);
        oneBusinessEffect(2);
        jdbc.update("DELETE FROM booking_notification");
        consumeAs("third-group", event);
        oneBusinessEffect(3);
    }

    @Test void inconsistentExistingNotificationIsRejectedWithoutNewReceiptOrAudit() throws Exception {
        var event = event();
        consumeAs("old-group", event);
        jdbc.update("UPDATE booking_notification SET user_id=999");
        jdbc.update("DELETE FROM business_event_audit");
        assertThrows(IllegalStateException.class, () -> consumeAs("new-group", event));
        assertEquals(0, count("business_event_audit"));
        assertEquals(1, count("message_consumption"));
        assertEquals(999L, jdbc.queryForObject("SELECT user_id FROM booking_notification", Long.class));
    }

    @Test void inconsistentAuditRollsBackAnyRepairedNotification() throws Exception {
        var event = event();
        consumeAs("old-group", event);
        jdbc.update("DELETE FROM booking_notification");
        jdbc.update("UPDATE business_event_audit SET event_type='UNEXPECTED'");
        assertThrows(IllegalStateException.class, () -> consumeAs("new-group", event));
        assertEquals(0, count("booking_notification"));
        assertEquals(1, count("message_consumption"));
    }

    @Test void changedPayloadWithSameEventIdCannotCreateAnotherGroupReceipt() throws Exception {
        var event = event();
        consumeAs("old-group", event);
        var changed = new BookingCreatedEvent(event.schemaVersion(), event.eventId(), event.bookingId(), event.userId(),
                event.roomId(), event.topic(), event.startTime(), event.endTime(), event.occurredAt().plusSeconds(1));
        assertThrows(IllegalStateException.class, () -> consumeAs("new-group", changed));
        oneBusinessEffect(1);
    }

    @Test void receiptFailureRollsBackRepairsAndRetriedGroupCanFinish() throws Exception {
        var event = event();
        consumeAs("old-group", event);
        jdbc.update("DELETE FROM business_event_audit");
        // 用测试约束制造回执写入失败，验证本次补写的业务记录会随事务一起回滚。
        jdbc.execute("ALTER TABLE message_consumption ADD CONSTRAINT reject_new_group CHECK(consumer_group <> 'new-group')");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> consumeAs("new-group", event));
            assertEquals(0, count("business_event_audit"));
            assertEquals(1, count("message_consumption"));
        } finally { jdbc.execute("ALTER TABLE message_consumption DROP CONSTRAINT reject_new_group"); }
        consumeAs("new-group", event);
        oneBusinessEffect(2);
    }
}
