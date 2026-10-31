package com.geek.smartoffice.remote;

import com.geek.smartoffice.common.result.Result;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * OpenFeign 根据接口上的映射声明生成代理，调用 me 时由代理组装并发送 HTTP GET，
 * 将响应 JSON 解码为 Result 和 AuthenticatedUser，调用方无需手写 HTTP 客户端。
 * 当前显式 url 取自配置，缺省直连 localhost:8082；name 用于标识客户端，
 * 此配置下请求目标由 url 决定，不按该服务名执行注册中心发现。
 */
@FeignClient(name="smartoffice-user-service",
        url="${smartoffice.user-service.url:http://localhost:8082}")
public interface UserClient {
    // 此映射与客户端基础 URL 拼成 /users/me 请求，参数显式写入 Authorization 头。
    // 用户服务先验证同一令牌，再按其用户 ID 回查数据库，返回供本地复核的最新身份。
    @GetMapping("/users/me")
    Result<AuthenticatedUser> me(@RequestHeader("Authorization") String bearer);
}
