package com.geek.smartoffice.user;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/*
 * 启动类位于 user 子包，因此显式扫描 com.geek.smartoffice，才能发现同级 controller、service 及共享配置。
 * MapperScan 则单独识别 mapper 接口并注册数据库访问代理；二者负责的对象类型不同。
 */
@SpringBootApplication(scanBasePackages="com.geek.smartoffice")
@MapperScan("com.geek.smartoffice.mapper")
public class UserApplication {
    public static void main(String[] args) { SpringApplication.run(UserApplication.class,args); }
}
