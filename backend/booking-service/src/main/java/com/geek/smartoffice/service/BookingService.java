package com.geek.smartoffice.service;

import com.geek.smartoffice.model.dto.BookingDTO;
import com.geek.smartoffice.model.vo.BookingVO;

import java.util.List;

public interface BookingService {
    Long book(BookingDTO dto, Long currentUserId);

    void cancel(Long orderId, Long currentUserId, boolean admin);

    List<BookingVO> listMyBookings(Long userId);

    List<BookingVO> listAllBookings();
}
