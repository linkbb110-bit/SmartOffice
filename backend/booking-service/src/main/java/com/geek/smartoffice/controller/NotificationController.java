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

@RestController
@RequiredArgsConstructor
public class NotificationController {
    private final JdbcTemplate jdbc;
    @GetMapping("/notifications/my")
    public Result<List<Map<String,Object>>> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return Result.success(jdbc.queryForList("SELECT id,booking_id,content,created_at FROM booking_notification WHERE user_id=? ORDER BY created_at DESC LIMIT 100",user.userId()));
    }
    @GetMapping("/admin/messages/outbox")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<Map<String,Object>>> outbox() {
        return Result.success(jdbc.queryForList("SELECT event_id,aggregate_id,event_type,status,attempts,available_at,lease_until,last_error,broker_message_id FROM booking_outbox ORDER BY created_at DESC LIMIT 100"));
    }
}
