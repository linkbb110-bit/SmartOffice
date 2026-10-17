package com.geek.smartoffice.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BookingDTO {
    @NotNull(message = "会议室ID不能为空")
    @Positive
    private Long roomId;

    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "requestId 必须为1到64位字母、数字、下划线或连字符")
    private String requestId;

    @NotBlank(message = "会议主题不能为空")
    @Size(max = 200, message = "会议主题不能超过200个字符")
    private String topic;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;
}
