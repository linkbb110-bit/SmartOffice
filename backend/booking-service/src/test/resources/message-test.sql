CREATE TABLE IF NOT EXISTS booking_outbox (
 event_id VARCHAR(36) PRIMARY KEY, aggregate_id BIGINT NOT NULL, event_type VARCHAR(64) NOT NULL,
 payload TEXT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT DEFAULT 0,
 available_at TIMESTAMP NOT NULL, lease_until TIMESTAMP, lease_owner VARCHAR(36),
 last_error VARCHAR(400), broker_message_id VARCHAR(128), created_at TIMESTAMP NOT NULL,
 updated_at TIMESTAMP NOT NULL, UNIQUE(aggregate_id,event_type)
);
CREATE TABLE IF NOT EXISTS message_consumption (
 event_id VARCHAR(36) NOT NULL, consumer_group VARCHAR(64) NOT NULL, consumed_at TIMESTAMP NOT NULL,
 PRIMARY KEY(event_id,consumer_group)
);
CREATE TABLE IF NOT EXISTS booking_notification (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(36) NOT NULL UNIQUE,
 booking_id BIGINT NOT NULL, user_id BIGINT NOT NULL, content VARCHAR(500) NOT NULL,
 created_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS business_event_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(36) NOT NULL UNIQUE,
 booking_id BIGINT NOT NULL, event_type VARCHAR(64) NOT NULL, payload TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL
);
