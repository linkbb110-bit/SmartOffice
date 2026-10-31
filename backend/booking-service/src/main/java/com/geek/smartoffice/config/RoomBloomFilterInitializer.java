package com.geek.smartoffice.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geek.smartoffice.mapper.MeetingRoomMapper;
import com.geek.smartoffice.model.entity.MeetingRoom;
import com.geek.smartoffice.service.RoomBloomFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ApplicationRunner 在应用上下文创建后执行，用数据库中现有会议室 ID 补充 Redis 布隆过滤器。
 * LOWEST_PRECEDENCE 表示在有序 Runner 中尽量靠后执行；该预热步骤只准备查询辅助数据。
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class RoomBloomFilterInitializer implements ApplicationRunner {

    private final MeetingRoomMapper meetingRoomMapper;
    private final RoomBloomFilterService roomBloomFilterService;

    /**
     * 只查询会议室 ID 并构造成初始化输入，MyBatis-Plus 的逻辑删除规则会排除已删除记录。
     * initialize 向已有布隆过滤器补入 ID，不清空旧位；它不能替代后续数据库中的存在性检查。
     * Redis 初始化失败会记录告警并继续启动，数据库读取位于 try 之外，失败仍向外传播。
     */
    @Override
    public void run(ApplicationArguments args) {
        List<Long> roomIds = meetingRoomMapper.selectList(new LambdaQueryWrapper<MeetingRoom>()
                        .select(MeetingRoom::getId))
                .stream()
                .map(MeetingRoom::getId)
                .toList();
        try {
            // 这一步可能首次触发 Redisson 建连，异常降级仅覆盖布隆预热，不吞掉数据库查询失败。
            roomBloomFilterService.initialize(roomIds);
            log.info("room bloom filter initialized, activeRoomCount={}", roomIds.size());
        } catch (RuntimeException failure) {
            log.warn("room bloom initialization unavailable, continuing with database checks, type={}", failure.getClass().getSimpleName());
        }
    }
}
