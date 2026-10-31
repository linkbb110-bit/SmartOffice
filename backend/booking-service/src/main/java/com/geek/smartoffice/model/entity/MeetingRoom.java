package com.geek.smartoffice.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("meeting_room")
public class MeetingRoom {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String roomName;
    private Integer capacity;
    private String equipment;
    private Integer status;
    // MyBatis-Plus 的逻辑删除标记：通用删除方法更新标记，通用查询会应用未删除条件。
    // 自定义 @Select SQL 仍需自行处理删除语义，房间行锁查询由服务层检查此字段。
    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
