package com.geek.smartoffice.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

/**
 * 为生成的 OpenAPI 文档提供标题、版本和名为 bearerAuth 的 HTTP Bearer 认证方案。
 * Controller 的 SecurityRequirement 引用此名称，使文档工具知道受保护接口应携带令牌。
 * 这些声明只描述接口契约；请求中的 JWT 校验和角色授权仍在 Spring Security 中执行。
 */
@Configuration
@OpenAPIDefinition(info = @Info(
        title = "SmartOffice API",
        version = "1.0.0",
        description = "云智办会议室管理系统接口"
))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        paramName = "Authorization",
        in = SecuritySchemeIn.HEADER,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {
}
