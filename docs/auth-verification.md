# 用户认证实现与验证

> **目录迁移说明（2026-10-17）**：本报告的历史命令、行号及旧路径按原验收版本保留。当前前端在 frontend，后端聚合工程在 backend，原 backend 模块现为 backend/booking-service；Compose/私有部署 env 在 deploy。新启动命令见 [runbook](runbook.md)，完整路径映射与当前验证见 [迁移记录](directory-layout-verification.md)。

用户数据仅由 `user-service` 的 `SysUserMapper` 访问。注册、登录、当前用户查询不调用 booking，不在 booking 数据库写 `sys_user`。公共 JWT、认证过滤器与权限配置位于 `shared`。

## API 行为

| 接口 | 请求与结果 |
| --- | --- |
| `POST /users/register` | JSON `{username,password}`，用户名为 3–64 位 ASCII 字母、数字、下划线；密码为 8–72 位且 UTF-8 不超过 72 字节。成功返回 `Result<Long>` 用户 ID。 |
| `POST /users/login` | 保留原 JSON 请求和 `Result<String>` token 响应；使用 BCrypt 验证。旧本地演示密码仍可登录，注册的新密码适用新限制。 |
| `GET /users/me` | 需 Bearer JWT；根据 JWT 中的 ID 查询本库有效用户，返回 `Result<AuthenticatedUser>`，字段为 `userId`、`username`、`role`，不返回密码。 |

注册始终保存 `USER`，请求中的 `role` 不影响角色。数据库用户名唯一键承担重复与并发冲突裁决，`DuplicateKeyException` 由公共异常处理转换为 HTTP 409；逻辑删除的同名记录也不能绕过唯一键。注册密码使用 `BCryptPasswordEncoder` 编码，未保存原文。

`/users/me` 使用数据库身份，JWT 中旧的 username/role 不作为响应身份。MyBatis-Plus `@TableLogic` 过滤逻辑删除用户；用户不存在、已删除或数据库身份无效时返回 401。

权限规则：仅 `POST /users/login`、`POST /users/register` 允许匿名认证请求；`/actuator/health` 及其子路径允许匿名健康检查。CORS OPTIONS 预检保留。其他请求均需要认证，包括 GET login/register、`/actuator/info` 和 API 文档端点。原方法权限注解保持启用。

## JWT 校验

`JwtProperties` 在配置绑定时拒绝空 secret、缺失/零/负 expiration；`JwtService` 仍要求 HMAC secret 至少 32 UTF-8 字节。解析先验证签名及过期时间，再要求正整数 `uid`、非空 subject、合法 `USER/ADMIN` role 和 expiration。缺声明与无效 role 统一为 `JwtException`，由现有过滤器清除认证上下文后走 401，避免 role 缺失导致空指针或 500。

使用的密码编码与 JWT 解析方式参考 [Spring Security Password Storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html) 和 [JJWT 官方文档](https://github.com/jwtk/jjwt#reading-a-jws)。应用对身份声明和密码字节边界的约束由本项目测试验证。

## 测试环境

只使用 `test` profile 的 H2 `jdbc:h2:mem:smartoffice_user_test;MODE=MySQL` 和 MockMvc，不连接真实 MySQL、Nacos、Redis 或 RocketMQ。discovery 被关闭。测试 JWT secret 明确为 `test-only`，无生产密钥或真实密码写入配置。用户测试设置 `mock-maker-subclass`，与共享模块一致，以便本地 Mockito 验证不依赖动态 agent 自附加。

PowerShell 验证命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
& 'D:\apache-maven-3.9.4-bin\apache-maven-3.9.4\bin\mvn.cmd' -B -ntp -pl user-service -am test
```

当前机器默认 Oracle javapath shim 在沙箱中返回 `Access is denied`；指定 Java 17 后，沙箱中的测试 fork 仍阻塞。本次通过标准 `require_escalated` 执行已授权的本地 H2/MockMvc 测试，没有修改系统配置或全局 Maven settings。

## RED → GREEN 证据（2026-10-14）

最初 `user-red.log` 在共享模块编译失败，缺 `spring-tx` 的 `DuplicateKeyException`，未执行用户 API，不能视为功能 RED。主代理修复模块依赖后，实际测试结果为：

| 日志/阶段 | 真实结果 |
| --- | --- |
| `auth-red.log` | shared 21 tests，13 failures、0 errors；证明缺 uid、非正 uid、空 subject、缺 role、无效 role、缺 expiration 和无效 JWT 配置原先未正确拒绝。user-service 因共享测试失败被跳过。 |
| `user-api-red.log` | 单选 `UserApiTest`：13 tests、12 failures、1 error。注册因未放行返回 401，缺 role token 触发空指针；BUILD FAILURE。单选只用于取得 API 的独立 RED。 |
| `auth-green.log` | 完整 `-pl user-service -am test`：2026-10-14 11:57:41 `BUILD SUCCESS`，退出码 0；shared 21 tests、user-service 15 tests，全为 0 failures、0 errors、0 skipped。 |

GREEN 包含保留的 `JwtServiceTest` 1 项、JWT 新验证 16 项、原公共结果/日志测试 4 项、原用户登录服务测试 2 项、用户 API 测试 13 项。

覆盖的 API 行为包括：注册保存可匹配 BCrypt 且不能提权、重复与已逻辑删除用户名返回 409、用户名非法返回 400、24 个中文字符（72 UTF-8 字节）可注册而 25 个中文字符被拒绝、73 位 ASCII 密码被拒绝、登录 token 兼容、me 读数据库身份且不暴露密码、物理或逻辑删除用户 401、匿名/非法 JWT 401、缺声明 JWT 不触发 500、健康根路径与 liveness 匿名成功、info 和非 POST 认证路由仍受保护。

JWT 测试包括签名不匹配、过期、缺少 uid/role/expiration、非正或小数 uid、空 subject、无效角色，以及空密钥/非正有效期。以上为 H2/MockMvc 与真实 JWT 编解码证据，不代表 Docker 或生产环境运行验证。整仓库 `clean verify` 由主代理在模块合并后执行并另行记录。
