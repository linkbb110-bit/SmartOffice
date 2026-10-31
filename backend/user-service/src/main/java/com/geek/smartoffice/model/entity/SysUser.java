package com.geek.smartoffice.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String role;
    /*
     * MyBatis-Plus 的逻辑删除标记使其生成的常规查询排除已删除记录，删除操作通常更新标记而非移除行。
     * 数据仍在表内，用户名唯一键也仍然生效；该标记不会替手写 SQL 或 JdbcTemplate 自动加过滤条件。
     */
    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
