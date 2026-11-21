package com.geek.smartoffice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DemoEnvironmentInfoTest {
    private ApplicationContextRunner context(String profile, boolean enabled, String database) {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues("smartoffice.demo.enabled=" + enabled,
                        "smartoffice.demo.database=untrusted_config_value")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(source))
                .withUserConfiguration(DemoEnvironmentInfo.class);
    }

    @Test
    void reportsTheDatabaseReadFromTheConnectionInsteadOfAConfiguredName() {
        context("local", true, "so_ui001_demo_20261121").run(context -> {
            Info.Builder builder = new Info.Builder();
            context.getBean(DemoEnvironmentInfo.class).contribute(builder);
            Map<?, ?> identity = (Map<?, ?>) builder.build().getDetails().get("smartofficeDemo");
            assertEquals("so_ui001_demo_20261121", identity.get("database"));
            assertEquals("demo", identity.get("mode"));
        });
    }

    @Test
    void aDifferentActualDatabaseIsNotReplacedWithTheExpectedDemoName() {
        context("local", true, "ordinary_database").run(context -> {
            Info.Builder builder = new Info.Builder();
            context.getBean(DemoEnvironmentInfo.class).contribute(builder);
            Map<?, ?> identity = (Map<?, ?>) builder.build().getDetails().get("smartofficeDemo");
            assertEquals("ordinary_database", identity.get("database"));
        });
    }

    @Test
    void isAbsentWithoutTheExplicitLocalDemoSwitch() {
        context("local", false, "disabled_demo").run(context ->
                assertEquals(0, context.getBeansOfType(DemoEnvironmentInfo.class).size()));
        context("cloud", true, "cloud_demo").run(context ->
                assertEquals(0, context.getBeansOfType(DemoEnvironmentInfo.class).size()));
    }
}
