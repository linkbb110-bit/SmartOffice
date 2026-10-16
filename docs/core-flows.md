# 核心执行流程与一致性边界

## 创建预约

1. JWT 过滤器验证签名、过期与身份声明；Controller 使用 Bean Validation 校验 DTO。时间只接受秒精度，防止 MySQL DATETIME 截断影响事件匹配和重试参数比较。
2. Sentinel 资源 entry 限流；Feign 携带原 Authorization 调用 user-service `/users/me`。身份或角色变化返回 401，远程不可用返回 503，不静默跳过检查。
3. BookingServiceImpl 获取房间 Redisson watchdog 锁；繁忙返回 429，中断恢复线程标记；Redis异常降级到数据库互斥。独立 Spring BookingWriteService 代理负责事务，提交先于 Redis 解锁。解锁失败只记录告警，不把已提交订单响应改成失败。
4. READ_COMMITTED 事务先查询 user_id/request_id 已有订单。相同参数返回原 ID，不同 room/topic/time 返回 409。没有 requestId 的旧客户端仍可提交，但没有请求重放保证。
5. `SELECT meeting_room ... FOR UPDATE` 锁房间，包括逻辑删除记录；新请求拒绝已删除/维修房间。锁后重新查询请求键，显式 flushCache 防止 MyBatis SESSION 一级缓存重放首次空结果。
6. 校验未来时间、start<end，然后查 `status=1 AND start_time < newEnd AND end_time > newStart`。这是 `[start,end)`，10:00–11:00 与 11:00–12:00 可相邻；包含与交叉都冲突。不是数值库存扣减。
7. 插入 BOOKED 订单和 BOOKING_CREATED_V1 Outbox，任一步失败一起回滚。`(user_id,request_id)` 唯一键进一步防止跨房间同键竞争；requestId 使用 ascii_bin，区分大小写。提交后返回 ID。

前端在校验前设置提交互斥；一次相同 payload 失败重试复用 requestId，修改内容、新打开或成功后更换。幂等重试不会撤销取消状态，也不会生成第二个 Outbox。

## 取消、管理员变更

取消读取订单，检查本人或 ADMIN；锁同一房间行，然后 `UPDATE ... WHERE id=? AND status=1` 将状态改成 CANCELED(2)。重复取消返回成功。活动预约的存在由订单状态和实际时间决定，不维护需要反复释放的 Redis 库存。并发预约在取消提交前可能返回 409，取消提交后可重试成功，不能承诺请求顺序必胜。

管理员更新、逻辑删除也锁同一房间行。删除时存在 `status=1 AND end_time>now` 则 409，历史订单与会议室记录保留。维修状态只阻止新预约，不隐式取消既有订单。Room CRUD 提交后才失效缓存/更新 Bloom，回滚不发布失效。

## 详情缓存

RoomCache：读命中（包含空值）→ miss 时尝试短等待加载锁→获得锁后再次读→取得随机 UUID 版本→数据库查询→Lua 比较版本后回填→安全解锁。未获得锁直接读库且不回填，Redis 任一阶段失败同样读库；数据库异常保持传播，不误当 Redis 故障再次查询。

键共用 `{room:<id>}` hash tag。默认内容180秒、空30秒、版本600秒；失效 Lua 原子更新 UUID 并删除值，正在查询的旧版本不再匹配。版本消失时旧 token 也不能写回。TTL 可配置且版本必须长于值 TTL。失效失败下保守陈旧展示上界为版本 TTL + 内容 TTL（默认13分钟，常见为剩余内容3分钟），交易仍直接查数据库。Bloom 初始化/写入失败只告警，其负结果不拒绝真实房间。

## 消息生产、发送与消费

Outbox 状态：PENDING → 条件 claim 为 SENDING（owner UUID、60秒租约）→ SDK SEND_OK → owner 条件标记 SENT。每轮最多20个候选，逐个发送前 claim，避免提前租下整个长批次。异常返回 PENDING，重试从2秒开始指数退避，上限300秒；SENDING 过期会重新 claim。数据库状态与错误类型通过管理员接口查询。

提交后进程崩溃：事件仍在表中，扫描恢复。发送成功但标记前崩溃：可能重复发送，由消费者 eventId 幂等处理。新的消费组显式 CONSUME_FROM_FIRST_OFFSET，读取消费者首次启动之前已补发的事件；已有 offset 则按其进度继续。超过 broker 保留时间、单 broker 丢失数据或 DLQ 需要人工恢复，不能只靠本地 Outbox 已 SENT 状态自动修复。

消费者验证 schemaVersion、UUID 与数据库订单不可变字段并锁订单行。通知和审计按全局 eventId 幂等：已存在则核对业务字段/事件内容，一致时保留，缺项则补齐，不一致则抛出并回滚；随后按 `(event_id,consumer_group)` 补记本组消费标记。三步在同一事务内，不同组重放不会重复写通知；同组重放也不会掩盖不一致的旧记录。全部唯一约束保留。业务失败抛出，SDK 返回 RECONSUME_LATER（最多16次后进入 DLQ），日志记录eventKey、messageId、reconsumeTimes和异常类型以关联真实重投递。通知失败不撤销已提交预约。取消后才收到创建消息也保留“创建”历史事件，不将历史通知解释为当前订单仍有效。

当前订单无待确认状态，不实现 MQ 超时取消。若后续新增 PENDING，必须把超时 CAS、数据库到期时间、幂等资源释放与兜底扫描作为完整业务一同开发。

## 接口错误与本地验收

业务异常、参数绑定/校验异常、非法路径参数、未支持的 HTTP 方法、未知资源和媒体类型错误统一返回 Result，并保留对应 400/403/404/405/406/409/415/429/503 状态。导出在过滤器阶段校验ADMIN并保留方法权限：普通用户无论请求JSON/CSV均403；管理员请求CSV成功，请求不支持的JSON响应类型为406。非法订单 ID 不再误报500；实际MVC回归和真实API权限验收覆盖这些边界。

本地三服务冒烟使用新的 H2 内存库；消息发送失败测试显式将事件设置为到期后再执行 relay，避免 H2 微秒时间舍入导致即时扫描的偶发未到期。该测试验证发送失败后的订单保留、PENDING/attempts 和后续 SENT，不依靠 sleep 模拟成功。

真实Docker验收另已完成：MySQL锁等待/READ COMMITTED、Outbox SQL异常全回滚；Redis停止后的并发预约数据库降级及恢复；实际Broker发送/重复/异常重试和重启恢复；Nacos发现、Feign故障闭合与Sentinel429。当前API17项和浏览器9项通过，完整SQL/消息offset/日志见[真实环境报告](real-integration-report.md)，不从H2结果推断这些结论。
