package com.geek.smartoffice.service;

import com.geek.smartoffice.model.dto.BookingDTO;

/**
 * 独立于预约入口的事务写接口，通过跨 Bean 调用触发 Spring 事务代理。
 * 实现会自行获取数据库房间行锁，因此方法名 InsideLock 不要求调用方一定已成功获取 Redis 锁。
 * 默认事务传播会加入外层事务，只有没有外层事务时，接口调用返回才意味着本次事务已提交。
 */
public interface BookingWriteService {
    Long createBookingInsideLock(BookingDTO dto, Long currentUserId);

    void cancel(Long orderId, Long currentUserId, boolean admin);
}
