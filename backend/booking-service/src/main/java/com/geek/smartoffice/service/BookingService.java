package com.geek.smartoffice.service;

import com.geek.smartoffice.model.dto.BookingDTO;
import com.geek.smartoffice.model.vo.BookingVO;

import java.util.List;

/**
 * 对外提供预约操作及查询；实现层先协调房间 Redis 锁，再调用独立写服务完成数据库事务。
 * currentUserId 由认证上下文传入，预约归属和取消权限使用该身份，不接受客户端自行指定归属。
 */
public interface BookingService {
    Long book(BookingDTO dto, Long currentUserId);

    void cancel(Long orderId, Long currentUserId, boolean admin);

    List<BookingVO> listMyBookings(Long userId);

    List<BookingVO> listAllBookings();
}
