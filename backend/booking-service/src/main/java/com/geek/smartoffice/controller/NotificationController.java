package com.geek.smartoffice.controller;

import com.geek.smartoffice.common.result.Result;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * 提供通知结果和 Outbox 投递状态的只读查询，数据分别来自消息消费和投递阶段。
 * JdbcTemplate 将每条查询结果映射为字段名到值的 Map，再放入统一 Result 响应。
 */
@RestController
@RequiredArgsConstructor
public class NotificationController {
    private final JdbcTemplate jdbc;
    /**
     * 用户范围来自 JWT 过滤器建立的认证主体，不接受客户端另传 userId。
     * 占位符将该 ID 作为查询参数绑定，只返回本人按创建时间倒序的最近 100 条通知。
     */
    @GetMapping("/notifications/my")
    public Result<List<Map<String,Object>>> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return Result.success(jdbc.queryForList("SELECT id,booking_id,content,created_at FROM booking_notification WHERE user_id=? ORDER BY created_at DESC LIMIT 100",user.userId()));
    }
    /**
     * 方法安全代理先检查 ADMIN 权限，再读取最近 100 条事件的状态、重试和租约信息。
     * 这是管理端观察投递进度的查询；发送、超时恢复与重试继续由 Outbox 调度执行。
     */
    @GetMapping("/admin/messages/outbox")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<Map<String,Object>>> outbox() {
        return Result.success(jdbc.queryForList("SELECT event_id,aggregate_id,event_type,status,attempts,available_at,lease_until,last_error,broker_message_id FROM booking_outbox ORDER BY created_at DESC LIMIT 100"));
    }
}
