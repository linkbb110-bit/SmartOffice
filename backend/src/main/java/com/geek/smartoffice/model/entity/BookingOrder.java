package com.geek.smartoffice.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("booking_order")
public class BookingOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long roomId;
    private String topic;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private String requestId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
