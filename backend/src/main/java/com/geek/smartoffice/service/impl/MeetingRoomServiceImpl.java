package com.geek.smartoffice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geek.smartoffice.common.enums.RoomStatus;
import com.geek.smartoffice.cache.RoomCache;
import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.mapper.MeetingRoomMapper;
import com.geek.smartoffice.mapper.BookingOrderMapper;
import com.geek.smartoffice.model.dto.RoomSaveDTO;
import com.geek.smartoffice.model.entity.MeetingRoom;
import com.geek.smartoffice.model.vo.RoomVO;
import com.geek.smartoffice.service.MeetingRoomService;
import com.geek.smartoffice.service.RoomBloomFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class MeetingRoomServiceImpl implements MeetingRoomService {

    private final MeetingRoomMapper meetingRoomMapper;
    private final RoomBloomFilterService roomBloomFilterService;
    private final ObjectMapper objectMapper;
    private final RoomCache roomCache;
    private final BookingOrderMapper bookingOrderMapper;
    private final Clock clock;

    @Override
    public List<RoomVO> listRooms() {
        return meetingRoomMapper.selectList(null).stream()
                .map(this::toRoomVO)
                .toList();
    }

    @Override
    public MeetingRoom getExistingRoom(Long roomId) {
        MeetingRoom room = roomCache.get(roomId, () -> meetingRoomMapper.selectById(roomId));
        if (room == null || Integer.valueOf(1).equals(room.getIsDeleted())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "会议室不存在");
        }
        return room;
    }

    @Override
    @Transactional
    public Long createRoom(RoomSaveDTO dto) {
        RoomStatus.fromCode(dto.getStatus());
        MeetingRoom room = new MeetingRoom();
        copy(dto, room);
        meetingRoomMapper.insert(room);
        afterCommit(room.getId(), true);
        return room.getId();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateRoom(Long roomId, RoomSaveDTO dto) {
        MeetingRoom room = lockExistingRoom(roomId);
        RoomStatus.fromCode(dto.getStatus());
        copy(dto, room);
        // Preserve creation time in storage; do not explicitly write the previously loaded timestamp.
        room.setCreatedAt(null);
        room.setUpdatedAt(LocalDateTime.now(clock).withNano(0));
        meetingRoomMapper.updateById(room);
        afterCommit(roomId, false);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteRoom(Long roomId) {
        lockExistingRoom(roomId);
        if (bookingOrderMapper.countActive(roomId, LocalDateTime.now(clock)) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "会议室存在尚未结束的预约，不能删除");
        }
        meetingRoomMapper.deleteById(roomId);
        afterCommit(roomId, false);
    }

    private MeetingRoom lockExistingRoom(Long roomId) {
        MeetingRoom room = meetingRoomMapper.selectForUpdate(roomId);
        if (room == null || Integer.valueOf(1).equals(room.getIsDeleted())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "会议室不存在");
        }
        return room;
    }

    private void copy(RoomSaveDTO dto, MeetingRoom room) {
        room.setRoomName(dto.getRoomName().trim());
        room.setCapacity(dto.getCapacity());
        room.setEquipment(writeEquipment(dto.getEquipment()));
        room.setStatus(dto.getStatus());
    }

    private RoomVO toRoomVO(MeetingRoom room) {
        return RoomVO.builder()
                .id(room.getId())
                .roomName(room.getRoomName())
                .capacity(room.getCapacity())
                .equipment(readEquipment(room.getEquipment()))
                .status(room.getStatus())
                .build();
    }

    private String writeEquipment(List<String> equipment) {
        List<String> values = equipment == null
                ? Collections.emptyList()
                : equipment.stream().map(String::trim).filter(StringUtils::hasText).toList();
        try {
            String encoded = objectMapper.writeValueAsString(values);
            // VARCHAR(500) counts characters, including JSON syntax/escapes, not UTF-8 bytes.
            if (encoded.codePointCount(0, encoded.length()) > 500) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "会议室设备列表过长，编码后不能超过500个字符");
            }
            return encoded;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "会议室设备序列化失败");
        }
    }

    private List<String> readEquipment(String equipment) {
        if (!StringUtils.hasText(equipment)) {
            return Collections.emptyList();
        }
        String text = equipment.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                return objectMapper.readValue(text, new TypeReference<List<String>>() {
                }).stream().filter(StringUtils::hasText).toList();
            } catch (JsonProcessingException ignored) {
                // Fall through for legacy comma-separated records.
            }
        }
        return Arrays.stream(text.split("[,，、]"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private void afterCommit(Long roomId, boolean publishBloom) {
        Runnable publication = () -> {
            roomCache.invalidate(roomId);
            if (publishBloom) {
                try { roomBloomFilterService.put(roomId); }
                catch (RuntimeException failure) {
                    log.warn("room bloom update failed after commit roomId={} type={}", roomId, failure.getClass().getSimpleName());
                }
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publication.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publication.run();
            }
        });
    }
}
