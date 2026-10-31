package com.geek.smartoffice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geek.smartoffice.model.entity.MeetingRoom;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MeetingRoomMapper extends BaseMapper<MeetingRoom> {
    // FOR UPDATE 读取并锁住指定房间行，调用方必须位于数据库事务中，锁才会覆盖后续检查和写入。
    // 预约、取消及房间管理共用此锁，直到事务提交或回滚再释放；不同房间可各自并行处理。
    // 这是自定义 SQL，服务层仍显式检查逻辑删除标记，不能假设查询会自动排除已删除房间。
    @Select("SELECT * FROM meeting_room WHERE id = #{id} FOR UPDATE")
    MeetingRoom selectForUpdate(@Param("id") Long id);
}
