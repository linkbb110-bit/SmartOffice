package com.geek.smartoffice.service;

import com.geek.smartoffice.model.dto.RoomSaveDTO;
import com.geek.smartoffice.model.entity.MeetingRoom;
import com.geek.smartoffice.model.vo.RoomVO;

import java.util.List;

public interface MeetingRoomService {
    List<RoomVO> listRooms();

    MeetingRoom getExistingRoom(Long roomId);

    Long createRoom(RoomSaveDTO dto);

    void updateRoom(Long roomId, RoomSaveDTO dto);

    void deleteRoom(Long roomId);
}
