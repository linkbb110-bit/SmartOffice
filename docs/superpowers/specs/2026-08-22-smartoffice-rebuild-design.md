# SmartOffice 重建设计说明

## 1. 目标

从现存的 Vue 3 前端与后端 patch 记录重建一套可运行、可演示、可用于 Java 后端实习面试深挖的 SmartOffice（云智办）会议室管理系统。

重建后的系统必须满足三个标准：

1. **真实可运行**：本地启动后能够完成登录、查看会议室、创建预约。
2. **简历可追溯**：简历中的 JWT、MyBatis-Plus、Redis/Redisson、Bloom Filter、AOP、Docker 均有对应真实代码。
3. **可解释**：关键技术均由业务问题驱动，不堆叠无关组件；并发预约链路可以清楚解释锁粒度、二次校验和时间冲突条件。

## 2. 已恢复事实与兼容约束

现存前端已经定义并使用以下接口：

- `POST /users/login`
- `GET /rooms/list`
- `POST /orders/book`

现存预约请求字段：

- `roomId: number`
- `topic: string`
- `startTime: string`，格式 `yyyy-MM-dd'T'HH:mm`
- `endTime: string`，格式 `yyyy-MM-dd'T'HH:mm`

前端统一响应契约：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

后端包名保持为 `com.geek.smartoffice`，服务端口保持 `8081`，以最大程度兼容恢复材料。

## 3. 范围

### 3.1 本次必须实现

- 用户登录与 JWT 无状态认证
- BCrypt 密码校验
- 会议室列表
- 管理员会议室新增、修改、删除
- 会议室预约
- 时间段冲突校验
- Redisson 分布式锁保护同一会议室并发预约
- Redisson Bloom Filter 过滤明显不存在的会议室 ID
- Spring AOP + 自定义 `@OperationLog` 记录操作、方法、耗时、异常
- MyBatis-Plus + MySQL 持久化
- Redis/Redisson 配置
- Knife4j/OpenAPI 接口文档
- 全局异常处理和统一 `Result<T>`
- Dockerfile、Docker Compose 本地依赖、Host 网络生产部署示例
- README 启动、验证与面试讲解说明
- 后端核心单元/集成测试与前端构建验证

### 3.2 明确不做

本轮不恢复以下 patch 中出现但不属于当前简历主线的功能：

- 邮件通知
- 自定义限流
- 自定义幂等注解
- WebSocket
- 消息队列
- 复杂订单超时/补偿链路

理由：这些功能会扩大面试攻击面，并不能提高当前会议室预约主链路的可信度。

## 4. 技术栈

- Java 17
- Spring Boot 3.x
- Spring Security 6
- MyBatis-Plus 3.5.x
- MySQL 8.x
- Redis 7.x
- Redisson 3.x
- JJWT 0.12.x
- Knife4j 4.x
- Jackson JSR-310
- Lombok
- Validation
- JUnit 5 + Mockito + Spring Boot Test
- Vue 3 + TypeScript + Vite + Element Plus（保留现有前端）

## 5. 后端模块边界

后端放在仓库 `backend/` 下。

### 5.1 common

- `result`：统一响应 `Result<T>`
- `exception`：`BusinessException` 与全局异常处理
- `security`：JWT 生成/解析、认证过滤器、当前用户信息
- `annotation`：`@OperationLog`
- `aspect`：操作日志切面
- `enums`：角色、会议室状态、预约状态

### 5.2 user

职责：用户查询、登录、JWT 发放。

用户密码仅存 BCrypt 哈希，不允许明文比较或日志输出完整 Token。

### 5.3 room

职责：会议室查询与管理员 CRUD。

新增会议室成功后将 ID 加入 Bloom Filter。删除会议室时不尝试从普通 Bloom Filter 删除；后续请求若产生假阳性，再由数据库进行最终判定。

### 5.4 booking

职责：预约创建与预约列表。

这是系统核心模块，包含：

- 参数时间合法性检查
- Bloom Filter 快速判断
- 以 `roomId` 为粒度的 Redisson 锁
- 锁内重新读取会议室状态
- 锁内执行时间段冲突查询
- 无冲突时事务插入预约记录

## 6. 数据模型

### 6.1 `sys_user`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT PK | 用户 ID |
| username | VARCHAR(64) UNIQUE | 用户名 |
| password | VARCHAR(100) | BCrypt 哈希 |
| role | VARCHAR(16) | `ADMIN` / `USER` |
| is_deleted | TINYINT | 逻辑删除 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 6.2 `meeting_room`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT PK | 会议室 ID |
| room_name | VARCHAR(100) | 名称 |
| capacity | INT | 容量 |
| equipment | VARCHAR(500) | JSON 字符串数组 |
| status | TINYINT | `1=可预约, 0=维护中` |
| is_deleted | TINYINT | 逻辑删除 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 6.3 `booking_order`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT PK | 预约 ID |
| user_id | BIGINT | 用户 ID |
| room_id | BIGINT | 会议室 ID |
| topic | VARCHAR(200) | 会议主题 |
| start_time | DATETIME | 开始时间 |
| end_time | DATETIME | 结束时间 |
| status | TINYINT | `1=已预约, 2=已取消` |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

冲突查询索引：

```sql
KEY idx_booking_room_status_time (room_id, status, start_time, end_time)
```

## 7. API 设计

### 7.1 认证

`POST /users/login`

请求：

```json
{
  "username": "admin",
  "password": "123456"
}
```

成功时 `data` 直接返回 JWT 字符串，以兼容现有前端。

### 7.2 会议室

- `GET /rooms/list`：登录用户可访问
- `POST /rooms`：仅 ADMIN
- `PUT /rooms/{id}`：仅 ADMIN
- `DELETE /rooms/{id}`：仅 ADMIN，逻辑删除

`GET /rooms/list` 返回字段保持：

```json
{
  "id": 1,
  "roomName": "星云会议室",
  "capacity": 12,
  "equipment": ["投影仪", "白板"],
  "status": 1
}
```

### 7.3 预约

- `POST /orders/book`：创建预约
- `GET /orders/my`：当前用户预约列表
- `GET /orders`：仅 ADMIN，全部预约

## 8. 并发预约设计

### 8.1 时间冲突条件

新预约 `[newStart, newEnd)` 与已有预约 `[oldStart, oldEnd)` 冲突，当且仅当：

```text
newStart < oldEnd && newEnd > oldStart
```

数据库查询等价为：

```sql
WHERE room_id = ?
  AND status = 1
  AND start_time < :newEnd
  AND end_time > :newStart
```

首尾相接如 `10:00-11:00` 与 `11:00-12:00` 不冲突。

### 8.2 分布式锁

锁 Key：

```text
smartoffice:booking:room:{roomId}
```

只串行化同一会议室的预约，不阻塞其他会议室。

获取锁使用带等待时间、由 Redisson Watchdog 管理租期的方式；获取失败返回“预约繁忙，请稍后重试”。

关键顺序：

```text
参数校验
  -> Bloom Filter
  -> 获取 roomId 锁
  -> 调用独立事务写服务
       -> 数据库重新查询会议室
       -> 锁内检查时间冲突
       -> INSERT booking_order
       -> 事务提交
  -> 事务服务返回
  -> finally 解锁
```

事务边界必须位于锁内部并在释放锁之前完成提交。不能简单在“获取锁并在 finally 解锁”的同一个 public 方法上加 `@Transactional`，因为 Spring 事务拦截器会在方法返回之后才提交，而方法体里的 `finally` 已经先释放锁，会留下“锁释放但数据尚未提交”的并发窗口。实现中使用独立的 `BookingWriteService` 事务代理来保证 **commit-before-unlock**。

锁前的任何查询都不能作为最终正确性依据；共享状态必须在锁内重新判断。

### 8.3 数据库兜底边界

MySQL 对任意时间区间重叠无法用普通 UNIQUE 索引完整表达，因此本项目的同一会议室并发写入以 Redisson 临界区为主。系统不宣称该方案在 Redis 完全不可用或所有外部写库场景下提供数学意义上的绝对互斥；数据库仍是资源存在性和最终数据的权威来源。

## 9. Bloom Filter 设计

Key：

```text
smartoffice:room:id:bloom
```

应用启动时读取所有未删除会议室 ID 并初始化 Bloom Filter；新增会议室事务成功后添加新 ID。

语义：

- Bloom Filter 返回“不存在” -> 快速返回会议室不存在。
- Bloom Filter 返回“可能存在” -> 必须继续查 MySQL。
- 删除会议室不会从普通 Bloom Filter 删除，因此可能产生假阳性，但不会把不存在的数据当作真实会议室创建预约，因为锁内仍会查询 MySQL。

预计容量 10,000，误判率 1%。

## 10. 安全设计

- Spring Security 无状态模式：`SessionCreationPolicy.STATELESS`
- `/users/login`、Knife4j/OpenAPI 静态资源和 `OPTIONS` 放行
- 其他业务接口需要 JWT
- 管理接口使用角色授权
- JWT Secret 从环境变量 `JWT_SECRET` 获取；仓库不提交生产密钥
- JWT 默认有效期 2 小时
- BCrypt `PasswordEncoder`
- 401/403 使用 JSON 响应，不返回 HTML
- 不记录原始密码或完整 JWT

## 11. AOP 操作日志

`@OperationLog("创建预约")` 标注关键写操作。

`@Around` 切面记录：

- 操作名
- 目标类与方法
- 当前用户名
- 执行耗时 ms
- 成功/失败
- 异常类型与简短错误信息

日志只输出到标准日志，不额外增加日志数据库表。

## 12. 配置与部署

### 12.1 本地开发

- 后端：`localhost:8081`
- MySQL：`localhost:3306/smartoffice`
- Redis：`localhost:6379`
- 前端：Vite 默认开发端口

提供 `docker-compose.dev.yml` 仅启动 MySQL 与 Redis，以降低本地环境门槛。

### 12.2 云服务器

提供后端 Dockerfile 和 Host 网络启动示例：

```bash
docker run -d \
  --name smartoffice-backend \
  --network host \
  --env-file .env \
  smartoffice-backend:1.0.0
```

同时在 README 解释：Host 网络是单机部署时为简化宿主机 MySQL/Redis 通信而选择，不宣称是所有生产环境的最佳网络模式。

## 13. 错误处理

业务异常使用 `BusinessException(httpStatus, message)`。

至少覆盖：

- 400：参数错误、结束时间不晚于开始时间、过去时间
- 401：未登录/Token 无效
- 403：无权限
- 404：会议室不存在
- 409：时间冲突
- 423：会议室维护中
- 429：无法及时取得预约锁（仅表达忙碌，不实现独立限流系统）
- 500：未预期异常

响应体始终保持 `Result<T>` 结构。

## 14. 测试策略

### 14.1 纯单元测试

- 时间区间首尾相接不冲突
- 实际重叠能够识别
- 开始时间必须早于结束时间
- AOP 异常必须继续向上抛出

### 14.2 Service 测试

使用 Mockito 隔离 Mapper、Redisson：

- Bloom 判不存在时不访问数据库
- 获取锁失败不插入订单
- 锁内发现会议室不存在返回 404
- 锁内发现维护中拒绝预约
- 锁内发现冲突返回 409
- 无冲突时插入订单
- `finally` 正确解锁

### 14.3 Web/Security 测试

- 登录成功返回 JWT
- 错误密码返回 401
- 未带 Token 访问 `/rooms/list` 返回 401
- USER 访问管理接口返回 403

### 14.4 前端

- `npm run build` 必须通过
- 登录 -> 房间列表 -> 创建预约人工 smoke test

## 15. 完成标准

仅当以下条件都满足，重建才算完成：

1. 项目结构、SQL、README 和 Docker 文件齐全。
2. 前端保持现有三条核心 API 契约，无需大改即可联调。
3. 后端核心测试通过。
4. 前端构建通过。
5. README 明确给出本地启动命令、默认演示账号初始化方式、接口地址和 Docker 部署方式。
6. 关键技术点与简历描述一一对应，且没有把未实现功能写入项目说明。
