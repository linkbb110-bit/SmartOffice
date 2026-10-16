# SmartOffice 升级实施计划

> 使用 superpowers:executing-plans 在当前会话逐项执行；最终使用独立代码审查。用户已指定无人值守，不重复询问执行方法。

**目标：** 完成可靠预约与真实消息业务、最小微服务和本地部署。
**架构：** 房间与订单本地事务，Outbox 最终一致性，用户数据单独所有权，Gateway 统一路由。
**技术栈：** Java 17、Spring Boot 3、MyBatis-Plus、MySQL、Redis/Redisson、RocketMQ、Spring Cloud/Alibaba、Vue。
**设计：** `docs/superpowers/specs/2026-10-14-smartoffice-upgrade-design.md`。

## 全局约束及审查重点

不接生产、不推送、不删数据卷，不覆盖用户原始文件（快照保留）。版本须用官方兼容矩阵确认。优先检查 Redis 中断/丢锁、事务回滚与消息发送崩溃窗口、重试键参数变化、取消权限、缓存失效竞态和跨服务失败。

## Task 1：基线修复与预约强化

文件：BookingDTO / BookingOrder / BookingOrderMapper / MeetingRoomMapper / BookingWriteServiceImpl / BookingServiceImpl / OrderController；新增数据库迁移及真实事务集成测试。
接口：创建返回 Long；cancel(orderId,userId,isAdmin) 返回 void；原 POST /orders/book 兼容返回订单 ID。
- [x] 修复测试 matcher 显式类型，运行原 8 类测试，确认基线。
- [x] 写测试验证重复 requestId、变更参数冲突、重叠/相邻、并发单胜者、重复取消、越权、预约与取消竞争、房间删除保护；运行确认缺失行为。
- [x] 实现行锁 + READ_COMMITTED + 唯一请求键 + CAS，保留 Redisson watchdog、Redis 失败安全降级到数据库。
- [x] 运行后端全套测试；更新 ledger 和阶段文档。

## Task 2：缓存与认证边界

文件：新增 RoomCache；修改 MeetingRoomServiceImpl / Bloom / JWT / 异常处理；新增注册 DTO、方法。
接口：RoomCache.get(roomId,Supplier)、evict(roomId)；注册只允许 USER。
- [x] 写 Redis 降级、空值、失效后旧回填和安全边界测试，确认 RED。
- [x] 实现可配置 TTL、加载锁、Lua 版本条件回填、afterCommit 失效失败日志与 TTL 兜底。
- [x] 全套测试和接口鉴权验证；更新进度。

## Task 3：可靠消息

文件：新增 messaging 包、Outbox/notification/audit SQL、调度器、RocketMQ 适配器和接口。
接口：BookingCreatedEvent(eventId,bookingId,userId,roomId,topic,startTime,endTime,occurredAt)；record(order)、consume(event)、publish(event)。
- [x] 写真实事务消费幂等/失败回滚、Outbox 同事务回滚、发送失败重试与租约测试，确认 RED。
- [x] 实现事务 Outbox、可关闭的 MQ 运行时、重试及状态查询，非核心发送不在预约事务内。
- [x] 全套测试；记录没有待确认订单不实施超时取消。

## Task 4：最小微服务

文件：根 pom、shared、user-service、gateway 模块，backend 保留为 booking-service；cloud 配置。
接口：/users/login、/users/register、/users/me；Feign 当前 Bearer token 调用 /users/me；Gateway /api 前缀转发并移除前缀。
- [x] 官方版本矩阵校验，建立聚合 POM；将共享安全/Result 提取，用户类移至 user-service，事务表分属服务。
- [x] 写远程失败/身份不匹配及入口限流/路由测试，确认 RED；实现 Nacos、Feign 和 Sentinel。
- [x] 每个模块编译，聚合全套测试；默认直连可启动，cloud profile 不连接测试环境之外服务。

## Task 5：部署、前端与收尾

文件：Compose、Dockerfiles、部署环境示例和脚本；前端 BookModal/orders；六份中文文档。
- [x] Compose 参数化、独立升级项目数据卷、healthchecks、按服务初始化数据库、现有数据增量迁移；不启动生产或删除卷。
- [x] 前端同一提交复用 requestId、防连续点击；保留原房间页面，提供本人订单取消入口。
- [x] Maven verify、npm build、Compose config、能够执行的本地接口检查；Docker 不可用则记录启动和真实中间件测试未执行。
- [x] 独立审查、可定位问题修复、最终文档和明确的下一天检查清单。


## 最终执行状态

所有当前可安全执行的实现步骤已完成，恢复后根Java17 verify 97/97、前端8/8+build、三JAR+Vite/H2 HTTP及Edge桌面/手机交互已通过，Compose静态通过。独立审查3项Important各有RED→GREEN，见docs/review-findings.md。恢复复验定位并修正了H2微秒时间舍入造成的测试不稳定，失败日志保留。现有数据迁移仅提供方案/SQL，不曾执行；Docker/真实中间件和cloud注册未运行，详见upgrade-progress.md，不将其标成通过。没有Git历史提交，源码和环境快照保留，不推送。
