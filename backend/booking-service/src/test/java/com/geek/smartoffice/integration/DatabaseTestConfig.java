package com.geek.smartoffice.integration;

import com.geek.smartoffice.service.impl.BookingWriteServiceImpl;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import com.geek.smartoffice.config.TimeConfig;
import com.geek.smartoffice.messaging.*;

// 以此类作为测试启动点，自动配置数据源等基础设施；MapperScan 为 Mapper 接口创建代理。
// @Import 只装配数据库写入与消息组件，避免扫描 Controller 和远程调用等整个业务应用。
// 各测试再用 test Profile 和 @Sql 准备隔离数据，验证真实 SQL 与事务行为。
@SpringBootConfiguration
@EnableAutoConfiguration
@MapperScan("com.geek.smartoffice.mapper")
@Import({BookingWriteServiceImpl.class, TimeConfig.class,BookingOutboxService.class,
        BookingEventConsumer.class,OutboxRelay.class,OutboxStore.class})
public class DatabaseTestConfig {
}
