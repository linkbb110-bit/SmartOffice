-- 仅 smoke-local.ps1 的全新 H2 内存库使用，不用于已有 MySQL。
INSERT INTO meeting_room(id,room_name,capacity,equipment,status,is_deleted)
VALUES(1,'本地冒烟测试会议室',8,'["白板"]',1,0);
