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
        // () -> ... 是无参数 Lambda，作为 Supplier 延迟传入，构造它时不会立即查询数据库。
        // 缓存未命中或 Redis 不可用时，RoomCache 才调用它；缓存空值与逻辑删除最终都转换为不存在。
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
        // 先登记提交后动作，事务回滚时不会失效缓存或发布新 ID，避免 Redis 暴露未提交的房间。
        // 新建需要补入 Bloom；更新和删除只失效详情，Bloom 不是可以逐个删除 ID 的精确集合。
        afterCommit(room.getId(), true);//避免Redis有记录却无mysql数据
        return room.getId();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateRoom(Long roomId, RoomSaveDTO dto) {
        MeetingRoom room = lockExistingRoom(roomId);
        RoomStatus.fromCode(dto.getStatus());
        copy(dto, room);
        // 这里持有与预约相同的房间行锁，状态变更与预约对“可用状态”的检查因而有明确先后顺序。
        // 创建时间不参与本次更新，避免回写先前读取的值；更新时间使用统一 Clock 并截到整秒。
        room.setCreatedAt(null);
        room.setUpdatedAt(LocalDateTime.now(clock).withNano(0));
        meetingRoomMapper.updateById(room);
        afterCommit(roomId, false);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteRoom(Long roomId) {
        // 先锁房间再统计未结束预约，与创建预约遵守同一房间行锁顺序。
        // 若预约先提交，这里会读到并拒绝删除；若删除先提交，后来的预约会在拿锁后看到删除标记。
        lockExistingRoom(roomId);
        if (bookingOrderMapper.countActive(roomId, LocalDateTime.now(clock)) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "会议室存在尚未结束的预约，不能删除");
        }
        // MeetingRoom 标有 @TableLogic，MyBatis-Plus 的 deleteById 更新删除标记，保留历史记录。
        // 自定义加锁查询仍需显式检查标记；提交后再清详情缓存，避免先删缓存又读回未提交旧值。
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

    // 设备以 JSON 数组写入一个数据库字段：空列表统一表示“无设备”，元素先 trim 再去掉空白项。
    // 方法引用 String::trim 和 StringUtils::hasText 分别供 map 转换与 filter 筛选，在 toList 时遍历执行。
    private String writeEquipment(List<String> equipment) {
        List<String> values = equipment == null
                ? Collections.emptyList()
                : equipment.stream().map(String::trim).filter(StringUtils::hasText).toList();
        try {
            String encoded = objectMapper.writeValueAsString(values);
            // 限制的是编码后 JSON 的 Unicode 码点数，数组括号、引号与转义符也计入。
            // codePointCount 避免把一个补充字符的 UTF-16 代理对算作两个字符，这里不按 UTF-8 字节计数。
            if (encoded.codePointCount(0, encoded.length()) > 500) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "会议室设备列表过长，编码后不能超过500个字符");
            }
            return encoded;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "会议室设备序列化失败");
        }
    }

    // 优先读取新格式 JSON 数组；空字段直接返回空列表，旧格式再按英文/中文逗号和顿号拆分。
    // 容错针对 JSON 解析失败，避免历史设备记录因格式差异使整个房间列表无法读取。
    private List<String> readEquipment(String equipment) {
        if (!StringUtils.hasText(equipment)) {
            return Collections.emptyList();
        }
        String text = equipment.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                // Java 泛型运行时会擦除元素类型，匿名 TypeReference 子类保留 List<String> 的完整类型信息。
                // Jackson 因而按字符串列表反序列化，而不是只知道一个原始 List；随后过滤无文本的元素。
                return objectMapper.readValue(text, new TypeReference<List<String>>() {
                }).stream().filter(StringUtils::hasText).toList();
            } catch (JsonProcessingException ignored) {
                // 不直接返回空列表，继续尝试旧版分隔格式，尽量保留历史数据中仍可显示的设备文本。
            }
        }
        return Arrays.stream(text.split("[,，、]"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 将房间详情失效和新建 ID 的 Bloom 补录推迟到数据库事务成功提交之后。
     * 有事务同步上下文时注册回调，外层事务存在时也等最终提交；回滚不会触发 afterCommit。
     * 无同步上下文则立即执行，便于明确处理非事务调用；Redis 操作不属于数据库事务，失败不能撤销提交。
     * 详情失效失败依靠缓存 TTL 收敛，Bloom 补录失败仅记录日志，因此二者都可能暂时落后于数据库。
     */
    private void afterCommit(Long roomId, boolean publishBloom) {
        // Runnable 是无参数、无返回值的任务；此 Lambda 只定义动作，直到 run() 被调用才访问 Redis。
        // 同一任务用于“提交后回调”和“无同步上下文立即执行”，避免两条路径各维护一套发布逻辑。
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
        // 匿名类只重写 afterCommit 钩子，Spring 在成功提交数据库后调用它，登记回调本身不会提前执行。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publication.run();
            }
        });
    }
}
