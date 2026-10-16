package com.geek.smartoffice.cache;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.geek.smartoffice.config.RoomBloomFilterInitializer;
import com.geek.smartoffice.mapper.MeetingRoomMapper;
import com.geek.smartoffice.model.entity.MeetingRoom;
import com.geek.smartoffice.service.RoomBloomFilterService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoomBloomFilterInitializerTest {
    @Test
    void redisBloomFailureDoesNotPreventApplicationStartup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "cache-test"), MeetingRoom.class);
        MeetingRoomMapper mapper = mock(MeetingRoomMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        RoomBloomFilterService bloom = mock(RoomBloomFilterService.class);
        doThrow(new IllegalStateException("redis unavailable")).when(bloom).initialize(List.of());
        assertDoesNotThrow(() -> new RoomBloomFilterInitializer(mapper, bloom).run(null));
    }
}
