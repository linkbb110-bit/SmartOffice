package com.geek.smartoffice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geek.smartoffice.common.enums.UserRole;
import com.geek.smartoffice.common.security.JsonAccessDeniedHandler;
import com.geek.smartoffice.common.security.JsonAuthenticationEntryPoint;
import com.geek.smartoffice.common.security.JwtAuthenticationFilter;
import com.geek.smartoffice.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocsSecurityTest {
    private static final String[] DOC_PATHS = {
            "/doc.html", "/webjars/knife4j/knife4j.js", "/v3/api-docs", "/v3/api-docs/",
            "/v3/api-docs/swagger-config", "/v3/api-docs/default",
            "/swagger-ui.html", "/swagger-ui/", "/swagger-ui/index.html"
    };

    /*
     * ContextRunner 按每次 run 给定的配置创建独立 Web 上下文，只装配这里导入的安全链和测试端点。
     * run 的 lambda 在上下文就绪后取 Bean 并发起 MockMvc 请求；apply(springSecurity()) 才将实际安全过滤器接入。
     * 因而可隔离验证文档开关的默认值、显式值及路径边界，不依赖完整服务或真实文档资源。
     */
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "smartoffice.jwt.secret=test-only-smartoffice-secret-key-with-at-least-32-bytes",
                    "smartoffice.jwt.expiration=2h");

    @Test
    void documentationGetIsPublicWhenExplicitlyEnabled() {
        contextRunner.withPropertyValues("smartoffice.docs.public=true").run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            for (String path : DOC_PATHS) {
                mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string("reachable"));
            }
        });
    }

    @Test
    void documentationRequiresAuthenticationByDefault() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            for (String path : DOC_PATHS) {
                mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
            }
        });
    }

    @Test
    void documentationRequiresAuthenticationWhenExplicitlyDisabled() {
        contextRunner.withPropertyValues("smartoffice.docs.public=false").run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            for (String path : DOC_PATHS) {
                mvc.perform(get(path)).andExpect(status().isUnauthorized());
            }
        });
    }

    @Test
    void publicDocumentationDoesNotPermitAnonymousPostRequests() {
        contextRunner.withPropertyValues("smartoffice.docs.public=true").run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            for (String path : DOC_PATHS) {
                mvc.perform(post(path)).andExpect(status().isUnauthorized());
            }
        });
    }

    @Test
    void publicDocumentationKeepsBusinessAndSimilarPathsProtected() {
        contextRunner.withPropertyValues("smartoffice.docs.public=true").run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            for (String path : new String[]{"/rooms/list", "/orders/my", "/doc.html/rooms", "/v3/api-docs-private"}) {
                mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
            }
            String token = context.getBean(JwtService.class).createToken(7L, "alice", UserRole.USER);
            mvc.perform(get("/rooms/list").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(content().string("reachable"));
        });
    }

    // 本测试不靠配置类内部调用 @Bean 方法复用实例，因此关闭方法代理，所需依赖仍由容器注入。
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, JwtService.class, JwtAuthenticationFilter.class,
            JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class, TestEndpoint.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @RestController
    static class TestEndpoint {
        // 用统一端点消除实际文档资源是否存在的影响，专门检验过滤器链的放行规则。
        @RequestMapping("/**")
        String reachable() {
            return "reachable";
        }
    }
}
