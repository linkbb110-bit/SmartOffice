# 五项审计缺陷修复与回归记录

> **目录迁移说明（2026-10-17）**：本报告的历史命令、行号及旧路径按原验收版本保留。当前前端在 frontend，后端聚合工程在 backend，原 backend 模块现为 backend/booking-service；Compose/私有部署 env 在 deploy。新启动命令见 [runbook](runbook.md)，完整路径映射与当前验证见 [迁移记录](directory-layout-verification.md)。

日期：2026-10-16。范围仅为 final-code-review.md 的 R1–R5，不扩展框架或功能。

## 计划与状态

- [x] R1：设备列表统一序列化后按 MySQL 字符容量检查，边界 500/501、转义及 Unicode 测试先行。
- [x] R4：更新房间不再回写旧时间；数据库/服务事务回归覆盖字段与回滚。
- [x] R2：业务事件结果跨消费组幂等，保留全部唯一约束；验证旧记录一致性、缺项补齐和事务回滚。
- [x] R3：两个验收入口先拒绝非本地 Docker，固定 endpoint 与目标身份，隔离测试拒绝时零变更。
- [x] R5：登录凭据错误与受保护接口 Token 失效分别处理，前端回归。
- [x] 统一 Maven/前端/相关隔离集成验证，核查 Git 与敏感文件忽略规则，更新审计结论；真实中间件验证因环境不可用单独列为未执行。

R1/R4 共用房间 service，在同一工作区按测试→实现顺序处理；R2 只改消费者及其测试，不迁移/删除已有数据库。R3 与 R5 独立并行，再对五项修改做限定范围的只读复核。恢复收尾时用户授权创建本地稳定基线；提交前检查全部候选文件和敏感文件排除，不推送远程仓库。

## 环境检查

2026-10-16：`docker context show` 为 `desktop-linux`，context endpoint 为本机 `npipe:////./pipe/dockerDesktopLinuxEngine`，DOCKER_HOST/DOCKER_CONTEXT 无覆盖。`docker version --format '{{.Server.Version}}'` 退出 1：named pipe 不存在。当前 Engine 不可用；不将 H2 结果写成真实 MySQL/Broker 结果。后续如运行环境未改变，记录真实验证未执行而不反复重试同一命令。

22:39 恢复后再次只读检查，仍为上述本地 endpoint 且 Engine 不可用；两个 Docker 环境覆盖变量为空。原始结果见 `logs/final-fixes/docker-status.json`。没有启动、停止或变更任何业务容器，也没有连接真实 MySQL、Redis、Broker 或 Nacos。此次不需要破坏性迁移。

## 修复与定向证据

| 问题 | 实现与当前代码位置 | 回归证明及范围 |
| --- | --- | --- |
| R1 / P2 设备长度 | `backend/src/main/java/com/geek/smartoffice/service/impl/MeetingRoomServiceImpl.java:127`，归一化后序列化一次，对 JSON 的 Unicode code point 总数限制 500；超限抛 400 参数错误；不改 VARCHAR(500) | `MeetingRoomAdministrationTransactionTest` 覆盖创建/更新边界、转义、多设备合计、失败时数据库/缓存不变；`BookingApiSecurityTest` 覆盖真实控制器 HTTP 400；`MeetingRoomServiceImplTest` 覆盖非 BMP 字符计数。数据库为 H2，Unicode 边界用实际序列化服务和 Mock Mapper；没有冒称真实 MySQL 字符容量已复验 |
| R2 / P2 跨消费组幂等 | `backend/src/main/java/com/geek/smartoffice/messaging/BookingEventConsumer.java:43`，继续锁订单行，核对已存在通知及审计内容、补齐一致数据缺项，再写本组 receipt；同事务失败回滚 | 新增 `ConsumerGroupIdempotencyTest` 7 项：G1→G2→G1 重放、8 线程两组并发、已有通知/审计单项缺失、旧通知不一致、审计不一致回滚、同 eventId 不同 payload、receipt 写失败回滚及恢复重试。真实生产消费者类、JdbcTemplate/事务与 H2，未启动 Broker |
| R3 / P2 探针目标 | `deploy/upgrade/real-redis-lua-check.mjs`、`deploy/upgrade/run-middleware-probe.ps1`：先解析本机 Docker 元数据，只接受本地 pipe/socket，再固定 `--host`，清除调用时环境覆盖，固定 Compose 项目并检查服务标签和容器 ID | 两个真实脚本入口的隔离测试，拦截 Docker/Java/报告输出，拒绝路径在 exec/cp/run 前退出；最终数量及 CLI 元数据复核见下文 |
| R4 / P3 更新时间 | `MeetingRoomServiceImpl.java:78`，更新时不写入 created_at，用注入 Clock 的当前整秒时间设置 updated_at，匹配 DATETIME(0) | `updateAdvancesModificationTimeAndRollbackPreservesBothTimestamps`：实际事务更新推进旧时间、保持创建时间；外层回滚后字段和缓存均不变。H2 通过，真实 MySQL 待复验；同一秒多次更新不承诺不同时间戳 |
| R5 / P3 登录提示 | `src/utils/request.ts:22`，只对 `POST /users/login` 的 HTTP/body 401 显示凭据错误并保留现有 Token；其他 401 仍清 Token 并跳转 | 新增 7 项测试执行真实 Axios interceptor、loginApi 和 auth helper，仅替换 HTTP/browser 边界；覆盖凭据错误、无后端文案、HTTP/body 401、受保护请求、登录 URL 非 POST。不是真实浏览器验收 |

R2 保留 `message_consumption(event_id,consumer_group)` 主键及通知/审计各自的全局 `event_id` 唯一约束。没有 DROP/ALTER 生产表、没有吞掉任意 DuplicateKeyException、没有覆盖旧业务内容。已有记录不一致会拒绝并回滚，需要人工定位数据来源，不能用自动改写掩盖冲突。测试中的 DELETE/临时约束只作用于 `application-test` 的内存 H2 fixture。

## 实际执行命令与结果

执行目录为仓库根；Java 17，Maven 3.9.4。日志均位于 ignored `logs/final-fixes/`，本节保存可随仓库分发的结果摘要，不提交原始日志或环境变量。

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
# RED：实现修复前执行，28 tests / 7 failures / 4 errors / 0 skipped
& 'D:/apache-maven-3.9.4-bin/apache-maven-3.9.4/bin/mvn.cmd' -B -ntp -o -pl backend -am '-Dtest=MeetingRoomAdministrationTransactionTest,MeetingRoomServiceImplTest,BookingApiSecurityTest,ConsumerGroupIdempotencyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# GREEN：加入已有消息可靠性回归，33 tests 全通过
& 'D:/apache-maven-3.9.4-bin/apache-maven-3.9.4/bin/mvn.cmd' -B -ntp -o -pl backend -am '-Dtest=MeetingRoomAdministrationTransactionTest,MeetingRoomServiceImplTest,BookingApiSecurityTest,ConsumerGroupIdempotencyTest,MessagingReliabilityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 完整回归：16:25 与恢复后的 22:41 两次均成功
& 'D:/apache-maven-3.9.4-bin/apache-maven-3.9.4/bin/mvn.cmd' -B -ntp -o verify
node --test frontend-tests/login-feedback.test.mjs
npm test
npm run build
# 两个探针的隔离安全回归；不访问 Docker daemon
node --experimental-test-module-mocks --test deploy/upgrade/tests/probe-boundary.test.mjs
```

| 执行 | 实际结果 | 日志 |
| --- | --- | --- |
| 后端定向 RED | 正确复现：超长输入落库失败/HTTP 500、时间未前进、跨组撞唯一键等；不是编译或上下文启动失败 | `backend-red.log` |
| 后端定向 GREEN | 33/33，0 failures/errors/skipped，包含原有消息可靠性 5 项 | `backend-green.log` |
| Maven 完整 verify | 110/110，shared 21、user-service 15、backend 68、gateway 6；打包成功；22:41:52 恢复后再次退出 0 | `maven-verify.log`、`maven-resumed-verify.log`、`surefire-summary.json` |
| 登录分支 RED→GREEN | 7 项由 4 fail/3 pass 变为 7/7 pass | `frontend-login-red.log`、`frontend-login-green.log` |
| 完整前端测试 | 15/15，恢复后再次退出 0 | `frontend-tests.log`、`frontend-resumed-tests.log` |
| 前端 vue-tsc + Vite | 构建退出 0；保留既有 PURE 注释和大包提示，不增加包体重构 | `frontend-build.log` |
| 探针边界 RED→GREEN | 初次 53 项 RED 为 47 fail/6 pass；修复后 53/53，补充 CLI 元数据差异场景后最终 **57/57**，0 fail/skip，22:45:06 退出 0 | `probe-boundary-red.log`、最终 `probe-boundary-green.log` |
| Docker CLI 元数据 | 5 项只读核对，本机 Docker 29.7.2；无 daemon 调用、无 context 创建/修改 | `probe-cli-context-metadata.json` |

探针测试不将自行模拟的环境变量优先级当作事实：本机 `DOCKER_CONTEXT=desktop-linux` 与远程格式 `DOCKER_HOST` 同时设置时，`context inspect` 返回 default/TCP。脚本检查返回的 endpoint 并拒绝，只有本地 endpoint 才固定到后续 `--host`；测试增加两种 CLI 解析结果的边界。元数据检查没有连接地址所指的远端。

中途把 PowerShell 入口副本放入 OS 临时目录时，执行策略拒绝，57 项中 47 pass/10 fail；聚焦排查见 `probe-harness-isolation-debug.log`。改为仓库 ignored `logs/final-fixes/probe-fixtures` 中的未改动脚本副本后 57/57，未更改 ExecutionPolicy、ACL 或系统限制。早期隔离测试留下的 87 个新 `logs/middleware-probe-*` 目录是 **stub 测试 fixture，不是真实验收**，对应清单见 `probe-fix-summary.md`；未覆盖历史记录或删除数据，最终 harness 的 artifact 已隔离在专用测试目录。

**消息证据特别说明**：审计阶段 `logs/code-review/messaging-probe.log` 的 `SpringDocConfigProperties` 启动失败仍是失败，未被删除或改记通过。本轮新测试使用项目已有可启动的测试上下文，已执行真实消费者业务断言并从 RED 转为 GREEN；这新增了 H2 事务层的复现/修复证据，未新增任何真实 RocketMQ 组切换或 MySQL 行锁通过结果。2026-10-14 的 Broker 历史验收针对修复前部署版本，不能替代本次修复后的真实验收。

## 未执行与保留边界

- **NOT_EXECUTED / 环境不可用**：新版本镜像构建启动、真实 MySQL 设备 500/501（含 utf8mb4）、更新时间、跨组事务回滚/并发；真实 Broker 两个业务消费组历史重放/offset/重试确认；真实 Redis 探针执行；真实浏览器错误密码与 Token 过期联调。原因均为本地 Engine named pipe 不存在，不是这些业务测试已经失败。
- 需要补验时先启动本机 Docker，按 runbook 重建受影响的 booking-service/frontend 镜像、保留现有卷；只在独立本地测试库/组及专用 fixture 上验证 G1 已有通知、G2 重放后通知/审计各一份及两份 receipt，不切换共享演示服务的组配置来冒险覆盖历史数据。旧 `MqEarliest` observer 仅证明读到消息，不证明业务 consumer 换组完成。
- 本轮没有新增 HA、DLQ 自动回灌、生产安全认证或性能承诺。单 Broker、缓存 TTL 收敛等原有设计边界不因这五项修复消失。

## Git 与保存检查

恢复时 `feature/smartoffice-upgrade` 仍无历史提交，索引为空。候选文件的真实环境、target、node_modules、dist、logs 和原始备份由现有 `.gitignore` 排除，当前没有待跟踪的数据库数据/私钥/临时构建文件，因此无需无关修改忽略规则。

已将 `.env.upgrade` 中 8 项私有凭据在内存与全部待保存文件比对，没有输出值，也未发现匹配；未发现 PEM 私钥头。记录为 `logs/final-fixes/worktree-hygiene.json`。这只是此次保存前泄露检查，不等同完整安全扫描；示例 env 的 change-me/公开本地演示值与真实凭据区分。提交前将对最终索引再次核查。

首次暂存后的全量 `git diff --cached --check` 返回 2：历史 SQL/文档末尾空行、恢复 `.patch` 文件中的上下文空格被 Git 作为新文件的空白警告。保留历史恢复材料，不为通过检查去改写 patch 内容或做无关全库格式化；本轮修改文件另做限定检查。Git 的 LF→CRLF 提示是现有 Windows 配置提示，不是编译/测试失败。

## 限定复核与开发收尾结论

独立复核任务对本轮生产文件差异、六份修复前快照、测试代码和最终日志逐项核对，结论为 critical 0 / important 0 / minor 0，没有发现阻止五项修复收尾的问题。未进行全项目重审，也未把未执行的真实环境场景算通过。

**R1–R5 均已修复并完成相应工程回归；SmartOffice 可以结束本轮开发阶段，进入项目学习、演示整理与 Java 后端实习简历准备。** 仍有环境受阻的真实中间件增量验收，这不等于所有新版本真实场景已通过，也不应在简历夸大为生产级可靠性。消息亮点可描述为保留全局唯一约束的业务事件幂等、跨组 receipt、事务回滚及异常回归，明确跨组新增证明来自 H2。

用户授权本地基线提交，保存命令为 `git add -- .`、检查索引后 `git commit -m "chore: save SmartOffice stable baseline after five audit fixes"`。提交哈希与最终干净状态通过 `git log -1 --oneline`、`git status --short --branch` 核对并在交付消息报告，不在本提交内递归写自己的哈希。只保存经过检查的源码、测试与文档；忽略原始日志/私有 env/构建产物和备份，不推送。
