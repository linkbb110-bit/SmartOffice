CREATE TABLE IF NOT EXISTS booking_outbox (
 event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 aggregate_id BIGINT NOT NULL, event_type VARCHAR(64) NOT NULL, payload TEXT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 available_at DATETIME(6) NOT NULL, lease_until DATETIME(6) NULL, lease_owner VARCHAR(36) NULL,
 last_error VARCHAR(400) NULL, broker_message_id VARCHAR(128) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_outbox_aggregate_type(aggregate_id,event_type),
 KEY idx_outbox_retry(status,available_at), KEY idx_outbox_lease(status,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS message_consumption (
 event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 consumer_group VARCHAR(64) NOT NULL, consumed_at DATETIME(6) NOT NULL,
 PRIMARY KEY(event_id,consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS booking_notification (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 booking_id BIGINT NOT NULL, user_id BIGINT NOT NULL, content VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL, UNIQUE KEY uk_notification_event(event_id),
 KEY idx_notification_user(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS business_event_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 booking_id BIGINT NOT NULL, event_type VARCHAR(64) NOT NULL, payload TEXT NOT NULL,
 created_at DATETIME(6) NOT NULL, UNIQUE KEY uk_audit_event(event_id), KEY idx_audit_booking(booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
