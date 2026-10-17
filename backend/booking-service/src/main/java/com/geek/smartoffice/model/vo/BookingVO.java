package com.geek.smartoffice.model.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class BookingVO {
    Long id;
    Long userId;
    Long roomId;
    String topic;
    LocalDateTime startTime;
    LocalDateTime endTime;
    Integer status;
}
