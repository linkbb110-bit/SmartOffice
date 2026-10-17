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
    private static final long EXPECTED_INSERTIONS = 10_000L;
    private static final double FALSE_POSITIVE_RATE = 0.01D;

    private final RedissonClient redissonClient;

    @Override
    public boolean mightContain(Long roomId) {
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
        RBloomFilter<Long> filter = filter();
        ensureInitialized(filter);
        roomIds.forEach(filter::add);
    }

    private RBloomFilter<Long> filter() {
        return redissonClient.getBloomFilter(BLOOM_KEY);
    }

    private void ensureInitialized(RBloomFilter<Long> filter) {
        filter.tryInit(EXPECTED_INSERTIONS, FALSE_POSITIVE_RATE);
    }
}
