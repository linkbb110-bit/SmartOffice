import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geek.smartoffice.cache.RedisRoomCacheStore;
import com.geek.smartoffice.cache.RoomCacheStore;
import com.geek.smartoffice.messaging.BookingCreatedEvent;
import com.geek.smartoffice.messaging.BookingOutboxService;
import com.geek.smartoffice.model.entity.MeetingRoom;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.topic.TopicValidator;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Local acceptance probe. Uses deployed application classes and real client SDKs; never starts Spring. */
public final class RealMiddlewareProbe {
    private static final String TOPIC = "smartoffice-booking-created-v1";
    private static final String BUSINESS_GROUP = "smartoffice-notification-v1";
    private static final long VALUE_TTL = 180, NULL_TTL = 30, VERSION_TTL = 600;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static String step = "arguments";

    public static void main(String[] args) {
        // Client failures may contain connection strings. Report only the failed check and exception type.
        ((LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).getLogger("ROOT").setLevel(Level.OFF);
        System.setProperty("rocketmq.client.logLevel", "OFF");
        System.setProperty("rocketmq.client.logUseSlf4j", "true");
        int exitCode = 0;
        try {
            if (args.length == 0) throw new IllegalArgumentException("mode required");
            switch (args[0]) {
                case "redis" -> redis(args.length > 1 ? Long.parseLong(args[1]) : newRoomId());
                case "mq-duplicate" -> mq(eventId(args), true);
                case "mq-earliest" -> mq(eventId(args), false);
                case "mq-send-only" -> mqSendOnly(eventId(args));
                default -> throw new IllegalArgumentException("unknown mode");
            }
            System.out.println("PASS middleware-probe mode=" + args[0]);
        } catch (Throwable failure) {
            System.err.println("FAIL " + step + " type=" + failure.getClass().getSimpleName());
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static String eventId(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("eventId required");
        return UUID.fromString(args[1]).toString();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing environment");
        return value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void check(String name, boolean passed, String details) {
        step = name;
        if (!passed) throw new AssertionError(name);
        System.out.println("PASS " + name + (details.isEmpty() ? "" : " " + details));
    }

    private static long newRoomId() {
        return 8_000_000_000_000_000L + ThreadLocalRandom.current().nextLong(900_000_000_000_000L);
    }

    private static String roomKey(long roomId, String suffix) {
        return "smartoffice:{room:" + roomId + "}:" + suffix;
    }

    private static void redis(long roomId) throws Exception {
        step = "redis-connect";
        if (roomId < 8_000_000_000_000_000L) throw new IllegalArgumentException("probe roomId must be high and exclusive");
        String host = env("REDIS_HOST"), password = env("REDIS_PASSWORD");
        int port = Integer.parseInt(env("REDIS_PORT", "6379"));
        int database = Integer.parseInt(env("REDIS_DATABASE", "0"));
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        standalone.setDatabase(database);
        standalone.setPassword(RedisPassword.of(password));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3)).build();
        var connection = new LettuceConnectionFactory(standalone, client);
        RedissonClient redisson = null;
        var worker = Executors.newSingleThreadExecutor();
        try {
            connection.afterPropertiesSet();
            connection.start();
            var redis = new StringRedisTemplate(connection);
            redis.afterPropertiesSet();
            Config config = new Config();
            config.setThreads(2).setNettyThreads(2);
            config.useSingleServer().setAddress("redis://" + host + ":" + port).setPassword(password)
                    .setDatabase(database).setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                    .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2)
                    .setConnectTimeout(3000).setTimeout(3000).setRetryAttempts(0);
            redisson = Redisson.create(config);
            RedisRoomCacheStore store = new RedisRoomCacheStore(redis, redisson, JSON, VALUE_TTL, NULL_TTL, VERSION_TTL);
            var ownKeys = List.of(roomKey(roomId, "version"), roomKey(roomId, "detail"), roomKey(roomId, "load-lock"));
            check("redis-exclusive-keys", Long.valueOf(0).equals(redis.countExistingKeys(ownKeys)), "roomId=" + roomId);
            check("redis-initial-miss", !store.read(roomId).present(), "");
            step = "redis-version-lua";
            String oldVersion = store.version(roomId);
            check("redis-version-lua", oldVersion.equals(store.version(roomId)), "stable=true");
            Long versionTtl = redis.getExpire(roomKey(roomId, "version"), TimeUnit.SECONDS);
            check("redis-version-ttl", versionTtl != null && versionTtl > 0 && versionTtl <= VERSION_TTL,
                    "actualSeconds=" + versionTtl);
            MeetingRoom room = new MeetingRoom();
            room.setId(roomId);
            room.setRoomName("real-middleware-probe-" + roomId);
            room.setCapacity(1);
            room.setStatus(1);
            step = "redis-refill-lua";
            check("redis-refill-lua", store.putIfVersion(roomId, oldVersion, room), "");
            var cached = store.read(roomId);
            check("redis-refill-value", cached.present() && room.equals(cached.room()), "");
            Long detailTtl = redis.getExpire(roomKey(roomId, "detail"), TimeUnit.SECONDS);
            check("redis-value-ttl", detailTtl != null && detailTtl > 0 && detailTtl <= VALUE_TTL,
                    "actualSeconds=" + detailTtl);
            step = "redis-invalidate-lua";
            store.invalidate(roomId);
            check("redis-invalidate-lua", !store.read(roomId).present(), "detailDeleted=true");
            String currentVersion = store.version(roomId);
            check("redis-invalidate-version", !oldVersion.equals(currentVersion), "rotated=true");
            check("redis-stale-token-rejected", !store.putIfVersion(roomId, oldVersion, room)
                    && !store.read(roomId).present(), "");
            check("redis-current-token-refill", store.putIfVersion(roomId, currentVersion, room), "");
            store.invalidate(roomId);
            check("redis-null-refill", store.putIfVersion(roomId, store.version(roomId), null), "");
            cached = store.read(roomId);
            check("redis-null-hit", cached.present() && cached.room() == null
                    && "null".equals(redis.opsForValue().get(roomKey(roomId, "detail"))), "");
            Long nullTtl = redis.getExpire(roomKey(roomId, "detail"), TimeUnit.SECONDS);
            check("redis-null-ttl", nullTtl != null && nullTtl > 0 && nullTtl <= NULL_TTL,
                    "actualSeconds=" + nullTtl);
            step = "redis-load-lock-owner";
            RoomCacheStore.Lease owner = store.tryAcquire(roomId);
            check("redis-load-lock-owner", owner != null, "");
            try (owner) {
                var contender = worker.submit(() -> {
                    RoomCacheStore.Lease lease = store.tryAcquire(roomId);
                    if (lease == null) return false;
                    try (lease) { return true; }
                });
                check("redis-load-lock-contention", !contender.get(5, TimeUnit.SECONDS), "secondThreadDenied=true");
            }
            var nextOwner = worker.submit(() -> {
                RoomCacheStore.Lease lease = store.tryAcquire(roomId);
                if (lease == null) return false;
                try (lease) { return true; }
            });
            check("redis-load-lock-release", nextOwner.get(5, TimeUnit.SECONDS), "nextThreadAcquired=true");
            step = "redis-null-expiry";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(NULL_TTL + 5);
            while (store.read(roomId).present() && System.nanoTime() < deadline) Thread.sleep(250);
            check("redis-null-expiry", !store.read(roomId).present(), "expiredOnRealRedis=true");
            // No global cleanup: the probe's detail/lock disappear naturally; its version has its own TTL.
        } finally {
            worker.shutdownNow();
            if (redisson != null) redisson.shutdown();
            connection.destroy();
        }
    }

    private record OutboxEvent(byte[] payload, String status, Long bookingId) {}

    private static OutboxEvent readEvent(Connection database, String eventId) throws Exception {
        step = "mq-outbox-read";
        try (var statement = database.prepareStatement(
                "SELECT payload,status,aggregate_id FROM booking_outbox WHERE event_id=? AND event_type=?")) {
            statement.setString(1, eventId);
            statement.setString(2, BookingOutboxService.EVENT_TYPE);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("event does not exist");
                return new OutboxEvent(result.getString(1).getBytes(StandardCharsets.UTF_8), result.getString(2), result.getLong(3));
            }
        }
    }

    private static long count(Connection database, String table, String eventId, String group) throws Exception {
        // Table names come only from the three constants at the call sites.
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE event_id=?" + (group == null ? "" : " AND consumer_group=?");
        try (var statement = database.prepareStatement(sql)) {
            statement.setString(1, eventId);
            if (group != null) statement.setString(2, group);
            try (var result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    private static List<Long> businessCounts(Connection database, String eventId) throws Exception {
        return List.of(count(database, "message_consumption", eventId, BUSINESS_GROUP),
                count(database, "booking_notification", eventId, null), count(database, "business_event_audit", eventId, null));
    }

    private static void mqSendOnly(String eventId) throws Exception {
        String sdkLocation = DefaultMQProducer.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
        check("mq-real-sdk-version", sdkLocation.contains("rocketmq-client-5.3.1.jar"), "actualVersion=5.3.1");
        String runId = UUID.randomUUID().toString().replace("-", "");
        DefaultMQProducer producer = null;
        step = "mq-database-connect";
        try (Connection database = DriverManager.getConnection(env("DB_URL"), env("DB_USERNAME"), env("DB_PASSWORD"))) {
            database.setReadOnly(true);
            OutboxEvent source = readEvent(database, eventId);
            BookingCreatedEvent event = JSON.readValue(source.payload(), BookingCreatedEvent.class);
            check("mq-existing-outbox-payload", eventId.equals(event.eventId()) && event.schemaVersion() == 1
                    && source.bookingId().equals(event.bookingId()) && event.userId() != null && event.roomId() != null,
                    "eventId=" + eventId + " outboxStatus=" + source.status());
            step = "mq-payload-matches-booking";
            try (var statement = database.prepareStatement(
                    "SELECT user_id,room_id,topic,start_time,end_time FROM booking_order WHERE id=?")) {
                statement.setLong(1, event.bookingId());
                try (var row = statement.executeQuery()) {
                    check("mq-payload-matches-booking", row.next()
                            && Objects.equals(event.userId(), row.getLong("user_id"))
                            && Objects.equals(event.roomId(), row.getLong("room_id"))
                            && Objects.equals(event.topic(), row.getString("topic"))
                            && Objects.equals(event.startTime(), row.getObject("start_time", LocalDateTime.class))
                            && Objects.equals(event.endTime(), row.getObject("end_time", LocalDateTime.class)), "");
                }
            }
            step = "mq-sdk-producer-start";
            producer = new DefaultMQProducer("smartoffice-probe-producer-" + runId);
            producer.setInstanceName("probe-send-only-" + runId);
            producer.setNamesrvAddr(env("ROCKETMQ_NAMESRV_ADDR"));
            producer.setRetryTimesWhenSendFailed(0);
            producer.setSendMsgTimeout(5000);
            producer.start();
            step = "mq-send-only-broker-ack";
            SendResult sent = producer.send(new Message(TOPIC, "BOOKING_CREATED", eventId, source.payload()), 5000);
            check("mq-send-only-broker-ack", sent.getSendStatus() == SendStatus.SEND_OK,
                    "sendStatus=" + sent.getSendStatus() + " brokerMessageId=" + sent.getMsgId()
                            + " queue=" + sent.getMessageQueue().getQueueId() + " offset=" + sent.getQueueOffset()
                            + " copies=1");
        } finally {
            if (producer != null) producer.shutdown();
        }
    }

    private static void mq(String eventId, boolean duplicate) throws Exception {
        step = "mq-database-connect";
        String sdkLocation = DefaultMQProducer.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
        check("mq-real-sdk-version", sdkLocation.contains("rocketmq-client-5.3.1.jar"), "actualVersion=5.3.1");
        String runId = UUID.randomUUID().toString().replace("-", "");
        String namesrv = env("ROCKETMQ_NAMESRV_ADDR");
        String observerGroup = "smartoffice-probe-" + runId;
        var positions = new ConcurrentHashMap<MessageQueue, Set<Long>>();
        var consumer = new DefaultMQPushConsumer(observerGroup);
        DefaultMQProducer producer = null;
        try (Connection database = DriverManager.getConnection(env("DB_URL"), env("DB_USERNAME"), env("DB_PASSWORD"))) {
            database.setReadOnly(true);
            OutboxEvent source = readEvent(database, eventId);
            BookingCreatedEvent event = JSON.readValue(source.payload(), BookingCreatedEvent.class);
            check("mq-existing-outbox-payload", eventId.equals(event.eventId()) && event.schemaVersion() == 1
                    && source.bookingId().equals(event.bookingId()), "eventId=" + eventId);
            check("mq-existing-outbox-sent", "SENT".equals(source.status()), "");
            List<Long> before = businessCounts(database, eventId);
            check("mq-existing-business-consumption", before.equals(List.of(1L, 1L, 1L)), "counts=" + before);
            consumer.setNamesrvAddr(namesrv);
            consumer.setInstanceName("probe-observer-" + runId);
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            consumer.setConsumeThreadMin(1);
            consumer.setConsumeThreadMax(2);
            consumer.setConsumeMessageBatchMaxSize(1);
            consumer.subscribe(TOPIC, "*");
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (var message : messages) {
                    try {
                        if (event.equals(JSON.readValue(message.getBody(), BookingCreatedEvent.class))) {
                            positions.computeIfAbsent(context.getMessageQueue(), ignored -> ConcurrentHashMap.newKeySet())
                                    .add(message.getQueueOffset());
                        }
                    } catch (Exception ignored) { /* Observer never invokes the business handler or writes database rows. */ }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            step = "mq-earliest-observer-start";
            consumer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (positions.isEmpty() && System.nanoTime() < deadline) Thread.sleep(250);
            check("mq-earliest-observer-history", !positions.isEmpty(), "group=" + observerGroup + " historicalEventObserved=true");
            if (duplicate) {
                step = "mq-sdk-producer-start";
                producer = new DefaultMQProducer("smartoffice-probe-producer-" + runId);
                producer.setInstanceName("probe-producer-" + runId);
                producer.setNamesrvAddr(namesrv);
                producer.setRetryTimesWhenSendFailed(0);
                producer.setSendMsgTimeout(5000);
                producer.start();
                step = "mq-retry-path-baseline";
                Map<String, Long> retriesBefore = retryOffsets(producer);
                check("mq-retry-path-baseline", !retriesBefore.isEmpty(), "brokerCountersRead=true");
                var sent = new ArrayList<SendResult>();
                for (int i = 0; i < 2; i++) {
                    step = "mq-duplicate-send-" + (i + 1);
                    SendResult result = producer.send(new Message(TOPIC, "BOOKING_CREATED", eventId, source.payload()), 5000);
                    check(step, result.getSendStatus() == SendStatus.SEND_OK,
                            "brokerMessageId=" + result.getMsgId() + " queue=" + result.getMessageQueue().getQueueId()
                                    + " offset=" + result.getQueueOffset());
                    sent.add(result);
                }
                check("mq-two-distinct-broker-messages", !sent.get(0).getMsgId().equals(sent.get(1).getMsgId()), "sameEventId=true");
                step = "mq-observe-duplicate-delivery";
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!allObserved(positions, sent) && System.nanoTime() < deadline) Thread.sleep(250);
                check("mq-observe-duplicate-delivery", allObserved(positions, sent), "copies=2");
                step = "mq-business-group-offset";
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                boolean consumed = false;
                while (!consumed && System.nanoTime() < deadline) {
                    consumed = allConsumed(producer, sent);
                    if (!consumed) Thread.sleep(1000);
                }
                check("mq-business-group-offset", consumed, "group=" + BUSINESS_GROUP + " advancedPastBothCopies=true");
                // Main-topic offsets also advance when a listener sends a failure to retry. Inspect those paths too.
                Thread.sleep(1000);
                step = "mq-no-retry-or-dlq";
                Map<String, Long> retriesAfter = retryOffsets(producer);
                check("mq-no-retry-or-dlq", retriesAfter.equals(retriesBefore),
                        "retryDlqAndBrokerScheduleOffsetsUnchanged=true");
            }
            step = "mq-business-idempotency-counts";
            List<Long> after = businessCounts(database, eventId);
            check(duplicate ? "mq-business-idempotency-counts" : "mq-observer-no-business-writes",
                    after.equals(before), "before=" + before + " after=" + after);
        } finally {
            consumer.shutdown();
            if (producer != null) producer.shutdown();
        }
    }

    private static boolean allObserved(Map<MessageQueue, Set<Long>> positions, List<SendResult> sent) {
        return sent.stream().allMatch(result -> positions.getOrDefault(result.getMessageQueue(), Set.of()).contains(result.getQueueOffset()));
    }

    @SuppressWarnings("deprecation") // SDK 5.3.1 exposes read-only broker administration through these accessors.
    private static boolean allConsumed(DefaultMQProducer producer, List<SendResult> sent) throws Exception {
        var client = producer.getDefaultMQProducerImpl().getMqClientFactory();
        for (SendResult result : sent) {
            var broker = client.findBrokerAddressInAdmin(result.getMessageQueue().getBrokerName());
            if (broker == null) return false;
            var stats = client.getMQClientAPIImpl().getConsumeStats(broker.getBrokerAddr(), BUSINESS_GROUP, TOPIC, 3000);
            var offset = stats.getOffsetTable().get(result.getMessageQueue());
            if (offset == null || offset.getConsumerOffset() <= result.getQueueOffset()) return false;
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private static Map<String, Long> retryOffsets(DefaultMQProducer producer) throws Exception {
        var client = producer.getDefaultMQProducerImpl().getMqClientFactory();
        // fetchPublishMessageQueues queries NameServer without filling this client's broker-address cache.
        // Refresh the real route before using the SDK's read-only broker administration API.
        client.updateTopicRouteInfoFromNameServer(TOPIC);
        Map<String, Long> offsets = new HashMap<>();
        var brokers = producer.fetchPublishMessageQueues(TOPIC).stream().map(MessageQueue::getBrokerName).distinct().toList();
        for (String brokerName : brokers) {
            var broker = client.findBrokerAddressInAdmin(brokerName);
            if (broker == null) throw new IllegalStateException("broker route missing");
            for (String topic : List.of("%RETRY%" + BUSINESS_GROUP, "%DLQ%" + BUSINESS_GROUP,
                    TopicValidator.RMQ_SYS_SCHEDULE_TOPIC)) {
                long maxOffsets;
                try {
                    var stats = client.getMQClientAPIImpl().getTopicStatsInfo(broker.getBrokerAddr(), topic, 3000);
                    maxOffsets = stats.getOffsetTable().values().stream().mapToLong(offset -> offset.getMaxOffset()).sum();
                } catch (MQBrokerException failure) {
                    if (failure.getResponseCode() != ResponseCode.TOPIC_NOT_EXIST) throw failure;
                    maxOffsets = 0;
                }
                offsets.put(brokerName + "/" + topic, maxOffsets);
            }
        }
        // Schedule counters are broker-wide: unrelated delayed traffic makes this conservative check fail.
        return offsets;
    }
}
