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
        // 这里直接 new 初始化器，不经过 Spring/MyBatis 的自动装配阶段。
        // 先登记实体表信息，让初始化器中的 Lambda 查询能解析属性对应的数据库列。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "cache-test"), MeetingRoom.class);
        MeetingRoomMapper mapper = mock(MeetingRoomMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        RoomBloomFilterService bloom = mock(RoomBloomFilterService.class);
        doThrow(new IllegalStateException("redis unavailable")).when(bloom).initialize(List.of());
        assertDoesNotThrow(() -> new RoomBloomFilterInitializer(mapper, bloom).run(null));
    }
}
