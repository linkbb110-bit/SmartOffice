package com.geek.smartoffice.service;

import java.time.LocalDateTime;

public final class BookingTimeRule {

    private BookingTimeRule() {
    }

    // 假定每个输入时段已满足 start < end；这里仅判断两个合法时段是否重叠，不负责参数校验。
    // 使用 [start, end) 半开区间，两个严格不等式都成立才算重叠；首尾相接不占用同一时刻。
    // 该规则与预约冲突 SQL 保持一致，避免 Java 判断和数据库判断对边界时间产生不同结果。
    public static boolean overlaps(LocalDateTime firstStart,
                                   LocalDateTime firstEnd,
                                   LocalDateTime secondStart,
                                   LocalDateTime secondEnd) {
        return firstStart.isBefore(secondEnd) && firstEnd.isAfter(secondStart);
    }
}
