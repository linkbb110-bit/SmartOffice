package com.geek.smartoffice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@MapperScan("com.geek.smartoffice.mapper")
@EnableFeignClients(basePackages="com.geek.smartoffice.remote")
public class SmartOfficeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartOfficeApplication.class, args);
    }
}
