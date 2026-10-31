package com.geek.smartoffice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

// 启动预约服务，并注册本服务的 MyBatis Mapper 与远程用户校验所用的 Feign 客户端。
@SpringBootApplication
@MapperScan("com.geek.smartoffice.mapper")
@EnableFeignClients(basePackages="com.geek.smartoffice.remote")
public class SmartOfficeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartOfficeApplication.class, args);
    }
}
