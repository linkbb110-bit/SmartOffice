package com.geek.smartoffice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geek.smartoffice.model.entity.BookingOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;

import java.time.LocalDateTime;

public interface BookingOrderMapper extends BaseMapper<BookingOrder> {
    // 显式订单行锁只在事务内持续到提交或回滚；BaseMapper 的普通 selectById 不会自动加这把锁。
    // 当前预约创建、取消与房间管理使用的是房间行锁，调用方需按具体业务选择统一的协调对象。
    @Select("SELECT * FROM booking_order WHERE id = #{id} FOR UPDATE")
    BookingOrder selectForUpdate(@Param("id") Long id);
    // 同一用户和 requestId 定位幂等记录；#{...} 通过 @Param 对应方法参数，以绑定参数执行查询。
    // flushCache 在查询前刷新 MyBatis 缓存，避免同一会话的锁后复查复用第一次“未找到”的结果。
    // 它与 READ_COMMITTED 配合让复查真正读取数据库：隔离级别决定可见性，刷新缓存负责避免跳过查询。
    @Select("SELECT * FROM booking_order WHERE user_id = #{userId} AND request_id = #{requestId}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    BookingOrder findByRequest(@Param("userId") Long userId, @Param("requestId") String requestId);

    // 状态条件与更新在一条 SQL 中完成，只有仍为已预约的订单才切换为取消。
    // 返回影响行数；重复取消为 0，调用方将其视为幂等完成，不必先查状态再更新。
    @Update("UPDATE booking_order SET status = 2, updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 1")
    int cancelBooked(@Param("id") Long id);

    // “有效预约”要求状态为已预约且结束时间晚于 now，包含进行中和未来预约。
    // 删除房间前在房间行锁内执行此查询，防止检查结束后又插入一个尚未结束的预约。
    @Select("SELECT COUNT(*) FROM booking_order WHERE room_id = #{roomId} AND status = 1 AND end_time > #{now}")
    long countActive(@Param("roomId") Long roomId, @Param("now") LocalDateTime now);

    // 按 [start, end) 半开区间判断：旧开始 < 新结束，且旧结束 > 新开始，两项同时成立才重叠。
    // 使用严格不等号，所以前一场的结束等于后一场的开始时可直接衔接；取消状态由 status 参数排除。
    // 此 SQL 只负责计数，不自行串行化并发预约，调用方需先取得同一房间行锁再检查与插入。
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
