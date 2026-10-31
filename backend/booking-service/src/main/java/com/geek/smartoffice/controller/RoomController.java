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

/**
 * 将会议室 HTTP 请求转交 MeetingRoomService，读取接口由安全过滤器要求已登录。
 * 新增、修改、删除再通过方法安全代理检查 ADMIN 角色，业务层负责状态、存在性与删除约束。
 * bearerAuth 声明用于接口文档展示，真正的访问限制来自 Security 配置与 PreAuthorize。
 */
@RestController
@RequestMapping("/rooms")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class RoomController {

    private final MeetingRoomService meetingRoomService;

    // 返回列表用 RoomVO 表达展示字段，设备等存储格式的转换由业务层处理。
    @GetMapping("/list")
    public Result<List<RoomVO>> listRooms() {
        return Result.success(meetingRoomService.listRooms());
    }

    // MVC 将路径中的 roomId 转为 Long；业务层读取详情缓存并对不存在的会议室抛出业务异常。
    @GetMapping("/{roomId}")
    public Result<MeetingRoom> roomDetail(@PathVariable Long roomId) {
        return Result.success(meetingRoomService.getExistingRoom(roomId));
    }

    /**
     * 请求 JSON 先绑定为 RoomSaveDTO 并执行字段校验，校验失败不会进入方法体。
     * 管理员通过授权后由业务事务创建会议室，成功返回 ID，缓存及布隆发布由业务层安排。
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @OperationLog("新增会议室")
    public Result<Long> createRoom(@Valid @RequestBody RoomSaveDTO dto) {
        return Result.success(meetingRoomService.createRoom(dto));
    }

    /**
     * 路径指定目标会议室，请求体提供新的完整字段；两类输入由 MVC 在调用前完成绑定。
     * {@code @Valid} 处理 DTO 约束，业务层再锁定房间、验证状态并更新，成功返回空数据的 Result。
     */
    @PutMapping("/{roomId}")
    @PreAuthorize("hasRole('ADMIN')")
    @OperationLog("修改会议室")
    public Result<Void> updateRoom(@PathVariable Long roomId, @Valid @RequestBody RoomSaveDTO dto) {
        meetingRoomService.updateRoom(roomId, dto);
        return Result.success();
    }

    // 管理员身份只决定是否可发起删除；能否删除还取决于业务层对未结束预约的事务检查。
    @DeleteMapping("/{roomId}")
    @PreAuthorize("hasRole('ADMIN')")
    @OperationLog("删除会议室")
    public Result<Void> deleteRoom(@PathVariable Long roomId) {
        meetingRoomService.deleteRoom(roomId);
        return Result.success();
    }
}
