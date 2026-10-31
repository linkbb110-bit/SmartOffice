package com.geek.smartoffice.integration;

import com.geek.smartoffice.SmartOfficeApplication;
import com.geek.smartoffice.cache.RoomCacheStore;
import com.geek.smartoffice.common.enums.UserRole;
import com.geek.smartoffice.common.security.JwtService;
import com.geek.smartoffice.remote.RemoteUserVerifier;
import com.geek.smartoffice.service.RoomBloomFilterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

// MockMvc 在进程内经过真实 MVC 与 Security 过滤链，不需要启动外部 HTTP 客户端。
// @MockBean 替换 Redis 和远程校验依赖；JWT 仍由真实组件签发，便于分别验证 401 认证失败与 403 权限不足。
@SpringBootTest(classes=SmartOfficeApplication.class,properties={
 "spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:db-test.sql,classpath:message-test.sql",
 "spring.sql.init.data-locations=classpath:api-test-data.sql","spring.cloud.sentinel.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingApiSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @MockBean RedissonClient redisson;
    @MockBean RoomCacheStore cache;
    @MockBean RoomBloomFilterService bloom;
    @MockBean RemoteUserVerifier remote;
    String alice, bob, admin;
    @BeforeEach void reset() {
        for(String table:new String[]{"message_consumption","booking_notification","business_event_audit","booking_outbox","booking_order","meeting_room"}) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO meeting_room(id,room_name,capacity,equipment,status) VALUES(1,'test',10,'[]',1)");
        alice="Bearer "+jwt.createToken(1L,"alice",UserRole.USER);
        bob="Bearer "+jwt.createToken(2L,"bob",UserRole.USER);
        admin="Bearer "+jwt.createToken(3L,"admin",UserRole.ADMIN);
    }
    String payload() {return "{\"roomId\":1,\"topic\":\"=SUM(1,2)\",\"startTime\":\"2099-01-01T10:00:00\",\"endTime\":\"2099-01-01T11:00:00\",\"requestId\":\"api-retry\"}";}
    long create() throws Exception {
        String body=mvc.perform(post("/orders/book").header("Authorization",alice).contentType("application/json").content(payload()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();
    }
    @Test void bearerRequiredAndUserCannotManageRoomsOrExport() throws Exception {
        mvc.perform(get("/rooms/list")).andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/export").header("Authorization",alice)).andExpect(status().isForbidden());
        mvc.perform(post("/rooms").header("Authorization",alice).contentType("application/json")
                .content("{\"roomName\":\"test\",\"capacity\":10,\"equipment\":[],\"status\":1}"))
                .andExpect(status().isForbidden());
    }
    @Test void exportPermissionAndMediaNegotiationPreserveHttpStatus() throws Exception {
        for (String accept : new String[]{"application/json", "text/csv"}) {
            mvc.perform(get("/orders/export").header("Authorization",alice).accept(accept))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        }
        mvc.perform(get("/orders/export").header("Authorization",admin).accept("application/json"))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value(406));
        mvc.perform(get("/orders/export").header("Authorization",admin).accept("text/csv"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"));
    }

    @Test void oversizedEquipmentReturnsParameterErrorForCreateAndUpdate() throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "roomName", "oversized", "capacity", 10, "equipment", java.util.List.of("x".repeat(497)), "status", 1));
        for (var request : java.util.List.of(post("/rooms"), put("/rooms/1"))) {
            mvc.perform(request.header("Authorization", admin).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("设备")));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM meeting_room", Integer.class));
        assertEquals("test", jdbc.queryForObject("SELECT room_name FROM meeting_room WHERE id=1", String.class));
    }
    @Test void bookingReplayCancelAndExportKeepOriginalContract() throws Exception {
        long id=create();
        mvc.perform(post("/orders/book").header("Authorization",alice).contentType("application/json").content(payload()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM booking_outbox",Integer.class));
        mvc.perform(post("/orders/"+id+"/cancel").header("Authorization",bob)).andExpect(status().isForbidden());
        mvc.perform(post("/orders/"+id+"/cancel").header("Authorization",alice)).andExpect(status().isOk());
        mvc.perform(post("/orders/"+id+"/cancel").header("Authorization",alice)).andExpect(status().isOk());
        String csv=mvc.perform(get("/orders/export").header("Authorization",admin)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition","attachment; filename=bookings.csv"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"'=SUM(1,2)\""));
    }
    @Test void invalidBodyIs400AndUserCannotInspectMessageStatus() throws Exception {
        mvc.perform(post("/orders/book").header("Authorization",alice).contentType("application/json")
                .content("{\"roomId\":0,\"topic\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/messages/outbox").header("Authorization",alice)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/messages/outbox").header("Authorization",admin)).andExpect(status().isOk());
    }

    @Test void invalidPathUnsupportedMethodAndUnknownRoutePreserveHttpStatus() throws Exception {
        mvc.perform(post("/orders/not-a-number/cancel").header("Authorization",alice))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(put("/orders/my").header("Authorization",alice))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value(405));
        mvc.perform(get("/not-configured").header("Authorization",alice))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }
}
