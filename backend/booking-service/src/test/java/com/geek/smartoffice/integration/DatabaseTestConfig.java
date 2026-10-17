package com.geek.smartoffice.integration;

import com.geek.smartoffice.service.impl.BookingWriteServiceImpl;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import com.geek.smartoffice.config.TimeConfig;
import com.geek.smartoffice.messaging.*;

@SpringBootConfiguration
@EnableAutoConfiguration
@MapperScan("com.geek.smartoffice.mapper")
@Import({BookingWriteServiceImpl.class, TimeConfig.class,BookingOutboxService.class,
        BookingEventConsumer.class,OutboxRelay.class,OutboxStore.class})
public class DatabaseTestConfig {
}
