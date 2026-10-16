package com.geek.smartoffice.common.enums;

public enum BookingStatus {
    BOOKED(1),
    CANCELED(2);

    private final int code;

    BookingStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
