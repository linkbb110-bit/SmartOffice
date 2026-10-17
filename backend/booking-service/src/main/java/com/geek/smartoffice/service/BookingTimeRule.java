package com.geek.smartoffice.service;

import java.time.LocalDateTime;

public final class BookingTimeRule {

    private BookingTimeRule() {
    }

    public static boolean overlaps(LocalDateTime firstStart,
                                   LocalDateTime firstEnd,
                                   LocalDateTime secondStart,
                                   LocalDateTime secondEnd) {
        return firstStart.isBefore(secondEnd) && firstEnd.isAfter(secondStart);
    }
}
