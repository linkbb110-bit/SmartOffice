# SmartOffice 渐进升级设计

目标：在已有 Vue + Java 项目上形成可运行、可测试、能解释一致性原理的本地演示系统。保留已有前端 URL 和 JWT 协议；禁止生产操作、推送、删除历史文件和数据卷。

比较：只增强单体最易验证但缺少服务治理展示；立即大规模拆分引入跨事务难题；选择先增强单体业务、随后最小三服务拆分。会议室与预约保持同一数据库事务。用户库只由 user-service 写，booking-service 不写用户表。

预约以 `[start,end)` 冲突判断，Redisson 用于削峰，数据库会议室 `SELECT ... FOR UPDATE` 为最终互斥；READ_COMMITTED 防止锁前快照漏查。预约、取消与管理员房间写入采用同一房间行锁。新增可选 requestId，用户+requestId 唯一，重复且参数相同返回原订单，不同参数返回 409；无 requestId 保持旧客户端行为。取消校验所有者/ADMIN，BOOKED→CANCELED 条件更新，重复取消成功，释放资源即状态变更，不伪造库存。

房间详情 Cache Aside：短期空值、TTL、分布式加载锁、Redis 失败查库；版本令牌+Lua 条件回填防止失效后的旧请求写入。数据库始终作为预约判定依据。管理员提交后清缓存；Bloom 仅作为提示，负值不能代替数据库最终判断。

成功预约与 Outbox 记录同事务。独立批量调度器租约 claim、RocketMQ 同步发送、数据库条件标记 SENT；异常退避重试，可观测状态。消费者以 eventId 唯一键，在一个事务中写通知、审计和消费标记；失败抛出供 RocketMQ 重试。发送后标记前崩溃允许重复投递，由消费幂等解决。没有 PENDING 订单，不新增不必要的超时取消。

最小服务：gateway 只路由、去掉外来内部身份头并对预约入口限流；后端独立 JWT 验证。user-service 登录、注册、本人资料；booking-service 房间、预约、通知、管理员导出。OpenFeign 用于预约创建前的当前用户有效性检查，失败停止新预约，查询/取消不依赖远程可用性。Nacos 可配置关闭，默认本地直连，cloud profile 启用注册与 lb 路由。Sentinel 显式资源限流，用固定本地配置保证没有 dashboard 也生效。

兼容性按官方发布矩阵选稳定同代版本，不关闭 Spring Cloud 兼容校验；RocketMQ 优先直接 Java SDK，避免与 Boot 的 starter 自动配置版本耦合。依赖无法获取时保留可构建的业务层并在进度中记录。

测试：原有单测、H2 真实事务/SQL 的重叠/并发/幂等/取消/回滚/消息消费测试、Redis 边界单测、JWT/接口权限、Feign HTTP 契约、Gateway 路由与限流。H2 不代表 MySQL；缺少 Docker daemon 时必须明确标注真实 MySQL/Redis/RocketMQ/Nacos 验证未执行。
