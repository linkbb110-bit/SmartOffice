SET NAMES utf8mb4;

INSERT IGNORE INTO meeting_room (id, room_name, capacity, equipment, status, is_deleted)
VALUES
    (1, '研发一号会议室', 12, '["投影仪","白板","视频会议终端"]', 1, 0),
    (2, '产品评审室', 8, '["电视","白板","无线投屏"]', 1, 0),
    (3, '培训室A', 20, '["投影仪","麦克风","音响","白板"]', 1, 0),
    (4, '面试室', 6, '["电视","白板"]', 0, 0);

