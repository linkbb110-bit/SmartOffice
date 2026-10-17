# 房间详情缓存与管理验证（2026-10-14）

> **目录迁移说明（2026-10-17）**：本报告的历史命令、行号及旧路径按原验收版本保留。当前前端在 frontend，后端聚合工程在 backend，原 backend 模块现为 backend/booking-service；Compose/私有部署 env 在 deploy。新启动命令见 [runbook](runbook.md)，完整路径映射与当前验证见 [迁移记录](directory-layout-verification.md)。

## 实现边界

`GET /rooms/{roomId}` 使用 Cache Aside，返回不包含用户凭据的 MeetingRoom 房间记录；列表仍直接查询数据库并返回原 RoomVO。预约和取消的业务判定继续使用数据库及房间行锁，缓存和 Bloom 不参与最终正确性判定。

RoomCache 与 RoomCacheStore 分离：前者负责真实读取算法，RedisRoomCacheStore 使用 StringRedisTemplate + RedissonClient。缓存命中包含显式空值命中；加载锁获取后再次查缓存；锁忙时直接查数据库并且不回填，Redis 读取、获取锁、取得版本、回填等任何阶段失败均降级数据库。数据库错误只执行一次并继续抛出，加载锁在 finally 中释放。日志记录房间、阶段与异常类型。

Redis 键为 `smartoffice:{room:<id>}:detail/version/load-lock`，共同 hash tag 适配集群槽位。版本为 UUID；原子 Lua 取得/初始化版本，回填 Lua 仅在版本仍等于加载前令牌时写缓存；失效 Lua 原子轮换令牌并删除内容。旧加载器遇到失效或版本过期都不能回填。加载锁等待 100 ms，租期 5 秒；即使租期过期，回填仍必须通过版本检查。

默认 TTL：内容 180 秒、空值 30 秒、版本 600 秒；配置项是 `smartoffice.room-cache.value-ttl-seconds`、`null-ttl-seconds`、`version-ttl-seconds`，均要求正数，版本 TTL 必须大于两种内容 TTL。失效失败时只记日志，已提交业务不反转；旧缓存通常在剩余内容 TTL 内过期。在极端长时加载且失效失败时，旧令牌最迟 600 秒失效，最后一次允许的旧回填另有 180 秒 TTL，保守总界限为版本 TTL + 内容 TTL。详情允许这种故障下的短期陈旧；预约决策不读取详情缓存。

## 管理员写入和 Bloom

- 更新和删除均在 READ_COMMITTED 事务中先 `selectForUpdate` 锁同一会议室行，同时拒绝已逻辑删除记录。
- 删除在持锁期间查询 `status=1 AND end_time > now` 的订单；存在则 409。否则通过 MyBatis Plus 逻辑删除保留房间与历史订单，不删除订单数据。
- 维修状态更新允许保留既有预约，不自动取消它们；后续新预约由持锁数据库检查拒绝维修房间。
- 新增、更新和删除只在 afterCommit 失效详情缓存，回滚不失效。新增同时补 Bloom；Bloom 故障只记录日志，不将已提交新增报告为失败。
- Redisson 延迟初始化、连接超时 1000 ms、命令超时 1500 ms、重试 0；限制连接池。启动时 Redis Bloom 初始化失败可继续运行。数据库启动失败仍正常阻止启动，未用 Redis 降级掩盖数据库故障。

## 实际执行

命令（reactor 迁移前）：

```powershell
mvn -f backend/pom.xml '-Dtest=RoomCacheTest,MeetingRoomServiceImplTest,RoomBloomFilterInitializerTest,MeetingRoomAdministrationTransactionTest' test
```

通过标准工具提升权限运行本地测试，没有外部数据库连接。RED 阶段 19 tests / 10 failures / 2 errors：缺缓存命中、旧回填保护、管理员锁/删除活动订单校验/提交后失效，以及 Redis Bloom 异常传播导致失败。修复了纯测试 MyBatis 元数据初始化后，失败均来自预期缺失行为。

GREEN 阶段：**19 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS，24.431 s（11:44:12 完成）**。

- RoomCacheTest：8 个 fake-store 测试，执行实际算法。覆盖正值缓存、空值及短 TTL、加载中失效拒绝旧回填、锁忙不回填、各阶段 Redis 故障降级、数据库失败只执行一次且释放锁、失效失败不影响提交、版本过期拒绝回填。
- MeetingRoomServiceImplTest：5 个单元测试，含管理员更新先取行锁和 Bloom 故障下新增返回正确 ID。
- RoomBloomFilterInitializerTest：1 个启动降级测试。
- MeetingRoomAdministrationTransactionTest：5 个 H2 真实 SQL/事务测试，验证活动预约阻止逻辑删除、历史订单保留、只在提交后失效、回滚不失效、维修保留既有预约、提交后 Bloom 故障不反转新增（部分行为共用一个测试）。

随后新增第 20 个测试：H2 中管理员删除与真实预约写入同时竞争，断言只有预约成功/删除409，或删除成功/预约404两种结果，绝不能删除房间后留下活动订单。该新增测试需在主代理 reactor 迁移稳定后执行；截至本记录写入时未运行。

## 未运行和限制

没有真实 Redis 服务或 MySQL 服务验证。本轮没有执行 Lua 脚本、真实 Redis 集群 hash slot/TTL/连接故障、Redisson 加载锁竞争或真实 Redis 停机启动检查；fake-store 测试证明算法边界，不代表 Redis 实现已集成验证。H2 验证真实事务与 SQL，但不等价于 MySQL 的实际锁行为。完整 Maven suite 与新 reactor 校验由主代理统一执行，避免并发编译目录干扰。

## 主代理最终聚合验证

新增第20项管理员删除/预约并发回归已在Java17根clean verify通过。缓存相关20/20、当时整个reactor96/96，0失败/错误/跳过；恢复后最新verify为97/97，包含新增HTTP错误回归。当前命令应使用根目录mvn -pl backend -am test。原单模块命令仅是当时执行记录。真实三JAR/H2 HTTP及浏览器还验证Redis不可用的预约与取消降级；实际Redis Lua仍未运行。

