package com.geek.smartoffice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geek.smartoffice.model.entity.BookingOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;

import java.time.LocalDateTime;

public interface BookingOrderMapper extends BaseMapper<BookingOrder> {
    @Select("SELECT * FROM booking_order WHERE id = #{id} FOR UPDATE")
    BookingOrder selectForUpdate(@Param("id") Long id);
    @Select("SELECT * FROM booking_order WHERE user_id = #{userId} AND request_id = #{requestId}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    BookingOrder findByRequest(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Update("UPDATE booking_order SET status = 2, updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 1")
    int cancelBooked(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM booking_order WHERE room_id = #{roomId} AND status = 1 AND end_time > #{now}")
    long countActive(@Param("roomId") Long roomId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*)
            FROM booking_order
            WHERE room_id = #{roomId}
              AND status = #{status}
              AND start_time < #{newEnd}
              AND end_time > #{newStart}
            """)
    Long countConflicts(@Param("roomId") Long roomId,
                        @Param("status") Integer status,
                        @Param("newStart") LocalDateTime newStart,
                        @Param("newEnd") LocalDateTime newEnd);
}
