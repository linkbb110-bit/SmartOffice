-- 仅由操作者审查后在已有本地开发库执行一次；不删除或覆盖已有记录。
ALTER TABLE booking_order ADD COLUMN request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE booking_order ADD UNIQUE KEY uk_booking_request (user_id, request_id);
