package com.geek.smartoffice.model.vo;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class RoomVO {
    Long id;
    String roomName;
    Integer capacity;
    List<String> equipment;
    Integer status;
}
