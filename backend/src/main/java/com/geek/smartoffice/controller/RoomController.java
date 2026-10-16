package com.geek.smartoffice.controller;

import com.geek.smartoffice.common.annotation.OperationLog;
import com.geek.smartoffice.common.result.Result;
import com.geek.smartoffice.model.dto.RoomSaveDTO;
import com.geek.smartoffice.model.vo.RoomVO;
import com.geek.smartoffice.model.entity.MeetingRoom;
import com.geek.smartoffice.service.MeetingRoomService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/rooms")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class RoomController {

    private final MeetingRoomService meetingRoomService;

    @GetMapping("/list")
    public Result<List<RoomVO>> listRooms() {
        return Result.success(meetingRoomService.listRooms());
    }

    @GetMapping("/{roomId}")
    public Result<MeetingRoom> roomDetail(@PathVariable Long roomId) {
        return Result.success(meetingRoomService.getExistingRoom(roomId));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @OperationLog("新增会议室")
    public Result<Long> createRoom(@Valid @RequestBody RoomSaveDTO dto) {
        return Result.success(meetingRoomService.createRoom(dto));
    }

    @PutMapping("/{roomId}")
    @PreAuthorize("hasRole('ADMIN')")
    @OperationLog("修改会议室")
    public Result<Void> updateRoom(@PathVariable Long roomId, @Valid @RequestBody RoomSaveDTO dto) {
        meetingRoomService.updateRoom(roomId, dto);
        return Result.success();
    }

    @DeleteMapping("/{roomId}")
    @PreAuthorize("hasRole('ADMIN')")
    @OperationLog("删除会议室")
    public Result<Void> deleteRoom(@PathVariable Long roomId) {
        meetingRoomService.deleteRoom(roomId);
        return Result.success();
    }
}
