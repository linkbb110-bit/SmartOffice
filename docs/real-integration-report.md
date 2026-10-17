# SmartOffice 真实环境验收报告

> **目录迁移说明（2026-10-17）**：本报告的历史命令、行号及旧路径按原验收版本保留。当前前端在 frontend，后端聚合工程在 backend，原 backend 模块现为 backend/booking-service；Compose/私有部署 env 在 deploy。新启动命令见 [runbook](runbook.md)，完整路径映射与当前验证见 [迁移记录](directory-layout-verification.md)。

> **2026-10-16 修复后的状态**：本页主体为 10 月 14 日部署版本的历史真实验收。后续 R1–R5 已做代码修复和工程回归，但 10 月 16 日 22:39 检查本机 Docker Engine named pipe 不存在；新版本真实 MySQL/Redis/Broker/容器/浏览器回归 **未执行**，不得把下文历史通过数转记为新版本通过。当前证据见 [五项修复验收记录](final-fix-verification.md) 和 [审计报告第 9 章](final-code-review.md#9-五项修复与最终回归2026-10-16)。

日期：2026-10-14（北京时间）。本轮只验收既有升级架构，不新增组件、不大规模重构；仅本地隔离环境，不操作生产、已有业务数据或数据卷，不推送。

## 最终结论（2026-10-14 22:05，北京时间）

现有升级架构已在本机真实Docker环境运行并完成本轮验收。十个容器全部running/healthy；Nacos三个服务实例healthy，Gateway与Feign使用cloud服务发现配置。最后只读核对：47个订单、47个Outbox全部SENT，通知/审计/消费回执各47条，无活跃时间重叠、无缺失消费记录、无遗留验收trigger。八个持久卷保留，原源码备份和私有环境文件保留；没有生产操作、远程推送或删除既有数据。

| 验收项 | 最终状态 | 实际证据 |
|---|---|---|
| Docker构建、启动、健康与通信 | PASS | 三Java镜像完整构建含测试，前端和MQ镜像构建成功；完整up --wait退出0；final-images.json、final-services.log、final-volumes.log |
| 真实MySQL并发/幂等/取消CAS/时间精度 | PASS | 最终API套件17/17，167HTTP、17SQL、16Redis命令；12路重叠仅一成功、12路同key同ID、包含/相邻、重复取消与替换竞争均通过；real-api-cache-final-command.log及runId real_20261014T140145_660669fa JSON |
| 真实MySQL行锁与隔离、回滚 | PASS | mysql-lock和mysql-rollback各5阶段通过；观察真实LOCK WAIT/READ COMMITTED；唯一Outbox SQL异常对应订单与事件均回滚；自己的锁/连接/trigger已清理 |
| MySQL迁移、索引和字段精度 | PASS | 新建隔离schema迁移7/7、旧fixture保留；实际复合唯一键/冲突索引、ascii_bin、DATETIME(0)/(6)记录 |
| Redis Lua、缓存更新/旧回填/加载锁 | PASS | 原Lua21条真实命令；部署JAR探针验证旧token拒绝、空值到期、Redisson排他；最终API实际GET/PUT证实提交后失效和新值回填 |
| Redis故障数据库降级与恢复 | PASS | 停本项目Redis后详情、12路重叠、requestId、重复取消/替换通过；原容器恢复后真实缓存重新存在；redis-outage 5阶段通过 |
| RocketMQ真实事件、重复消费、新组历史 | PASS | 实际SDK5.3.1 SEND_OK、精确queue/offset与业务消费组推进；同eventId两份重复投递后三表仍各1；新组FIRST_OFFSET读取历史事件 |
| Broker停止/重启与Outbox重试 | PASS | producer-outage 20断言；停Broker预约200且Outbox失败次数增长，恢复后自动SENT与消费；旧MsgId及原持久卷跨重启保留 |
| 消费者异常与Broker重试 | PASS | consumer-failure 18断言；同eventId reconsumeTimes 0→1，SQL失败三表全回滚，撤去本轮trigger后各1 |
| 消费者停止后的积压与重启 | PASS | consumer-restart修复后23断言；停止期间原eventId确切offset积压，恢复后消费且三表不重复；mq_20261014T135850_3e0af02450 JSON |
| Nacos/Gateway/OpenFeign/Sentinel | PASS | 最终三实例真实查询、cloud无直连覆盖；用户服务停止新预约503不落库、读/取消仍可用，恢复后成功；64请求突发实际返回Sentinel429 |
| 真实后端浏览器 | PASS | 当前前端镜像Edge155/Playwright1.56.1九项通过，含长名换行、登录注册、双击预约、取消、409重试、401与桌面/手机；real-browser-final-command.log |
| 工程回归（独立于真实中间件） | PASS | Maven98个不同测试：shared21/user15/backend56/gateway6；前端8测试、vue-tsc、Vite构建通过 |

最终未解决的本轮必验失败项：无。过程中真实失败与脚本环境失败仍保留在下方历史及原日志中，不将首轮失败改成通过。未执行/未实现的扩展范围单列在文末。

## 完成清单

- [x] 检查Git、runbook、Compose及Docker客户端。
- [x] 启动并确认现有Docker Desktop Linux Engine可用。
- [x] 按runbook准备私有环境、构建镜像并启动10个服务，核对health与通信。
- [x] 真实MySQL：并发重叠、行锁、requestId、回滚、重复取消、CAS、迁移/索引/时间精度。
- [x] 真实Redis：Lua、缓存失效、旧值回填防护、故障降级与恢复。
- [x] 真实RocketMQ：发送/消费、重复消费、发送失败、消费异常、Outbox重试、重启恢复。
- [x] Cloud：Nacos注册发现、Gateway、Feign、Sentinel实际限流。
- [x] Vue：真实后端登录、预约、取消、权限、重复提交和异常提示。

下方按时间保留当时的执行状态；历史段中的“待执行/正在构建”表示该时间点的状态，最终以本页首表为准。H2、Mock和此前浏览器H2结果不计入真实中间件通过项。

## 环境检查已执行的命令

原升级工作区仍为 `feature/smartoffice-upgrade`，没有提交或远程；所有源码仍在工作区，已有备份保留。

日志目录：`logs/real-integration/20261014-engine-check/`（ignored，仅本机保留）。

| 命令 | 实际结果 | 日志 |
|---|---|---|
| `git status --short --branch` | 原项目无Git历史；升级分支未提交，源码文件未跟踪，不作清理 | 本轮工具输出 |
| `docker --context desktop-linux version` | 退出1，Client29.7.2，Linux Engine管道不存在 | docker-version.log / preflight.json |
| `docker compose version` | Compose v5.4.0可用 | 本轮工具输出 |
| `docker desktop status` | 退出1，Desktop未运行 | desktop-status.log |
| `wsl --list --verbose`（标准授权执行） | 退出0，Ubuntu和docker-desktop均为WSL2/Stopped | wsl-list.log |
| `docker desktop start --timeout 45` | 退出0，正常启动成功 | desktop-start.log / desktop-start-result.json |
| `docker --context desktop-linux version`（启动后） | 退出0，Docker Desktop4.87.0 / Linux Engine29.7.2 | docker-version-after-start.log / engine-ready.json |
| `docker desktop status`（启动后） | 退出0，running | desktop-status-after-start.log |
| `docker info --format 'OSType={{.OSType}}; Memory={{.MemTotal}}; CPUs={{.NCPU}}; ServerVersion={{.ServerVersion}}'` | linux、7897513984字节内存、16CPU、29.7.2 | engine-info.log |
| `docker ps -a --filter label=com.docker.compose.project=smartoffice-upgrade --format '{{.ID}} {{.Names}} {{.Status}}'` | 升级项目无既有容器 | existing-upgrade-containers.log |
| `docker volume ls --filter label=com.docker.compose.project=smartoffice-upgrade --format '{{.Name}}'` | 升级项目无既有卷 | existing-upgrade-volumes.log |

已确认本机Docker Desktop位于 `%LOCALAPPDATA%/Programs/DockerDesktop/`。沙箱内WSL曾返回E_ACCESSDENIED，标准工具授权后的同一只读检查成功；没有修改权限或绕过限制。

按runbook生成新的ignored `.env.upgrade`，使用每项48字节加密随机值，终端/报告不打印凭据；已有文件则保留。开始执行Compose镜像构建，结果待补录。正常启动方式依据[Docker官方start命令](https://docs.docker.com/reference/cli/docker/desktop/start/)。

## 镜像获取失败与已验证的修正

首次 `docker compose --progress plain --env-file .env.upgrade -f docker-compose.upgrade.yml build` 退出1：Maven、Temurin、Node、Nginx、RocketMQ的基础镜像metadata获取失败，公共`auth.docker.io/token`连接超时，未进入应用编译。记录compose-build.log、compose-build-result.json。此结果是构建失败，不是MySQL或MQ业务失败。

只读诊断：本地image列表为空；系统DNS返回108.160.162.98，宿主机同一公共HTTPS端点实际返回200（不记录响应token）；Windows已有系统代理127.0.0.1:7897，当前CLI没有HTTP_PROXY/HTTPS_PROXY。Docker Desktop设置没有手工代理覆盖。诊断脚本最初存在PowerShell foreach管道语法错误，修正后完成读取，不涉及应用代码。

在当前命令进程使用`.NET DefaultProxy.GetProxy()`取得已有代理，临时设置HTTP_PROXY/HTTPS_PROXY，finally恢复；`docker pull redis:7-alpine`退出0，digest为`sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499`，日志proxy-redis-pull.log。该对照支持CLI代理差异是镜像鉴权失败原因；没有修改系统DNS、Docker全局配置、TLS校验或权限。

随后使用同一进程代理执行：

```powershell
docker compose --parallel 2 --progress plain --env-file .env.upgrade -f docker-compose.upgrade.yml build `
  --build-arg HTTP_PROXY=http://host.docker.internal:7897/ `
  --build-arg HTTPS_PROXY=http://host.docker.internal:7897/
docker compose --parallel 2 --env-file .env.upgrade -f docker-compose.upgrade.yml pull mysql-booking mysql-user redis nacos
```

容器构建通过host.docker.internal使用已有宿主代理；地址只是本机实例，换机器应读取自己的系统代理。构建/pull仍在执行，日志compose-build-proxy.log、middleware-pull.log与对应result.json。使用标准代理配置依据[Docker官方网络说明](https://docs.docker.com/desktop/features/networking/)及[CLI代理说明](https://docs.docker.com/engine/cli/proxy/)，未改镜像版本或引入组件。

## Desktop中断与保留式恢复

17:49构建进到Maven后，Desktop正常退出、WSL停止，构建退出1/RPC Unavailable EOF；批量pull同时出现公共token端点EOF。日志compose-build-proxy-result.json、desktop-lifecycle.log。没有证据确认OOM，不能将其写成内存不足或应用编译错误。

随后正常启动失败于Windows运行时AF_UNIX文件：先是`%LOCALAPPDATA%/Docker/run/sailor-ingest.sock`，再是`%LOCALAPPDATA%/docker-secrets-engine/engine.sock`，均报“The file cannot be accessed by the system”。直接读取当前backend日志证明故障发生在Engine启动之前；bounded启动外层50秒超时退出124，未无限等待。相关backend-restart-tail.log、backend-preserved-start-tail.log、preserved-runtime-start-result.json保存。

在确认Desktop进程全部退出、绝对路径父目录精确匹配、运行目录仅含已知零字节socket/reparse条目后，将目录同父路径重命名保留，不递归读取/删除socket，不改ACL，不碰Docker磁盘、卷或设置。保留清单runtime-preservation.json、runtime-pair-preservation.json（最终一轮确认时目录已为空）记录每个原路径和保留路径。18:01:55正常start退出0，再次version成功；Engine现运行。此处理只是本轮运行恢复，不宣称永久修复Windows/Desktop问题。相似问题见[Docker官方仓库报告](https://github.com/docker/desktop-feedback/issues/554)。

之后逐个构建Java镜像，避免一次重跑全部已完成镜像；`.dockerignore`补充logs，避免验收记录与提取的JAR进入构建上下文。已有前端和派生RocketMQ镜像缓存保留，MySQL/Redis镜像缓存保留。

## 已完成的真实中间件检查（分项）

| 项目 | 状态 | 实际命令、结果与证据 |
|---|---|---|
| MySQL/Redis容器初始化和健康 | PASS | `docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml up -d --pull never --no-build --wait --wait-timeout 180 mysql-booking mysql-user redis`，退出0，三个容器healthy；database-start.log |
| MySQL实际表/索引/时间精度 | PASS（结构检查） | Compose exec实际mysql客户端SELECT information_schema，版本8.0.46，六表InnoDB，request_id ascii_bin，唯一索引(user_id,request_id)，房间冲突索引(room_id,status,start_time,end_time)；订单DATETIME精度0，消息时间DATETIME(6)；mysql-schema-evidence.json。服务器默认REPEATABLE-READ，不等同于应用事务显式READ_COMMITTED已验证 |
| V1增量迁移 | PASS：7/7 | `node deploy/upgrade/real-migration-check.mjs`退出0，真实MySQL8.0.46；仅新建schema `real_api_v1_1791972356248_03cb7745b0e7`，旧room/order字段前后逐值一致，真实1062验证唯一键，大小写/跨用户/NULL行为正确；real-migration-result.json及带runId命令日志。schema与fixture保留 |
| Redis真实Lua | PASS：21/21 | `node deploy/upgrade/real-redis-lua-check.mjs`退出0，从应用RedisRoomCacheStore.java提取三个Lua原文，真实Redis7.4.11执行21次命令；VERSION、REFILL、INVALIDATE、旧token拒绝、新token回填、null值符合；TTL实测600/599、179、29秒（配置600/180/30）；redis-lua-result.json。仅本轮独立高ID key，自动TTL保留，无FLUSH或全局清理 |
| RocketMQ容器和NameServer注册健康 | PASS（基础运行） | `docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml up -d --pull never --no-build --wait --wait-timeout 180 namesrv broker`退出0，两容器healthy；rocketmq-start.log。尚不表示预约事件发送/消费通过 |

上述结果均来自本轮真实容器。预约并发/CAS/回滚、完整缓存故障降级、MQ业务与重启、Nacos/Feign/Sentinel、真实数据库浏览器链路仍待后续执行，不用这张表代替它们。

## 容器构建中的测试问题与最小修复

18:12原booking镜像构建实际失败：shared21通过，backend55中54通过/1 error，`BookingTrafficGuardTest`第一次请求就429。Linux与宿主机的测试顺序不同，先前BookingApiSecurityTest已经给同一Sentinel全局资源累计流量；加载新规则不会清掉滚动指标。没有跳测试、放宽生产QPS或替换真实限流SDK。

按现有SDK真实entry先制造两次前序流量，本地`sentinel-order-red.log`稳定复现首请求被阻断；测试fixture仅reset该预约资源指标并在结束时恢复原规则，`sentinel-order-green.log`1/1通过。生产BookingTrafficGuard不改。随后根Java17 `mvn -B -ntp verify`（host-regression-after-sentinel.log）退出0，97/97，0失败/错误/跳过；这是工程回归，不冒充中间件集成。

三Java Dockerfile的Maven RUN加入标准BuildKit持久cache mount `/root/.m2`、同一id和sharing=locked，保留重试下载并串行复用，不skip测试。未新增依赖或服务。Java镜像正在完整重建，尚未把编译前中间状态计为构建成功。

Nacos第一次单独pull出现EOF；具体scope的宿主HTTPS诊断200后再单独复查拉取成功，digest `sha256:a223937902d4292e49ce6bcca8c9d47d29d508075b7f7c6ba98e1a34ff9c3f3b`。`up -d --pull never --no-build --wait --wait-timeout 180 nacos`退出0，真实容器healthy（nacos-start.log）。服务发现业务仍待Java服务启动。

## 应用启动与真实业务验收增量（18:50）

十容器完整 `up -d --pull never --no-build --wait --wait-timeout 360` 退出0，前端18080、Gateway18000已可访问。三Java镜像构建均退出0，Docker内shared21/backend55/user15/gateway6全部通过。真实Nacos v3实例接口对user、booking、gateway分别返回code0和一个healthy实例，端口8082/8081/8080，证据nacos-*-instances.json。

启动后发现非root用户999不能创建/app/logs，Nacos/Sentinel SDK日志报错。仅在三个现有Dockerfile预建/app/logs并授予smartoffice用户，再构建、保留卷重新创建Java容器；up退出0、十容器healthy，`id; test -w /app/logs; find /app/logs`证实用户999能写且已有真实Nacos、Sentinel、RocketMQ日志文件；java-log-owner-fixed.log。更新后限定日志未见目录权限错误。

| 项目 | 实际命令 | 状态与证据 |
|---|---|---|
| 真实API首轮 | `node deploy/upgrade/real-api-check.mjs --with-sentinel-burst` | 15 PASS/1 FAIL，160次HTTP/15次SQL；失败为普通用户JSON Accept导出500，其他权限、12路重叠唯一成功、12路幂等、取消竞争/重复取消、秒精度、真实Sentinel429通过。real-api-result.json及runId历史保留；修复后待重跑 |
| 真实MySQL行锁和隔离 | `node deploy/upgrade/real-failure-check.mjs --mode mysql-lock` | 5/5 PASS，真实performance_schema与INNODB_TRX观察应用SELECT FOR UPDATE在本轮room10行等待，READ COMMITTED；释放后订单/Outbox各1、自己的连接/事务/锁均0。real-failure-mysql-lock-result.json |
| 真实MySQL事务回滚 | `node deploy/upgrade/real-failure-check.mjs --mode mysql-rollback` | 5/5 PASS，仅本轮topic+room的Outbox触发器唯一SIGNAL实际SQLException；HTTP500后订单/Outbox均0；移除本轮trigger后同key成功、各1。real-failure-mysql-rollback-result.json与*.booking-error.log |
| 真实应用Redis缓存/Redisson | `./deploy/upgrade/run-middleware-probe.ps1 -Mode Redis` | PASS，提取正在运行容器JAR（SHA记录）、Java17编译后在容器用同依赖执行：版本Lua/回填/失效/旧token拒绝、null29秒TTL实际到期、加载锁跨线程排他及释放。redis-java-probe-command.log、logs/middleware-probe-75603381396c46e9954d20a6d6458f35/ |
| Redis真实故障降级/恢复 | `node deploy/upgrade/real-failure-check.mjs --mode redis-outage` | 5/5 PASS，26HTTP/5SQL；同项目Redis暂停，详情200、12路重叠只1成功，其余409；同key同ID、重复取消CAS和替换无活跃重叠；重启原容器后真实Redis EXISTS=1且可再预约。real-failure-redis-outage-result.json |

导出500的真实日志为HttpMediaTypeNotAcceptableException；新增回归测试精确复现403预期/500实际。最小修复为导出管理员权限在过滤器阶段校验，保留方法权限，并把媒体协商异常归入HTTP406。根Maven verify退出0，98/98通过（export-media-green-verify.log）；这属于回归而非真实数据库通过。第一条Maven测试命令因PowerShell未引用带点-D参数而解析失败，修正参数后RED测试才是有效复现，两个日志分别保留。修复镜像正在构建。另补充消费失败eventKey/messageId/reconsumeTimes日志供真实重投递关联，不改变重试行为。

## 修复后重跑与消息/浏览器结果（19:00）

最终修复Java镜像构建退出0（final-java-fix-build-result.json），Docker内shared21、user15、backend56、gateway6通过；完整up再次退出0、十容器healthy（final-java-fix-up.log）。真实API `--with-sentinel-burst`重跑为16/16 PASS、160HTTP/15SQL（real-api-fixed-command.log；runId real_20261014T105114_22ad2a54），此前导出失败已在真实环境修复。

- `node deploy/upgrade/real-failure-check.mjs --mode user-outage`：5/5 PASS，13HTTP/4SQL，暂停本项目用户服务，新预约503且订单/Outbox均0，查询/取消不依赖用户服务；恢复原容器后Gateway身份接口和同key新预约成功。real-failure-user-outage-result.json。
- `./deploy/upgrade/run-middleware-probe.ps1 -Mode MqEarliest -EventId 7f8c4cf2-201c-47a9-8152-d356bd433c2d`：PASS，使用正在运行容器JAR、SDK5.3.1与真实Outbox原始payload，首次组从FIRST_OFFSET读取既有事件，三消费表前后各1；mq-earliest-command.log。
- 同事件 `-Mode MqDuplicate`首轮探针失败于只读broker route baseline；未发送重复消息。实际SDK官方源码显示fetchPublishMessageQueues只查询NameServer、不填producer的broker地址缓存；仅探针补真实updateTopicRouteInfoFromNameServer后重跑PASS。实际两SEND_OK有不同MsgId与queue/offset，新观察组读到两份，业务消费组offset超越两份且retry/DLQ/schedule不增，三表仍[1,1,1]；mq-duplicate-command.log保留失败，mq-duplicate-fixed-command.log与middleware-probe-5af9ee0f9dfe45c19d5ba92727f26349保留成功。依据[RocketMQ5.3.1官方实现](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.1/client/src/main/java/org/apache/rocketmq/client/impl/MQAdminImpl.java)，非生产代码修复。
- 真实Docker后端Edge155/Playwright1.56.1浏览器8项PASS：新用户注册/登录、Gateway房间查询、同步双击仅1个带requestId的POST、本人取消、409提示保留弹窗及同参数重试同key、桌面/手机尺寸、无pageerror/非预期console错误、真实401清令牌回登录。命令`node <visualizations>/real-browser-check.mjs http://127.0.0.1:18080`，real-browser-command.log；报告与五截图在`C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/real-browser-evidence/b1dd43c3/`，已实际查看桌面列表和手机弹窗。不使用此前H2浏览器报告。

Broker/消费者故障与重启测试继续执行，尚不计为通过。

## 真实MQ故障恢复已执行（19:05）

`node deploy/upgrade/real-mq-failure-check.mjs --mode producer-outage`退出0，20个实际断言全部通过。runId `mq_20261014T105532_65476ccc07`：Broker停止时HTTP预约200、订单BOOKED/Outbox PENDING，实际attempts>0和lastError保存；恢复原container/image/broker_store卷后Outbox自动SENT、通知/审计/receipt各1。暂停前witness eventId `a5184214-56e5-4513-9e4d-595f1cfe60dc`实际broker MsgId在恢复后完全相同，队列maxoffset保持且增长。finallyBroker与booking均healthy。mq-producer-outage-command.log及同runId JSON保存完整命令与状态。不等同于机器断电或HA验证。

`node deploy/upgrade/real-mq-failure-check.mjs --mode consumer-failure`退出0。runId `mq_20261014T105804_1a6c1afec3`：仅本轮通知内容限定的唯一trigger阻断实际SQL INSERT，预约HTTP200、Outbox SENT，而通知/审计/receipt均0；日志精确关联eventId `1f986036-f0fd-4712-971d-3def32f7000d`，初次reconsumeTimes=0及Broker重投递reconsumeTimes>=1真实失败，retry/schedule实际offset增长；移除本轮trigger后Broker重试完成三表各1、订单仍BOOKED。JSON与mq-consumer-failure-command.log保留。没有用通用日志或其他事件的retry冒充本轮失败。

前端长名称换行修复的`npm test`8/8、`npm run build`（含vue-tsc）退出0。前端Docker单独构建第一次因没有当前进程代理而公共token超时失败；使用已验证的系统代理变量/finally还原后构建退出0，两个日志分别保留。消费者重启与前端更新后的浏览器仍继续验收。

## 消费者重启验收恢复（22:00后）

首次`--mode consumer-restart`（runId `mq_20261014T110042_224dccda94`）在探针artifact-selection阶段失败：Node启动legacy PowerShell时`Get-FileHash`函数不可用，未完成停机积压投递。finally已启动原booking容器并验证Broker/booking健康；该次报告保持FAIL/ENVIRONMENT，不能作为MQ业务通过。只读对照确认直接PowerShell能找到该函数，但Node子进程找不到。探针改用.NET SHA256计算同一JAR的哈希，兼容两种宿主；移除多余ExecutionPolicy参数，不更改系统策略或模块路径。

`node deploy/upgrade/real-mq-failure-check.mjs --mode consumer-restart`修复后退出0，runId `mq_20261014T135850_3e0af02450`，23项断言通过。停止原booking容器后，从该容器提取实际部署JAR，SHA256 `00fcea04f630b64bbf25d8572762f861626193fc35b9165b6d68d179db74ba38`；同一镜像的临时SDK进程发送真实已消费eventId `02038590-ab20-4fae-bec5-58c91335a029`，SEND_OK队列1/offset8。业务组保持停止且consumerOffset<=8、brokerOffset>8，证明形成真实积压；启动原容器后consumerOffset>8、retry/DLQ/schedule不增长，三消费表仍各1。finally两服务healthy。证据mq-consumer-restart-fixed-command.log与同runId JSON。

独立只读复核确认本轮权限/406、MQ关联日志、非root日志目录、Docker缓存与标题换行未发现阻塞或P1/P2；第一次复核因工具额度中断未算完成，恢复后实际完成审查。该复核不代替后续缓存与更新后浏览器运行结果。

## 最终新增缓存与浏览器复验

`node deploy/upgrade/real-api-check.mjs --with-sentinel-burst`：退出0，17/17，runId `real_20261014T140145_660669fa`。新增专用cache房间，真实Redis旧detail TTL177秒；管理员PUT提交后detail不存在且TTL=-2，version不同；GET重新填入新name/capacity，TTL180秒；MySQL读取一致。普通用户PUT403后MySQL各取证字段和Redis detail/version均未改变。记录167HTTP/17SQL/16Redis只读CLI命令及返回值，fixture全部保留。

`node C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/real-browser-check.mjs http://127.0.0.1:18080`：退出0、9项PASS。真实页面长名称换行已复验，五张截图与report.json在`real-browser-evidence/5930f9ff/`；已人工查看桌面卡片和手机弹窗。浏览器报告无pageerror，409/401为本轮预期错误响应；没有忽略其他console错误。该脚本和Playwright工具位于项目外，换机器需重新配置，不能仅靠仓库命令假定可运行。

最终只读核对命令与证据：

```powershell
docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml config -q
docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml ps --format '{{.Service}} {{.State}} {{.Health}}'
docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml images --format json
docker volume ls --filter label=com.docker.compose.project=smartoffice-upgrade --format '{{.Name}}'
# serviceName分别为smartoffice-user-service、smartoffice-booking-service、smartoffice-gateway
docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml exec -T nacos curl -fsS 'http://127.0.0.1:8848/nacos/v3/client/ns/instance/list?serviceName=smartoffice-user-service&healthyOnly=true'
Get-Content logs/real-integration/final-database-check.sql -Raw | docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml exec -T mysql-booking sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B'
```

final-services.log：十服务healthy；final-volumes.log：原八个命名卷；final-database-state.json：47订单/47SENT/三表各47、重叠和遗留trigger均0。最终Nacos核对首次误按旧接口hosts字段读取v3的data数组，断言失败；原返回code0/healthy=true保留为final-nacos-first-response.json，按实际v3格式重跑三服务均通过。final-nacos-summary.json与final-cloud-profile.log记录实际服务发现配置和健康实例。

## 未执行、未实现及保留限制

- **NOT_EXECUTED**：生产/云部署、旧业务数据库整库迁移、多实例并发/长时间负载/容量压测、Nacos自身故障恢复、集群分区与跨机故障。没有据此声称生产上线、全局QPS或高可用。
- **NOT_EXECUTED**：主机断电/强制kill-9、Broker磁盘损坏、SEND_OK后标记SENT前精确崩溃窗口、真实多relay租约竞争、重试耗尽进入DLQ及人工回灌。这些不能由本轮正常容器stop/start替代；已有工程测试只能说明对应代码路径。
- **NOT_EXECUTED**：其他浏览器/更多设备、辅助技术可访问性、浏览器网络层“响应丢失后重试”。本轮真实409失败重试和接口幂等均已测。
- **未实现（原有取舍）**：预约无待确认状态，故无超时取消；无短信/邮件、Nacos配置中心、Sentinel集群配额、JWT即时撤销、DLQ自动回灌、生产多副本部署。本轮未增加组件。
- 单Broker持久卷和SYNC_FLUSH已核对且正常重启保留消息；仍无HA。缓存失效失败靠TTL收敛，不能宣称展示强一致。前端仍有Vite包体/PURE注释告警，测试与构建成功，未做本轮范围外的包体重构。
- 本机Docker Desktop曾有运行时socket启动失败，已保留目录后恢复；不是已证明永久修复。公共镜像鉴权依赖本机现有代理，runbook提供当前进程变量及还原方式。

## 本轮修改范围与可用于简历的事实

生产代码只做导出权限/406修复、消费失败关联日志、三个Dockerfile的Maven缓存与非root日志目录，以及房间长名称换行。新增真实验收脚本位于deploy/upgrade，补充BookingApiSecurityTest与Sentinel测试隔离；没有重做预约核心、拆更多服务或增加技术依赖。

可以写：基于MySQL房间行锁/READ_COMMITTED、请求唯一键与取消CAS实现预约一致性；Redis版本Lua防旧回填并在Redis停止时通过真实并发降级验收；Transactional Outbox结合RocketMQ至少一次投递和幂等通知/审计，完成发送失败、消费者异常与重启恢复验证；Gateway/Nacos/OpenFeign组成三个服务，Sentinel本地限流实际生效。所有“验证”限定本机Docker集成验收，不写生产规模或未经测量的性能提升。

明天先打开前端18080演示注册/登录/预约/取消，查看最终报告和runbook，再核对47条验收数据仍在、Outbox无积压及服务健康。现有验收账号/房间/订单和独立迁移schema均保留，不要把它们当生产数据或删除旧卷以“清理环境”。

22:11:40最终宿主Java17 `mvn -B -ntp verify`退出0、BUILD SUCCESS，耗时1分41秒，三个可执行JAR同步到最终源码。日志final-host-verify.log/result.json，逐Surefire XML统计final-surefire-summary.json为98 tests、0 failures/errors/skipped。它与Docker内构建结果分别保存，属于工程回归。原源码zip SHA256再次核对不变，私有.env.upgrade和日志/备份仍被Git忽略。

最终机器汇总`logs/real-integration/final-summary.json`从实际API/迁移/故障/MQ/浏览器/构建报告读取结果，passed=true。对199个待跟踪源文件核对本轮8个随机凭据值，未发现匹配且未输出任何凭据；final-secret-scan.json保存结果。首次宽泛扫描把公开CORS来源配置也作为长值匹配，四处命中均为CORS_ALLOWED_ORIGINS；按明确凭据key修正核对范围，原记录保留，不误报为密钥泄露。

## 2026-10-16 缺陷修复后的增量验收边界

本轮修改房间设备存储校验/更新时间、消费组与业务事件幂等、两个 Docker 探针的目标检查以及登录错误提示；没有数据库迁移或历史数据清理。Maven 110 项、前端 15 项及探针边界隔离测试是工程证据，不是真实中间件验收。

新 `ConsumerGroupIdempotencyTest` 的 7 个业务测试已实际执行且通过，数据库为 H2。旧 `messaging-probe.log` 启动失败仍保持失败；旧 `MqEarliest` 观察组只证明读到消息，不能证明新版本业务消费组跨组写库。真实 MySQL 容量/更新时间、两业务组在 Broker 上重放及 offset/重试、本轮浏览器登录提示均列为 **NOT_EXECUTED**，原因是 Engine 不可用，未报告它们失败或通过。

后续恢复本地 Docker 后，按 runbook 在保留卷的条件下构建 booking-service/frontend，核验本地 endpoint、固定项目和镜像后复验；不再为这项验收增加框架或改写历史数据。单 Broker、DLQ 耗尽、容量等原有未验证项继续保留。
