package com.geek.smartoffice.model.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class RoomSaveDTO {
    @NotBlank(message = "会议室名称不能为空")
    @Size(max = 100, message = "会议室名称不能超过100个字符")
    private String roomName;

    @NotNull(message = "会议室容量不能为空")
    @Min(value = 1, message = "会议室容量至少为1")
    @Max(value = 30, message = "会议室容量最多为100")
    private Integer capacity;

    // @NotBlank 标在泛型元素 String 上，校验列表中的每个设备名称，而不是要求列表本身非空。
    // 整个列表可以省略，服务层会统一转为空列表；这类“容器元素约束”不同于在字段上放 @NotBlank。
    private List<@NotBlank(message = "设备名称不能为空") String> equipment;

    @NotNull(message = "会议室状态不能为空")
    private Integer status;
}
