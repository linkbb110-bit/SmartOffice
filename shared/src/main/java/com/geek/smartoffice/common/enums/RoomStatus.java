package com.geek.smartoffice.common.enums;

import com.geek.smartoffice.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

public enum RoomStatus {
    MAINTENANCE(0),
    AVAILABLE(1);

    private final int code;

    RoomStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static RoomStatus fromCode(Integer code) {
        for (RoomStatus value : values()) {
            if (Integer.valueOf(value.code).equals(code)) {
                return value;
            }
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "会议室状态不合法");
    }
}
