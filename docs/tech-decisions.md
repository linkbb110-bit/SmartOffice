# 技术选型与真实取舍

| 选择 | 业务价值 / 为什么采用 | 边界 |
|---|---|---|
| 房间数据库行锁 + READ_COMMITTED | 时间区间不适用普通库存扣减；同房间跨任意重叠时段必须序列化判定 | 热门房间锁竞争仍存在，跨房间并行；需真实 MySQL 压测 |
| Redisson watchdog | 房间维度削峰，保留原事务提交先于解锁设计 | Redis 不是最终正确性来源；不可假设租约永不丢失 |
| 用户/requestId 唯一键 + 参数匹配 | 用户重试得到原 ID，防双击及提交响应丢失 | 可选字段兼容旧前端；无键请求无重放承诺，异参数409 |
| MyBatis 查询 flushCache | 锁前空结果不能污染锁后幂等复查 | 对这条关键查询局部配置，未全局关闭缓存 |
| CAS 取消 + 同房间锁 | 预约/取消/删除竞争有数据库顺序，重复取消无需重复释放资源 | 已不存在待确认状态，不虚构超时取消功能 |
| Redis Cache Aside + 空值 TTL | 详情热点和不存在 ID 查询保护，故障时继续查库 | 列表先保留直接查询，避免低价值多维缓存复杂度 |
| UUID 版本 + Lua 条件回填 | 原子判断回填资格与失效，阻止旧查询覆盖新展示值 | 失效异常只由 TTL 有界收敛；不宣称强一致缓存 |
| Transactional Outbox | 解决预约事务提交后、单纯 afterCommit 发送之前的丢事件窗口 | 至少一次，业务幂等；表写失败需要回滚订单 |
| RocketMQ Java SDK 5.3.1 | 真实异步通知/审计，消费者失败 broker 重试 | 避免 starter 与 Boot3 自动配置耦合；MQ关闭积压待发记录 |
| 最早消费偏移 | 全新消费组读取启动前已发送事件，避免先发送后启动的漏消息 | broker保留期/DLQ与单节点故障需人工运维，不等于无限可靠 |
| 本地 broker 同步刷盘 | Compose broker 配置为 SYNC_FLUSH，降低发送确认后进程故障的磁盘丢失窗口 | 已核对真实配置、发送ACK和正常容器重启保留原MsgId；仍是单broker / ASYNC_MASTER，无副本，未验证断电或磁盘损坏 |
| 最小3服务 + shared | 认证数据独立、边缘路由明确；房间/预约事务不拆 | shared 只共享安全基础，不共享业务实体和数据库写入 |
| Nacos discovery | cloud 环境按服务名定位、扩展多个实例 | 暂不加配置中心，避免配置启动依赖与未使用的功能 |
| OpenFeign /users/me | 新预约检查用户仍有效及角色是否变化，实际跨服务业务 | 超时/失败停止新预约；查询和取消用 JWT，不加不必要远程调用 |
| Sentinel 本地 QPS 规则 | 对实际受保护预约入口限流，直连后端也受约束 | 单实例规则，不承诺跨节点全局配额；未做 Dashboard 管理 |
| Gateway + 后端各自 JWT | 路由与信任边界分离，伪造头无权绕过身份认证 | 网关不重复解析 JWT，不存在“信任网关透传身份”的旁路 |
| 两个本地 MySQL 容器 | 账号、库及卷隔离，容易验证数据所有权 | 本地资源成本略高；正式部署可共实例分库分账号 |
| H2 真实事务 + loopback HTTP 测试 | 无外部服务也能验证业务、SQL、Feign 和网关协议 | 不能替代真实 MySQL/Redis/RocketMQ/Nacos 集成结果 |
| Playwright + 已安装 Edge | 无 Browser 插件时验证真实注册、登录、双击预约、取消与手机布局 | 临时工具和截图放在项目外；早期H2与本轮完整Docker真实后端分别记录，后者9项通过 |

版本依据：[Alibaba 官方发布矩阵](https://sca.aliyun.com/docs/2025.x/overview/version-explain/)、[Spring 官方支持版本关系](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)、[Apache RocketMQ SDK](https://github.com/apache/rocketmq/tree/rocketmq-all-5.3.1/client)。选择 Boot3.5.13 / Cloud2025.0.0 / Alibaba2025.0.0.0，同代兼容且 Java17，避免退回较旧 Boot3.2 或跳升 Boot4。构建保留 compatibility verifier。

工程环境曾使用机器旧 HTTP Maven mirror；项目 `.mvn/settings.xml` 改 HTTPS，未改用户全局设置。Surefire 固定已验证的3.5.4，避免新父版本3.5.5下载竞态/解析失败。Windows沙箱 Vite native realpath 与 Java 动态attach存在权限限制，采用标准批准的本地验证，未改ACL、依赖私有补丁或绕过系统限制。

## 明确未实现的功能

没有 PENDING 预约/超时取消；没有 Seata/跨服务写事务；没有 OpenFeign循环调用；没有 Nacos配置中心、Sentinel集群配额、短信/邮件网关；没有 JWT 即时撤销或刷新 Token；没有消息 DLQ 自动回灌与生产级多副本运维。通知是数据库通知记录，审计是数据库业务事件日志，不能称为短信已发送。
