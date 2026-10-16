package com.geek.smartoffice.service;

import java.util.Collection;

public interface RoomBloomFilterService {
    boolean mightContain(Long roomId);

    void put(Long roomId);

    void initialize(Collection<Long> roomIds);
}
