package com.geek.smartoffice.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingTimeRuleTest {

    @Test
    void touchingIntervalsDoNotOverlap() {
        LocalDateTime ten = LocalDateTime.of(2026, 8, 23, 10, 0);
        LocalDateTime eleven = LocalDateTime.of(2026, 8, 23, 11, 0);
        LocalDateTime noon = LocalDateTime.of(2026, 8, 23, 12, 0);

        assertFalse(BookingTimeRule.overlaps(ten, eleven, eleven, noon));
    }

    @Test
    void partialOverlapIsDetected() {
        LocalDateTime ten = LocalDateTime.of(2026, 8, 23, 10, 0);
        LocalDateTime eleven = LocalDateTime.of(2026, 8, 23, 11, 0);
        LocalDateTime tenThirty = LocalDateTime.of(2026, 8, 23, 10, 30);
        LocalDateTime elevenThirty = LocalDateTime.of(2026, 8, 23, 11, 30);

        assertTrue(BookingTimeRule.overlaps(ten, eleven, tenThirty, elevenThirty));
    }

    @Test
    void containingIntervalIsDetected() {
        LocalDateTime ten = LocalDateTime.of(2026, 8, 23, 10, 0);
        LocalDateTime noon = LocalDateTime.of(2026, 8, 23, 12, 0);
        LocalDateTime tenThirty = LocalDateTime.of(2026, 8, 23, 10, 30);
        LocalDateTime eleven = LocalDateTime.of(2026, 8, 23, 11, 0);

        assertTrue(BookingTimeRule.overlaps(ten, noon, tenThirty, eleven));
    }
}
