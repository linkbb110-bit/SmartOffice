package com.geek.smartoffice.service;

import com.geek.smartoffice.model.dto.RoomSaveDTO;
import com.geek.smartoffice.model.entity.MeetingRoom;
import com.geek.smartoffice.model.vo.RoomVO;

import java.util.List;

public interface MeetingRoomService {
    List<RoomVO> listRooms();

    // 详情读取可命中缓存，仍统一拒绝不存在或逻辑删除的房间；返回实体供需要房间数据的内部流程使用。
    MeetingRoom getExistingRoom(Long roomId);

    Long createRoom(RoomSaveDTO dto);

    void updateRoom(Long roomId, RoomSaveDTO dto);

    void deleteRoom(Long roomId);
}
