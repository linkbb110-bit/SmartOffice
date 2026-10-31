package com.geek.smartoffice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    /*
     * @Bean 的返回值交给 Spring 容器管理，业务构造器注入 Clock 后可在获取时间时使用它。
     * 默认实现使用系统时区；测试可注入固定 Clock 来验证临界时间，无需依赖测试执行时的真实时间。
     * 这只影响显式使用该 Clock 的代码，不会替换 Instant.now() 等直接读取系统时间的调用。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
