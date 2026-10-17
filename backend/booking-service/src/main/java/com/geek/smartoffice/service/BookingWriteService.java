package com.geek.smartoffice.service;

import com.geek.smartoffice.model.dto.BookingDTO;

public interface BookingWriteService {
    Long createBookingInsideLock(BookingDTO dto, Long currentUserId);

    void cancel(Long orderId, Long currentUserId, boolean admin);
}
