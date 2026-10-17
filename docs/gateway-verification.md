# Gateway 验证记录（2026-10-14）

> **目录迁移说明（2026-10-17）**：本报告的历史命令、行号及旧路径按原验收版本保留。当前前端在 frontend，后端聚合工程在 backend，原 backend 模块现为 backend/booking-service；Compose/私有部署 env 在 deploy。新启动命令见 [runbook](runbook.md)，完整路径映射与当前验证见 [迁移记录](directory-layout-verification.md)。

网关使用独立 WebFlux 模块，不依赖 shared 的 MVC 或 Spring Security 自动配置。JWT 验证与身份判定由后端执行；网关只清理客户端伪造的内部身份头并保留 Authorization。

## 预定路由与配置

Java RouteLocator 仅允许 `/users/**`（用户服务）及 `/rooms/**`、`/orders/**`、`/notifications/**`、`/admin/messages/**`（预约服务）。同样支持 `/api` 前缀并去掉一层路径；未允许路径不转发，避免将内部 `/actuator/env` 等暴露为泛化代理。

默认端口 8080、应用名 smartoffice-gateway、默认 profile local，Nacos 默认关闭。本地目标为 USER_SERVICE_URI（默认 localhost:8082）和 BOOKING_SERVICE_URI（默认 localhost:8081）。cloud profile 默认使用 lb://smartoffice-user-service 与 lb://smartoffice-booking-service，通过 NACOS_SERVER_ADDR、NACOS_USERNAME、NACOS_PASSWORD 配置注册发现；目标仍可通过环境变量覆盖。

入站 GlobalFilter 大小写无关地移除 X-User-Id、X-User-Role、X-Username 以及全部 X-Internal-* 头；保留 JWT 和普通追踪头。网关不创建任何认证身份，不替代后端授权，也不重复后端 Sentinel 预约限流。Actuator 仅暴露本地 health，供容器健康检查使用。

## 执行记录

RED：`mvn -pl gateway -am test` exit 1，首次下载 Gateway/Nacos 依赖后，因缺少 GatewayApplication 的 `@SpringBootConfiguration` 明确失败。补齐实现后的 GREEN：`mvn -B -ntp -pl gateway -am test` exit 0，**6 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS，20.236 s**（11:52:38）。此轮运行环境 Java 21.0.6，编译 release 17，Boot 3.5.13、Cloud Gateway 4.3.0。

按主代理统一要求完成最终 Java 17 打包验证：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
mvn -B -ntp -pl gateway -am package
```

使用项目 HTTPS Maven 配置、Surefire 3.5.4，实际测试进程日志确认 **Java 17.0.17**。最终结果 **exit 0，6 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS，35.339 s**（11:55:11），没有跳过测试。生成可执行 Spring Boot 包 `gateway/target/smartoffice-gateway-1.0.0.jar`。本任务未修改任何 POM。

GatewayRoutingTest 使用随机 loopback 端口：真实 Reactor Netty 网关转发到分别代表用户/预约服务的两个 JDK HttpServer，断言目标服务选择正确，不访问 Nacos。6 个测试覆盖 /api 前缀移除、原路径兼容、所有允许路由、身份头清理与 Authorization 保留、未允许管理路径没有到达上游，以及本地 health 返回 UP 且无需认证。

本次日志有本机 hostname 自动探测信息和 LoadBalancer 默认缓存建议；均未影响本地路由测试。没有 Spring Security 默认 401，也没有关闭 Spring Cloud 版本兼容校验。

## 尚未验证

初期Gateway测试中的两个目标 URI 均指向受控本地 HTTP 服务，仅验证local profile，不能据此声称注册发现通过。后续三份实际服务JAR的HTTP/Feign和Edge浏览器联调已补验，服务端JWT与业务链路可运行；Nacos注册、cloud profile的lb://路由和Docker启动仍未执行，见upgrade-progress.md。
