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

@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class RoomBloomFilterInitializer implements ApplicationRunner {

    private final MeetingRoomMapper meetingRoomMapper;
    private final RoomBloomFilterService roomBloomFilterService;

    @Override
    public void run(ApplicationArguments args) {
        List<Long> roomIds = meetingRoomMapper.selectList(new LambdaQueryWrapper<MeetingRoom>()
                        .select(MeetingRoom::getId))
                .stream()
                .map(MeetingRoom::getId)
                .toList();
        try {
            roomBloomFilterService.initialize(roomIds);
            log.info("room bloom filter initialized, activeRoomCount={}", roomIds.size());
        } catch (RuntimeException failure) {
            log.warn("room bloom initialization unavailable, continuing with database checks, type={}", failure.getClass().getSimpleName());
        }
    }
}
