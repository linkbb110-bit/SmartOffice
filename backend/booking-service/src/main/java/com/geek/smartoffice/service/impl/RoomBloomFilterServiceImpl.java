package com.geek.smartoffice.service.impl;

import com.geek.smartoffice.service.RoomBloomFilterService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
@RequiredArgsConstructor
public class RoomBloomFilterServiceImpl implements RoomBloomFilterService {

    public static final String BLOOM_KEY = "smartoffice:room:id:bloom";
    // 容量与目标误判率用于首次创建位图；实际加入的 ID 越多，误判率越可能高于最初目标。
    // Bloom 只保存哈希位，适合判断“可能出现过”，不能据此返回房间详情或判断可预约状态。
    private static final long EXPECTED_INSERTIONS = 10_000L;
    private static final double FALSE_POSITIVE_RATE = 0.01D;

    private final RedissonClient redissonClient;

    @Override
    public boolean mightContain(Long roomId) {
        // true 仅说明相关哈希位都存在，可能是不同 ID 碰巧设置了同样的位，仍须读取房间数据确认。
        // 算法对已成功加入且位图完整的元素不会漏判，但本项目提交后补录失败、Redis 数据丢失会造成滞后。
        // 因此业务不能只凭 false 拒绝数据库中可能存在的房间，且 Redis 异常由调用方决定如何降级。
        return filter().contains(roomId);
    }

    @Override
    public void put(Long roomId) {
        RBloomFilter<Long> filter = filter();
        ensureInitialized(filter);
        filter.add(roomId);
    }

    @Override
    public void initialize(Collection<Long> roomIds) {
        // 初始化采用向现有过滤器补入 ID 的方式，不清空历史位，以免重建期间丢失其他请求刚加入的 ID。
        // 已删除房间的位仍保留，可能继续命中；是否删除应由数据库/详情读取确认，不能依赖 Bloom 精确判断。
        RBloomFilter<Long> filter = filter();
        ensureInitialized(filter);
        // filter::add 是绑定了当前过滤器的方法引用，forEach 遍历时才对每个 ID 实际执行 add。
        roomIds.forEach(filter::add);
    }

    private RBloomFilter<Long> filter() {
        return redissonClient.getBloomFilter(BLOOM_KEY);
    }

    private void ensureInitialized(RBloomFilter<Long> filter) {
        // tryInit 仅在过滤器尚未初始化时建立参数；已存在时不重置位图，也不会修改原有容量配置。
        filter.tryInit(EXPECTED_INSERTIONS, FALSE_POSITIVE_RATE);
    }
}
