package com.geek.smartoffice.service;

import java.util.Collection;

/**
 * 维护房间 ID 的概率集合，命中只代表“可能存在”，不代表房间可用或尚未删除。
 * 数据库提交到补录之间存在时间差，异常也可能使补录落后，因此未命中仍不能替代数据库存在性判断。
 */
public interface RoomBloomFilterService {
    boolean mightContain(Long roomId);

    void put(Long roomId);

    void initialize(Collection<Long> roomIds);
}
