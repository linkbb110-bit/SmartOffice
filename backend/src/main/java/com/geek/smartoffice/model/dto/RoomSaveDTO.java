package com.geek.smartoffice.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class RoomSaveDTO {
    @NotBlank(message = "会议室名称不能为空")
    @Size(max = 100, message = "会议室名称不能超过100个字符")
    private String roomName;

    @NotNull(message = "会议室容量不能为空")
    @Min(value = 1, message = "会议室容量至少为1")
    private Integer capacity;

    private List<@NotBlank(message = "设备名称不能为空") String> equipment;

    @NotNull(message = "会议室状态不能为空")
    private Integer status;
}
