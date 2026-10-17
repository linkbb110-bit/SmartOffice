package com.geek.smartoffice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geek.smartoffice.common.enums.RoomStatus;
import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.mapper.MeetingRoomMapper;
import com.geek.smartoffice.mapper.BookingOrderMapper;
import com.geek.smartoffice.cache.RoomCache;
import com.geek.smartoffice.model.dto.RoomSaveDTO;
import com.geek.smartoffice.model.entity.MeetingRoom;
import com.geek.smartoffice.model.vo.RoomVO;
import com.geek.smartoffice.service.impl.MeetingRoomServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.time.Clock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MeetingRoomServiceImplTest {

    @Test
    void listMapsJsonEquipmentForRecoveredFrontendContract() {
        MeetingRoomMapper mapper = mock(MeetingRoomMapper.class);
        RoomBloomFilterService bloom = mock(RoomBloomFilterService.class);
        MeetingRoom room = new MeetingRoom();
        room.setId(1L);
        room.setRoomName("星云会议室");
        room.setCapacity(12);
        room.setEquipment("[\"投影仪\",\"白板\"]");
        room.setStatus(RoomStatus.AVAILABLE.getCode());
        when(mapper.selectList(null)).thenReturn(List.of(room));

        List<RoomVO> result = service(mapper, bloom).listRooms();

        assertEquals(List.of("投影仪", "白板"), result.get(0).getEquipment());
    }

    @Test
    void missingRoomReturns404() {
        MeetingRoomMapper mapper = mock(MeetingRoomMapper.class);
        RoomBloomFilterService bloom = mock(RoomBloomFilterService.class);
        when(mapper.selectById(88L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service(mapper, bloom).getExistingRoom(88L));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void createRoomPublishesNewIdToBloom() {
        MeetingRoomMapper mapper = mock(MeetingRoomMapper.class);
        RoomBloomFilterService bloom = mock(RoomBloomFilterService.class);
        doAnswer(invocation -> {
            MeetingRoom room = invocation.getArgument(0);
            room.setId(9L);
            return 1;
        }).when(mapper).insert(any(MeetingRoom.class));
        RoomSaveDTO dto = new RoomSaveDTO();
        dto.setRoomName("新会议室");
        dto.setCapacity(10);
        dto.setEquipment(List.of("白板"));
        dto.setStatus(RoomStatus.AVAILABLE.getCode());

        Long id = service(mapper, bloom).createRoom(dto);

        assertEquals(9L, id);
        verify(bloom).put(9L);
    }

    @Test
    void administratorUpdateAcquiresSameRoomRowLockAsBooking() {
        MeetingRoomMapper mapper = mock(MeetingRoomMapper.class);
        MeetingRoom room = new MeetingRoom();
        room.setId(1L);
        room.setIsDeleted(0);
        when(mapper.selectById(1L)).thenReturn(room);
        when(mapper.selectForUpdate(1L)).thenReturn(room);
        RoomSaveDTO dto = roomDto();
        service(mapper, mock(RoomBloomFilterService.class)).updateRoom(1L, dto);
        var order = inOrder(mapper);
        order.verify(mapper).selectForUpdate(1L);
        order.verify(mapper).updateById(room);
    }

    @Test
    void bloomFailureCannotReportSuccessfulCreateAsFailure() {
        MeetingRoomMapper mapper = mock(MeetingRoomMapper.class);
        RoomBloomFilterService bloom = mock(RoomBloomFilterService.class);
        doAnswer(invocation -> { ((MeetingRoom) invocation.getArgument(0)).setId(9L); return 1; }).when(mapper).insert(any(MeetingRoom.class));
        doThrow(new IllegalStateException("redis unavailable")).when(bloom).put(9L);
        assertEquals(9L, service(mapper, bloom).createRoom(roomDto()));
    }

    @Test void equipmentLimitCountsUnicodeCharactersInsteadOfUtf16UnitsOrBytes() {
        MeetingRoomMapper mapper = mock(MeetingRoomMapper.class);
        doAnswer(invocation -> { ((MeetingRoom) invocation.getArgument(0)).setId(9L); return 1; })
                .when(mapper).insert(any(MeetingRoom.class));
        var valid = roomDto();
        valid.setEquipment(List.of("😀".repeat(496)));
        var service = service(mapper, mock(RoomBloomFilterService.class));
        assertEquals(9L, service.createRoom(valid)); // MySQL utf8mb4: 496 + 4 = 500 characters.
        var invalid = roomDto();
        invalid.setEquipment(List.of("😀".repeat(497)));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(BusinessException.class, () -> service.createRoom(invalid)).getStatus());
        verify(mapper, times(1)).insert(any(MeetingRoom.class));
    }

    private RoomSaveDTO roomDto() {
        RoomSaveDTO dto = new RoomSaveDTO();
        dto.setRoomName("会议室");
        dto.setCapacity(10);
        dto.setEquipment(List.of("白板"));
        dto.setStatus(RoomStatus.AVAILABLE.getCode());
        return dto;
    }

    private MeetingRoomServiceImpl service(MeetingRoomMapper mapper, RoomBloomFilterService bloom) {
        RoomCache cache = mock(RoomCache.class);
        when(cache.get(anyLong(), any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        return new MeetingRoomServiceImpl(mapper, bloom, new ObjectMapper(), cache, mock(BookingOrderMapper.class), Clock.systemUTC());
    }
}
