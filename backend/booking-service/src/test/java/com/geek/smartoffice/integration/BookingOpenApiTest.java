package com.geek.smartoffice.integration;

import com.geek.smartoffice.SmartOfficeApplication;
import com.geek.smartoffice.cache.RoomCacheStore;
import com.geek.smartoffice.remote.RemoteUserVerifier;
import com.geek.smartoffice.service.RoomBloomFilterService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 检查真实生成的 OpenAPI 元数据和文档访问边界；本测试不包含浏览器实际发送接口。
@SpringBootTest(classes = SmartOfficeApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:booking-openapi;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db-test.sql,classpath:message-test.sql",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.sentinel.enabled=false",
        "smartoffice.mq.enabled=false",
        "smartoffice.docs.public=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingOpenApiTest {
    @Autowired MockMvc mvc;
    @MockBean RedissonClient redisson;
    @MockBean RoomCacheStore cache;
    @MockBean RoomBloomFilterService bloom;
    @MockBean RemoteUserVerifier remote;

    @Test
    void realOpenApiGenerationIncludesBookingRoutes() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.openapi").value(containsString("3.")))
                .andExpect(jsonPath("$.paths['/rooms/list'].get").exists())
                .andExpect(jsonPath("$.paths['/orders/book'].post").exists());
    }

    // bearerAuth 是文档中引用的方案 ID，实际 HTTP 请求头名称则必须是 Authorization。
    // 分别断言这两处元数据，避免 Knife4j 把方案 ID 当成请求头名称，导致授权后仍返回 401。
    @Test
    void bearerSecuritySchemeExposesTheAuthorizationHeaderToKnife4j() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.name").value("Authorization"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.in").value("header"))
                .andExpect(jsonPath("$.paths['/rooms/list'].get.security[0].bearerAuth").isArray());
    }

    @Test
    void swaggerConfigurationAndKnife4jUiRemainAvailable() throws Exception {
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
        mvc.perform(get("/doc.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<html")));
    }

    @Test
    void publicDocumentationKeepsBusinessAndNonGetRequestsProtected() throws Exception {
        for (String path : new String[]{"/rooms/list", "/orders/my"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
        mvc.perform(post("/v3/api-docs")).andExpect(status().isUnauthorized());
    }
}
