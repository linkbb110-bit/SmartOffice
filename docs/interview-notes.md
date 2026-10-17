# 项目介绍与面试追问（基于真实实现）

> 2026-10-17 已迁为 frontend 与 backend 四模块工程，以下关键源码路径已更新。10 月 16 日修复回归为 Java 110、前端 15 项通过；目录迁移后的结果见 [迁移验收](directory-layout-verification.md)。文中的真实中间件/浏览器数量指 10 月 14 日历史部署，不是新目录下已重新验收。

## 可用于简历的介绍

SmartOffice（云智办）是会议室预约系统。我在原有 Java17 / Vue3 项目上强化预约一致性，采用数据库房间行锁、READ_COMMITTED、用户请求键幂等与取消CAS，并通过事务 Outbox 连接 RocketMQ 通知/审计业务。系统渐进拆分为 Gateway、用户服务与预约服务，支持 Nacos发现、OpenFeign用户有效性检查和 Sentinel预约限流。Redis详情缓存包含空值、防击穿、版本Lua防旧回填与异常降级。

2026-10-14 历史工程证据：Java17四模块98个不同测试、前端8个行为测试、类型检查与生产构建通过；完整Docker十容器healthy，真实MySQL/Redis/RocketMQ/Nacos验收已完成，API17项和当前镜像Edge桌面/手机9项通过。真实MySQL行锁/回滚、Redis停机降级、Broker发送失败/重启、消费者异常/重启都有SQL、消息offset和日志证据。详情见[real-integration-report.md](real-integration-report.md)，不把本机集成验收说成生产上线或容量压测。

简历可以选择以下三条，按实际参与内容改写，不加未经测量的性能数字：

- 采用MySQL会议室行锁、READ_COMMITTED、requestId唯一键和取消CAS处理时间段冲突及重放，真实12路并发预约与Redis停机降级验收通过。
- 使用Transactional Outbox与RocketMQ实现预约通知/审计的至少一次投递和业务幂等，验证发送失败重试、同事件重复消费、消费者异常与容器重启恢复。
- 渐进拆分Gateway、用户服务、预约服务，通过Nacos发现和OpenFeign校验有效用户；用户服务故障时新预约503且不落库，Sentinel限流与Vue前后端关键流程经完整Docker环境验证。

## 关键代码与可讲亮点

| 亮点 | 关键位置 | 可以解释什么 |
|---|---|---|
| 时间段互斥 | backend/booking-service/.../service/impl/BookingWriteServiceImpl.java、mapper/MeetingRoomMapper.java、BookingOrderMapper.java | 同房间行锁，不把会议室当库存；半开区间冲突 |
| 提交与分布式锁 | backend/booking-service/.../service/impl/BookingServiceImpl.java | 独立事务代理返回后再解锁，watchdog、Redis失败最终由DB兜底 |
| 幂等 | BookingDTO.requestId、BookingOrderMapper.findByRequest、booking_order唯一键 | 同键同参数返回同ID，异参数409；MyBatis一级缓存锁后复查陷阱 |
| 取消与删除竞争 | BookingWriteServiceImpl.cancel、MeetingRoomServiceImpl.deleteRoom | 所有权、CAS、同房间锁、活动订单禁删，逻辑删除保留历史 |
| 缓存 | backend/booking-service/.../cache/RoomCache.java、RedisRoomCacheStore.java | 空值短TTL，加载锁，UUID版本与Lua，失效失败的TTL收敛边界 |
| 可靠消息 | backend/booking-service/.../messaging/BookingOutboxService.java、OutboxStore.java、OutboxRelay.java | 本地事务Outbox、租约所有者条件更新、发送与标记崩溃窗口 |
| 消费业务幂等 | messaging/BookingEventConsumer.java、RocketMqRuntime.java | 订单行锁+receipt，通知/审计同事务，重试与最早offset |
| 真实跨服务需求 | backend/booking-service/.../remote/UserClient.java、RemoteUserVerifier.java | 只验证新预约有效用户，JWT传递、超时503、身份变化401 |
| 服务保护 | backend/booking-service/.../protection/BookingTrafficGuard.java | 实际SDK entry、本地QPS规则、限流请求不执行写操作 |
| 身份与密码 | backend/shared/.../common/security/JwtService.java、backend/user-service/.../SysUserServiceImpl.java、RegisterDTO.java | 签名/过期/claims边界、BCrypt字节限制、注册固定USER |
| 网关边界 | backend/gateway/.../GatewayRoutes.java、ClientIdentityHeaderFilter.java | 同源/api、路由白名单、伪造身份头移除，后端仍自验JWT |
| 前端重试 | frontend/src/utils/bookingSubmission.ts、frontend/src/components/BookModal.vue、MyOrders.vue | 同payload复用requestId、校验前防双击、取消UI和服务幂等 |

`...` 代表 `src/main/java/com/geek/smartoffice`；定位时可直接按类名在IDEA搜索。

## 常见追问与回答要点

**为什么普通库存Lua不能解决预约？** 时间是连续区间，10–11与10:30–11:30争同一资源，11–12又应允许。当前保留数据库真实区间检查，用房间行锁序列化检查与插入。Redis只有辅助锁，不维护数值库存。

**Redis挂了还安全吗？** Redisson异常时进入同一个数据库事务行锁路径，核心一致性不依赖缓存。查询会增加数据库压力，Sentinel限流降低入口压力；数据库也不可用就失败，不能继续凭缓存下单。本轮实际停止本项目Redis容器，详情查询、12路重叠唯一成功、requestId重放、重复取消和替换均通过；恢复后缓存重新写入。未据此声称Redis故障时性能不下降。

**为什么READ_COMMITTED？** 房间锁等待后应看到前序事务提交的订单；避免锁前一致性读产生旧快照。仅调隔离还不够，MyBatis会话缓存也可能保留第一次幂等查询的空结果，所以关键查询显式flushCache。新增两事务都首次miss的Barrier集成测试证明这个问题及修复。

**事务注解和锁顺序？** 外层服务持Redis锁，调用另一Spring Bean的事务方法，代理提交完成才返回，因此不会先解锁后提交。避免同类自调用绕过事务代理；取消与房间管理也有数据库事务。

**如何保证创建与取消并发？** 共用房间行锁。取消条件status=1→2，重复取消无副作用。之后预约按状态重新查区间。取消前预约可能409，客户端可按结果重试，不声称时间上后发请求一定胜出。

**数据库唯一键不能直接阻止时间重叠吗？** user/requestId唯一键只处理重复请求；不同键但重叠时间不满足普通唯一约束。正确性依赖房间行锁下区间查询与插入，而非假设start_time唯一能禁止所有交叉。

**Outbox为何优于简单afterCommit发MQ？** 提交后、发送前进程崩溃时afterCommit内存回调会丢失；Outbox已经和订单一起持久化。发送在事务外，通知失败不撤销预约。表写失败则订单必须回滚，否则系统无法承诺可靠事件。

**会重复消息吗？** 发送成功但SENT标记前崩溃会重发；网络不确定也可能重复。consumer锁订单并同事务写receipt/通知/审计，重复事件直接成功。是至少一次+业务幂等，不宣称MQ exactly-once。

**消费者先后启动的坑？** 新组默认LAST_OFFSET可能跳过已发布事件；当前显式FIRST_OFFSET，已有offset继续使用。实际Broker已验证新组读取先前事件；停止原业务消费者后用真实SDK再投递已消费eventId，确认确切queue/offset形成积压，原消费者重启后消化且三表仍各1。保留期和DLQ耗尽不是Outbox无限保证的范围。

**缓存为什么还能旧？** 成功失效后UUID版本Lua能防旧回填；失效失败则靠TTL收敛，保守默认版本600+内容180秒。交易始终读数据库，陈旧展示不决定预约。这个边界比声称“延迟双删保证强一致”更准确。

**为什么只三个服务？为什么不用Seata？** 用户写用户库，预约只写预约库，远程检查是读；房间与预约属于同一本地事务。没有需要跨服务一起提交的业务，不引入分布式事务。Sentinel/Nacos/Feign各用于真实限流、发现和有效用户检查。

**网关鉴权了吗？JWT撤权马上生效吗？** 网关不重复解析JWT，业务服务独立鉴权，去除伪造身份头。新预约远程查有效用户；其他请求仍按2小时JWT，未实现即时全局撤权或refresh token。

**证明做过测试的材料？** `logs/real-integration/`中的API runId JSON、MySQL LOCK WAIT/READ COMMITTED与唯一SQL异常、Redis原Lua和版本/TTL、Broker ACK/queue/offset/reconsumeTimes、Nacos实际实例列表，以及browser-qa.md中的真实Docker浏览器报告和截图。工程测试另有Surefire XML与构建日志；首轮导出500、测试顺序导致Sentinel误限流、脚本环境错误等失败记录均保留，并有修复后重跑。现场可先运行`real-api-check.mjs --with-sentinel-burst`，故障脚本需按runbook串行执行。

## 第二天演示前检查

先检查`docker compose ... ps`的十服务健康、前端18080和Nacos三个实例，再演示注册/登录/预约/取消与冲突提示。最终验收快照为47订单、47SENT Outbox、三消费表各47、无重叠或遗留trigger；这些是保留的验收数据。若重新跑测试数量会增加，不必清卷。阅读报告中的未执行边界，准备解释至少一次、单Broker、TTL收敛和单实例限流；旧数据整库迁移仍应只对备份后的本地副本操作。
