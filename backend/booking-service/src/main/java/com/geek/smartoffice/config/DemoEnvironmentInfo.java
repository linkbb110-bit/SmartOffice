package com.geek.smartoffice.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 本地演示环境的只读身份信息，仅在显式开启演示开关时注册。
 * 查询实际 JDBC 会话的库名，避免初始化脚本仅凭地址或配置值误判目标环境。
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "smartoffice.demo.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DemoEnvironmentInfo implements InfoContributor {
    private final JdbcTemplate jdbc;

    /**
     * 向已受现有认证保护的 Actuator info 提供当前库名，不修改任何业务数据。
     *
     * @param builder Actuator 信息构建器
     */
    @Override
    public void contribute(Info.Builder builder) {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        builder.withDetail("smartofficeDemo", Map.of("mode", "demo", "database", database == null ? "" : database));
    }
}
