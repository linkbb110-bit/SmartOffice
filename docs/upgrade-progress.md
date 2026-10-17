# SmartOffice 升级进度

日期：2026-10-14（北京时间）。仅操作本地源码与测试环境，禁止推送、生产连接和删除数据卷。

**最新状态：真实环境验收已完成（22:05后）。** 十容器healthy，Nacos三服务healthy；真实API17/17，MySQL迁移7/7，行锁/事务回滚、Redis故障降级、MQ重复/生产失败/消费异常/消费者重启、Feign故障恢复及Sentinel均通过。当前前端镜像真实后端浏览器9项通过。最终47订单/47SENT/通知审计回执各47，无活跃重叠及验收trigger。详细命令、失败历史与未执行范围以[real-integration-report.md](real-integration-report.md)为准。下文带时间的阶段记录保留历史，不代表当前仍受Docker阻塞。

## 阶段清单

- [x] 阶段 0：读取原始源码、配置、接口、SQL 和 8 个测试类；原目录没有 Git 元数据，源码已备份到 `.upgrade-backup/original-2026-10-14.zip`。
- [x] 阶段 0：修复基线测试编译并再次运行。
- [x] 阶段 1：数据库房间行锁、请求幂等、取消 CAS、缓存降级及管理保护。
- [x] 阶段 2：事务Outbox、RocketMQ发布重试、消费幂等、通知与审计；真实Broker发送/重复/异常/重启验收完成。
- [x] 阶段 3：Maven聚合、gateway / user-service / booking-service、Nacos发现、Feign、Sentinel；真实注册与故障恢复/限流验收完成。
- [x] 阶段 4：Compose镜像构建、十容器健康、同源API；H2与完整真实Docker浏览器分别完成验证。
- [x] 阶段 5：全量构建与测试、真实中间件/故障/浏览器验收、独立复核、六份中文文档和真实验收报告；扩展未测范围单列。

## 原始架构审查

后端 `backend` 是 Spring Boot 3.4.12 单模块，Java 17；MyBatis-Plus 3.5.12、Redisson 3.50.0、JJWT 0.12.6。Vue 3 / TypeScript / Element Plus 位于根目录。

表：`sys_user`、`meeting_room`、`booking_order`；登录 BCrypt + JWT；管理员房间增改删和订单列表；普通用户房间列表、预约、本人订单。缺少注册、取消、导出、通知及消息表。订单仅有 BOOKED(1)、CANCELED(2)，没有待确认状态。

预约：校验未来时间 → Bloom → 房间 Redisson watchdog 锁 → 独立 Spring 事务服务 → SQL 半开区间冲突检查 → 插入 → 事务提交 → 解锁。正确保留“提交先于释放分布式锁”。风险：只有 Redis 锁，Redis 丢锁/中断后缺少数据库互斥；Bloom 未就绪/新房间提交与 Bloom 写入之间可能误拒绝；取消和幂等不存在。没有 Lua 库存扣减，也没有要恢复的数值库存。

缓存：当前 Redis 只承载锁和 Bloom，房间列表直接查库。房间变更事务后的 Bloom 操作抛异常可能让客户端误判已提交操作失败；删除房间没有活动订单保护。JWT 与权限代码已有，保留并补边界验证。MySQL 初始化每次启动执行 schema/data；仅可用于明确的本地 demo，不应作为现有数据库升级机制。

## 实际基线结果

- Java：Oracle 路径的默认 `java` 启动无输出，改为明确 `JAVA_HOME=C:/Program Files/Java/jdk-17`，Java 17.0.17 可用。
- `mvn -B -f backend/pom.xml test`：失败，已有测试的 4 个 `insert(any()/argThat())` 与 MyBatis-Plus 集合重载歧义。不是业务测试失败，是测试编译失败。日志 `baseline-maven.log`。
- `npm run build`：vue-tsc 步骤完成，Vite 在 `src/main.ts` realpath 遇到 EPERM。待定位后重新验证。
- `docker version`：客户端 29.7.2 存在，Linux daemon named pipe 不存在。容器启动/真实中间件验证当前未执行。

## 执行记录与技术取舍

已按授权初始化本地 `feature/smartoffice-upgrade` 分支（无远程、无提交）。原有源码不做基线提交，保留 zip 快照。测试 matcher 已修正；标准批准的本地 Maven 执行使测试完成。8 类原测试 21/21 通过。新并发回归 RED：12 个同房间重叠数据库事务有 10 个成功，证明不能只依赖 Redis；新增相邻/包含时间测试通过。完整结果 23 tests / 1 failure / 0 errors，日志 `baseline-and-concurrency.log`。

Task 1：行锁回归 GREEN 23/23；请求幂等 RED 4 tests / 2 failures（重复请求被冲突拒绝、同键异参数被接受）；取消测试首次编译明确缺少 API。实现 `SELECT FOR UPDATE` + READ_COMMITTED、用户/requestId 唯一键、参数匹配重放、取消所有权与 CAS。`booking-green.log`：全套 27/27 通过，0 errors / 0 skipped。取消与预约使用同一房间行锁，无 Redis 数值库存需要补偿。新增迁移 `deploy/migrations/V1__booking_request_id.sql` 尚未作用于任何已有数据库。

Task 2：缓存 RED 19 tests / 10 failures / 2 errors，GREEN 19/19（详见 cache-verification.md）。新管理员删除/预约并发测试在首次 root clean verify 中通过。JWT 增强 RED shared 21 tests / 13 failures；UserApi RED 13 tests / 12 failures / 1 error；GREEN shared 21 + user 15 全通过（auth-verification.md）。注册固定 USER、UTF-8 BCrypt 72字节校验、me 校验本库有效身份；健康端点匿名，其他接口认证。

Task 3：消息 API 未实现的首次测试编译 RED；Outbox 核心实现后 H2 5/5 通过，包括同事务回滚、重复消费、通知失败回滚与重试、发送失败保留订单、租约恢复。`message-schema.sql` 在全新 Compose 的 booking MySQL 空卷初始化时执行。MQ 关闭时仍记 Outbox，后续启用补发；不伪造通知为已发送。直接 Apache Java SDK 5.3.1 编译已完成，actual broker未运行。

Task 4：Maven 公共安全提取到 shared，user-service 独立用户库，backend 保持房间/预约本地事务；没有循环调用或 booking 写用户表。Boot 3.5.13 + Cloud 2025.0.0 + Alibaba 2025.0.0.0、Nacos 3.0.3、Sentinel 1.8.9 与官方矩阵同代。不关闭 Cloud compatibility verifier。网关 Java17 6/6 真实 Netty→双上游 HTTP 测试并打包通过；Feign 与 Sentinel API 未实现编译 RED 后完成代码。使用已有稳定 Surefire 3.5.4，项目 HTTPS 镜像覆盖机器旧 HTTP 镜像；不改全局 Maven 配置。

Task 5：前端早期 Node 行为测试6/6与构建通过；最终8/8结果见下。首次 root `clean verify`：shared21、user15、backend50个通过，2个Feign HTTP测试上下文错误（非Web测试不应启用Knife4j，定位并修正配置后2/2通过）；Gateway在该次reactor被SKIPPED。此失败未隐瞒，最终重新验证通过如下。

用户已经明确授权无人值守自主执行；设计/计划保留到文档并自行审查，不设置重复批准阶段。普通失败自主修复，外部服务不可用记录实际阻塞。当前不引入待确认状态，不把 RocketMQ 定时超时取消写成已实现。

## 中断前阶段验证记录（12:12）

2026-10-14 12:12（北京时间），Java17.0.17：`mvn -B -ntp clean verify`，退出0、BUILD SUCCESS，1分34秒。shared21 + user-service15 + backend54 + gateway6 = **96 tests，0 failures / 0 errors / 0 skipped**。三个服务可执行JAR已生成，SDK/BOM/代码编译兼容通过，未跳过测试。日志 upgrade-final-verify.log，报告各模块 target/surefire-reports/TEST-*.xml。

前端最终 `npm test`：**8/8**（含development/production实际Vite env解析）；`npm run build`：vue-tsc及Vite通过，1675 modules、8.31秒，退出0；日志frontend-final.log。仍有既有PURE注释和>500kB bundle告警，不当作失败也不隐藏。

独立审查3个Important全部修复（docs/review-findings.md）：真实SDK默认LAST_OFFSET被回归捕获→改FIRST_OFFSET；两事务首次miss后MyBatis一级缓存导致409被Barrier回归复现→幂等查询flushCache；旧env直连8081被Vite mode回归复现→先备份再改/api。最终新增两后端回归均通过，前端两env回归通过。无未修复Critical/Important。

实际三JAR+Vite本地冒烟：`smoke-local.ps1 -H2Jar <已下载的2.3.232驱动> -WithFrontend`，退出0，**8项检查通过**：Vite页面可达、通过Gateway登录、me数据库身份、会议室查询、真实Feign+预约幂等、仅一订单、重复取消、MQ关闭时唯一PENDING Outbox。三个独立JVM各用显式H2内存库，Redis端口1模拟故障，不连接生产/旧数据库；Nacos与MQ关闭。logs/local-smoke/result.json和local-smoke.log记录，finally已停止本次全部进程。此结果不表示浏览器交互或真实中间件通过。

最终 Compose `config --quiet` 退出0；配置使用10服务/8独立新命名卷、localhost端口、健康依赖、MySQL空卷初始化SQL和非root应用容器。未执行build/up，未删除任何卷。旧源码与环境另有归档，IDEA Maven入口已从backend/pom更新到根pom，原misc.xml保留在ignored备份。

## 明确阻塞、未执行与未实现

| 项目 | 当前状态与原因 | 下一步 |
|---|---|---|
| 实际MySQL并发/隔离与迁移 | 未执行；未为本轮启动MySQL，Docker daemon不存在；H2仅验证本地逻辑 | 按runbook独立新库测试，再审查旧数据复制和V1迁移 |
| Redis Lua/实际断连与恢复 | 未执行；算法fake store及真实HTTP故障降级，无运行Redis | Docker环境验证条件回填/失效和恢复 |
| RocketMQ broker发送/重试/DLQ/新group | 未执行；SDK编译/配置及Outbox/消费事务已测，daemon阻塞 | 真实先发后启动新组、重复消费、停止消费者再恢复 |
| Nacos注册/服务名Feign与lb路由 | 未执行；local真实Feign直连和Gateway契约已通过，cloud需要Nacos | Compose注册列表和服务名路由验收 |
| Docker镜像构建、10容器启动/health | 未执行；Client29.7.2存在，Linux daemon named pipe不存在 | 开启Docker后运行build/up/ps |
| 浏览器故障注入/更多设备 | Edge桌面/手机基本交互已通过；响应丢失的真实浏览器网络注入、其他浏览器未执行，前端重试行为已有单测 | 追加故障注入与更多设备；基本验收见browser-qa.md |
| 压测、生产多副本/可靠存储 | 未执行且不承诺吞吐数字 | 仅在独立环境追加真实压测/运维设计 |
| MQ超时取消 | 未实现：当前没有PENDING业务，不应虚构状态 | 未来有需求再加CAS+到期判断+扫描 |
| JWT即时全局撤权、自动DLQ回灌 | 未实现，见tech-decisions明确取舍 | 有业务需求时另设计，不写入已完成亮点 |

## 用户第二天优先检查

1. 阅读runbook，以根pom重新载入IDEA；升级分支没有历史基线/提交，原目录无Git，不把git diff为空当作无改动。对照源码归档与文档核查，备份未删除。
2. 开启Docker，生成私有.env.upgrade，执行完整配置/构建/启动；只使用smartoffice-upgrade新卷，不清旧卷。
3. 真实验证MySQL并发同requestId回放、跨键重叠与取消/删除竞争，以及Redis失效与旧回填。
4. 先发布事件再启新MQ消费组，查看通知/审计/receipt唯一、SENT状态及失败重试；检查Nacos注册和Feign lb链路。
5. 浏览器从新前端/api完整走一遍，检查参数变化重试、本人取消、权限与错误提示；旧数据迁移先本地备份副本，保持user_id。

## 额度恢复后的断点检查与复验

恢复时检查了 Git、源码、构建日志和进度：原目录无 Git，当前仍是未提交的 `feature/smartoffice-upgrade`，不覆盖原文件快照。上次额度中断导致最终浏览器复验命令未执行，后续文档同步也未执行；中断前实际 `upgrade-release-verify.log` 已有 97/97 BUILD SUCCESS，浏览器上一轮已有 7 项交互检查通过。没有发现遗留的本任务 Java/Node 进程；沙箱 CIM 查询受限，使用 Get-Process 补查。Docker 仍只有 Client，daemon pipe 不存在。

本轮 `upgrade-resumed-verify.log` 在 backend 的消息发送失败测试出现 1 个失败（attempts 预期1实际0），Gateway 被 reactor 跳过，不能记为通过。独立 `outbox-precision-probe.log` 复现 H2 TIMESTAMP 将 `.123456789` 向上舍入为 `.123457`，即时扫描时间仍小于 available_at。测试在首次发送前显式设置到期时间，避免依赖机器速度或 sleep；生产扫描/退避逻辑不改。`messaging-resumed-green.log` 消息可靠性5/5通过。前端 `frontend-resumed-final.log` 8/8、vue-tsc、Vite1675 modules / 6.54秒通过，原包体/PURE告警保留。

冒烟脚本修正报告元数据：本地Java/H2路径校验通过后，每轮正式冒烟先写 running/未通过，finally 记录完成时间和实际结果，成功的浏览器检查不再误写 not executed，运行中失败也不会遗留上一轮通过报告。此前各历史阶段记录仍保留。

## 恢复后最新验证（16:43）

2026-10-14 16:43:21（北京时间），Java17.0.17，根 `mvn -B -ntp verify` 退出0、BUILD SUCCESS、42.748秒。Surefire XML逐模块核对：shared21 + user-service15 + backend55 + gateway6 = **97 tests，0 failures / 0 errors / 0 skipped**。日志 `upgrade-resumed-final-verify.log`。消息测试时间舍入失败已修复并完整复验，首次失败日志保留，不把被跳过的Gateway记为通过。

前端最新 `frontend-resumed-final.log`：`npm test` **8/8**；vue-tsc与Vite生产构建退出0，1675 modules、6.54秒。Compose最新 `config --quiet` 退出0；单broker配置SYNC_FLUSH，但真实刷盘/容器验证仍未执行。原源码备份SHA256再次核对未变：`10F58A1851EF679FC68242B06D0D4E9A92D926C6B590FE4C77747D5F2C24B67B`。

2026-10-14 16:45:13（北京时间），`browser-release.log`：最新三JAR+Vite/H2组合冒烟**9/9**，独立Playwright/Edge真实交互**7/7**。桌面1366×900、手机390×844，注册→登录→预约同步双击仅一POST→本人取消；页面无横向溢出、手机弹窗在视口内；console/pageerror/warning均0。报告browser=passed、实际时间已记录，finally停止全部本轮服务；Get-Process补查无遗留Java/Node。截图人工核对并单列到browser-qa.md，后续清晰截图复验日志单独保留。

16:48:23清晰截图复验 `browser-release-evidence.log` 同样9/9和浏览器7/7通过；仅修改项目外QA脚本等待成功提示自然消失，不修改应用。两张桌面/手机截图已人工查看，订单状态及表单均清晰、弹窗无裁切；报告errors=[]、warnings=[]。本轮服务再次自动停止。

此前新增HTTP错误回归已将非法路径参数500修复为400；两服务CORS来源恢复、容量图标与手机弹窗修复均经最新JAR/页面联调。六份中文必需文档、README及各专项记录已同步97项和浏览器结果，没有把Docker静态配置写成容器通过。

当前已完成能够安全执行的本地开发、构建和验证。剩余真实中间件/容器测试因Docker daemon不可用保留为未执行，未实现功能按业务取舍明确列出；没有生产操作、删除数据或远程推送，源码仍在升级分支工作区，未创建提交。

## 新一轮真实环境验收（18:10起持续更新）

用户明确要求真实环境验收，不新增组件或大规模重构。本轮正常启动Docker Desktop成功；首次镜像构建因Docker Hub公共鉴权超时失败，当前进程沿用已有系统代理后镜像拉取可用。途中Desktop退出及Windows AF_UNIX runtime socket启动故障已按仅保留零字节运行目录的方式恢复，未删除数据/卷/文件或改ACL；过程与保留路径详见real-integration-report.md。Docker Engine29.7.2再次可用，前端/派生MQ镜像已构建，Java镜像逐个构建中。

实际Compose创建本项目新卷，MySQL booking/user与Redis三个容器healthy；NameServer/Broker两容器healthy。真实MySQL8.0.46独立新schema V1迁移7/7通过（旧行保留、字段、索引、1062、大小写/NULL/跨用户）；真实Redis7.4.11运行应用原Lua21/21通过（回填、版本失效、旧token拒绝及TTL）。所有结果都有实际SQL/CLI日志，不能用来代表尚未运行的完整预约、MQ或Cloud链路。新建验收schema与fixtures保留。

增加独立真实验收脚本real-api-check.mjs、real-migration-check.mjs、real-redis-lua-check.mjs，以及复用现有应用类/SDK的RealMiddlewareProbe.java与run-middleware-probe.ps1；语法/编译不计入真实业务通过项。仅.dockerignore加logs防止测试记录/JAR进入构建上下文，业务源码与依赖不改。最终状态以持续维护的docs/real-integration-report.md为准，之前H2与Mock测试继续保留为历史工程证据。
## 2026-10-14 真实环境验收恢复进度（18:50）

- Docker Desktop/Engine已运行，完整升级Compose十容器healthy；真实Nacos三Java服务实例均healthy。
- 真实MySQL8.0.46增量迁移7项、应用行锁/READ COMMITTED5阶段、Outbox INSERT异常事务回滚5阶段通过，证据在logs/real-integration。
- 真实Redis7.4.11 Lua21命令、现有应用JAR缓存/Redisson探针通过；停本项目Redis时数据库并发降级与恢复5阶段通过，未删卷或原数据。
- 真API首轮15/16通过；普通用户JSON Accept导出错误500已定位，新增RED回归后最小权限/406修复，根verify98/98通过，正在构建更新镜像等待真API重跑。
- 非root SDK日志目录权限问题已修复三个Dockerfile，重新启动后用户999可写，Nacos/Sentinel/MQ日志实际生成。没有增加组件或大规模改造。
- MQ故障脚本消费失败证据增加本轮eventId与重投递次数匹配；实际MQ业务/故障恢复、Feign故障恢复与真实后端浏览器仍继续执行，未计为通过。完整记录见real-integration-report.md。

## 真实验收收尾（22:11后）

- 实际必验项均完成：API17/17（167HTTP/17SQL/16Redis命令）、迁移7/7、四个MySQL/Redis/用户服务场景各5阶段、MQ生产者故障20/消费异常18/消费者重启23断言、浏览器9项。命令/日志/JSON/截图均按runId保留。
- 最终十容器healthy、Nacos三个真实healthy实例，cloud profile且无直连URL覆盖；47订单/47SENT/通知审计回执各47，活跃重叠/缺失消费/遗留trigger均0。
- 本轮发现并修复导出JSON Accept500、非root SDK日志目录权限、Sentinel测试全局指标污染、长会议室名裁切；测试探针路由缓存与legacy PowerShell哈希兼容问题也有失败/重跑记录。没有新增组件或重构核心。
- 最终宿主verify于22:11:40退出0，逐XML98 tests/0失败错误跳过，三JAR同步；前端8测试与vue-tsc/Vite构建通过，当前前端镜像浏览器已复验。独立限定范围复核无阻塞或P1/P2。
- 六份中文工程文档、README、浏览器QA和真实验收报告已同步；未做生产部署、HA/断电、长期性能/容量、DLQ耗尽回灌等扩展验证。无待确认状态，故无超时取消；通知是DB记录。
- 私有环境、备份与日志继续忽略，全部验收fixture/schema和八个卷保留；升级分支仍无提交/远程推送。后续按runbook直接启动已有环境，演示前核对健康与Outbox，不删卷。

## 2026-10-16 审计五项缺陷修复收尾

- 仅处理审计 R1–R5：最终设备 JSON 长度校验、消息跨组业务幂等、本地 Docker 探针边界、房间更新时间、登录凭据提示。不引入新框架，不改生产 SQL/唯一约束，不做数据迁移。
- 后端新增 12 项回归，修复前定向 RED 实际复现缺陷；修复后定向 33/33、完整 Maven verify 110/110，恢复后的 22:41:52 再次成功。前端新增 7 项，完整 15/15，类型检查和生产构建通过。
- 消息跨组测试使用实际消费者类及 H2/事务，覆盖新旧组/并发、已存在部分记录、冲突拒绝和 receipt 失败回滚。此前消息审计探针启动失败的记录保留，不能用新测试冒称真实 Broker 通过。
- Docker 22:39 只读检查仍指向本地 desktop-linux，Engine pipe 不存在。本轮没有新的真实数据库/Broker/Redis/浏览器验收；10 月 14 日历史验收不可当作新版本结果。
- 完成状态、探针最终回归与只读复核、本地提交和敏感文件检查统一记在 `final-fix-verification.md`；`final-code-review.md` 保留原问题证据并在第 9 章记录修复增量。开发范围到五项修复为止，不继续扩展功能。

## 2026-10-17 前后端目录整理收尾

- 基于稳定提交 `a29823e` 在 `codex/project-directory-layout` 整理：Vue 工程归入 frontend，Maven 父工程及四个模块归入 backend，原预约模块更名为 booking-service；Compose 和 Dockerfile 归入 deploy。只调整目录与引用，Java/SQL/业务前端及原测试保持原内容。
- 新布局 Maven verify 110/110，前端 15/15、类型检查/构建与开发 HTTP 通过；安装复用本地缓存，未验证全新联网下载。探针隔离 57/57、四种 Compose config 和 40 项源路径检查通过。
- 三个真实 JAR 与 Vite 已完成 8/8 冒烟，覆盖 Vite `/api` → Gateway → 业务服务、HTTP Feign、预约幂等、取消及 Outbox。使用新建内存 H2、MQ/Nacos 关闭，未执行浏览器，不能冒充真实中间件验收。
- Docker 两次只读检查均为本地 desktop-linux pipe 不存在，因此本次镜像构建、容器启动/健康及真实中间件回归未执行。保留所有原数据、卷、私有环境、备份与历史日志。
- 独立目录复核无遗留问题，206 个基线文件全部保留，完整源码树 211 个文件；README、project-structure、runbook 和验收文档已同步。敏感配置、缓存和产物继续忽略，目录整理仅创建本地提交，不推送；完整证据见 [directory-layout-verification.md](directory-layout-verification.md)。
