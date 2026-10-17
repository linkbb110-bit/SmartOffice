package com.geek.smartoffice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geek.smartoffice.model.entity.MeetingRoom;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MeetingRoomMapper extends BaseMapper<MeetingRoom> {
    @Select("SELECT * FROM meeting_room WHERE id = #{id} FOR UPDATE")
    MeetingRoom selectForUpdate(@Param("id") Long id);
}
