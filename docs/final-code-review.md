# SmartOffice 全项目深度代码审查

> **目录迁移说明（2026-10-17）**：本报告的历史命令、行号及旧路径按原验收版本保留。当前前端在 frontend，后端聚合工程在 backend，原 backend 模块现为 backend/booking-service；Compose/私有部署 env 在 deploy。新启动命令见 [runbook](runbook.md)，完整路径映射与当前验证见 [迁移记录](directory-layout-verification.md)。

> **修复完成（2026-10-16）**：R1–R5 五项均已修复并完成工程回归；限定复核未发现新增阻塞问题。Maven 110/110、前端 15/15、探针隔离 57/57；真实中间件增量验收因本地 Docker Engine 不可用而未执行。第 1–8 章保留修复前审计快照（含当时的行号、失败探针与待修建议），不应再据此判断当前代码是否仍有同一缺陷。当前实现与证据见第 9 章及 [五项修复验收记录](final-fix-verification.md)。

源码审查与已有探针日期：2026-10-15；报告收尾日期：2026-10-16（Asia/Shanghai）。对象为 `feature/smartoffice-upgrade` 工作区，不是历史提交差异。审查时仓库尚无提交，源码均为未跟踪文件，不能用空的 `git diff` 判断改动范围。本次收尾只读取已有结果及相关代码行、完善本报告，没有重新扫描全项目或重复执行完整测试，未修改核心业务逻辑，未启动或停止业务容器，未操作已有业务数据、数据卷或远程仓库。

> **修复前审计结论：项目具备作为 Java 后端实习简历核心项目的基础，核心预约和同组消息幂等有实际实现及测试支撑。确认 5 项代码层面问题：P0 0 项、P1 0 项、P2 3 项、P3 2 项；其中 3 项有隔离探针支持，2 项为静态调用链确认。未发现已成立的严重越权或重复预约缺陷，但不能据此宣称生产级可靠、所有故障场景已验证或完整安全扫描通过。**

## 1. 审查范围与证据边界

独立阅读四个 Maven 模块的生产代码、主要业务与安全测试、SQL/迁移、Vue 页面与请求封装、升级 Compose/Dockerfile、真实验收脚本，以及架构、流程、运行和面试文档。预约/缓存、消息可靠性、前端/部署由三个独立审查任务分别复核，主审负责认证、服务边界、证据交叉检查和结论。

证据等级分开处理：

- **本轮执行**：现有 Maven 测试、前端测试及构建、独立隔离探针。隔离探针调用现有编译类或配置，未替换生产业务实现。
- **代码确认**：可以从现有调用链、生成 SQL 和约束推导的确定行为，不等于真实中间件复现。
- **历史验收**：2026-10-14 的真实 MySQL、Redis、RocketMQ、Nacos 和浏览器证据。核查脚本与记录的对应关系，但不冒称本轮再次执行。
- **待验证风险**：需要特定部署、故障窗口、容量或真实中间件实验才能判断影响的事项，不计入已证实问题。

本轮此前已执行 `docker version --format '{{.Server.Version}}'` 和 `docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml ps --format json`，均退出 1，原因是本地 `dockerDesktopLinuxEngine` named pipe 不存在。因此本轮没有重新运行真实中间件故障测试，也没有浏览器连接真实后端的新增结果。

### 已有审查阶段构建与验证（2026-10-15）

| 执行 | 实际结果 | 证据与限制 |
| --- | --- | --- |
| Java 17，根目录 `mvn -B -ntp -o verify` | BUILD SUCCESS；98 项，0 failures/errors/skipped | `logs/code-review/maven-verify.log`，2026-10-15 16:03:56 完成；shared 21、user-service 15、backend 56、gateway 6。包含 Mock、H2 和本地 HTTP 测试，不是 98 项真实中间件测试 |
| `npm test` | 8/8 通过 | `logs/code-review/frontend-tests.log`；主要验证提交/取消状态及配置，不代表完整 Vue 浏览器覆盖 |
| `npm run build` | vue-tsc 与 Vite 构建成功 | `logs/code-review/frontend-build.log`；仍有 PURE 注释和大包提示，主 bundle 1,033.43 kB，gzip 340.32 kB |
| Docker Engine 状态 | 不可用 | 只读检查失败；不影响源码审查，不能报告本轮十容器 healthy |

恢复额度后没有重新执行上述完整构建，也没有重启完整安全扫描；只继续未完成探针和报告复核。

## 2. 已核实的设计与真实边界

### 2.1 预约、取消与数据库

- 冲突 SQL 使用 `start_time < newEnd AND end_time > newStart`，只查询 BOOKED，符合半开区间；相邻预约允许，包含、交叉重叠拒绝。跨天不依赖日期相等判断；后端支持跨天，当前前端仅提供同日表单。
- `BookingServiceImpl` 调用独立的 `BookingWriteServiceImpl` Spring Bean，后者声明 READ_COMMITTED 事务；房间 `SELECT ... FOR UPDATE` 在事务内执行。预约、取消和删除使用同一房间行锁，Redis 失效时仍保留数据库互斥。没有把时间段当作库存扣减，也没有虚构 Redis 库存释放。
- requestId 有 `(user_id, request_id)` 唯一键、ASCII 二进制比较、锁前与锁后查询、参数匹配；查询 `flushCache=TRUE` 避免第一次 miss 留在 MyBatis 会话缓存。相同请求重放返回原 ID，已取消订单也不会因重放重新变为 BOOKED；无 requestId 的兼容请求不承诺重放。
- 取消检查数据库订单所有权，ADMIN 可管理；`status=1 -> 2` 条件更新使重复取消无副作用。删除房间前拒绝尚未结束的有效预约。未发现普通用户取消他人预约、忽略事务代理或 Redis 锁失效即重复占用的确定路径。
- 预约时间按整秒校验，匹配 DATETIME(0)；Outbox 时间列使用 DATETIME(6)。未对任意年份范围或最大预约时长提供业务规则，不把尚未定义的产品规则直接判成 Bug。
- `(room_id,status,start_time,end_time)` 支持冲突查询的前缀与范围，`end_time` 仍可能是残余过滤；不能宣称双范围都能高效定位。用户列表有 `(user_id,start_time)` 索引。未运行大数据 EXPLAIN/容量测试，也未发现逐订单额外查库的 N+1。

### 2.2 Redis、Redisson 与缓存

- 预约锁使用 watchdog 重载；最终只在当前线程持有锁时释放，Redis 异常后进入数据库事务，释放异常不会把已提交订单变成失败响应。
- 详情采用 Cache Aside、空值短 TTL、加载锁、UUID 版本与原子 Lua 条件回填。版本与内容键共享 hash tag；成功失效后，旧查询持有的版本不能覆盖新版本。
- 加载锁忙时直接查库而不回填，避免无资格的旧值写入；这不等于所有并发 miss 都只查询一次数据库。Redis 故障时查库，数据库自身异常不会误被当成 Redis 异常后重复执行 loader。
- 失效失败可能保留旧展示值，现有文档明确接受 TTL 收敛；默认保守窗口为版本 600 秒加内容 180 秒。预约判断不读缓存，所以该窗口不是重复预约漏洞。未承诺强一致缓存或 Redis 故障时性能不下降。

### 2.3 RocketMQ 与 Outbox

- 订单插入与 Outbox 写入在同库同事务，Outbox 入口要求已有事务；消息发送不在预约事务中执行。通知发送失败不撤销已成功预约，Outbox 插入失败则必须回滚订单。
- Relay 采用到期扫描、条件认领、每次认领新的 owner、60 秒租约，SENT/retry 更新检查 owner。多实例可能读到相同候选，但正常情况下只有一个条件更新获胜；租约过期后的重复投递属于至少一次语义。
- SEND_OK 后更新 SENT 失败、发送结果不确定、进程中途退出，均可能再次投递相同 eventId；不能把“允许重复发送”本身当作错误，也不能描述为 exactly-once。
- 消费者对订单加行锁、校验不可变业务字段，再将通知、审计和 receipt 放入同一事务；异常返回 RECONSUME_LATER。当前同一消费组、同一事件的重复处理有数据库约束保障。
- 生产端持久重试不设置耗尽终态，退避上限 300 秒；消费者配置最多重试 16 次。DLQ 人工排查和保留原 eventId 重放仅有运行说明，没有自动回灌、告警闭环或保留期之外的无限恢复保证。
- Broker 是单节点、SYNC_FLUSH、持久卷，没有副本。历史普通重启恢复不证明断电、磁盘损坏、HA 或生产灾难恢复能力。

### 2.4 认证、微服务与接口安全

- JWT 使用 JJWT 验签，验证有效期与必需的 uid/subject/role/expiration；拒绝空密钥和少于 32 字节的密钥。注册固定 USER，BCrypt 密码处理考虑 72 字节限制。SQL 参数绑定未发现客户端可控 SQL 拼接入口。
- Gateway 按白名单路由并清理伪造身份头；实际 JWT、角色和订单所有权校验在业务服务。直连业务服务不会仅凭 X-User-* 获得身份。网关没有单独解析 JWT，这是当前设计，不能在简历写“Gateway 完成 JWT 统一认证”。
- OpenFeign 仅在创建预约前以原 Bearer 查询 `/users/me`，核对当前数据库身份、用户名及角色；1 秒连接/2 秒读取超时，401/403 转身份失效，其余异常拒绝新预约并返回 503。调用发生在房间行锁之前，没有跨库写事务或循环依赖。
- Sentinel 仅保护预约入口、单实例 20 QPS；不存在集群配额或已配置的熔断规则。查询、取消不依赖远程用户服务，有利于故障时继续处理已有订单。
- 管理接口有方法权限，导出另有过滤器权限；当前用户通知/订单以服务端身份过滤。CORS 是来源列表，不是通配允许任意来源。未发现密码或 Token 正常写入业务操作日志的路径。
- **限制**：除新预约与 `/users/me` 外，其他接口接受尚未到期的旧 JWT；没有即时撤权/refresh token。登录注册没有专门的限速/账号锁定，登录 DTO 缺少长度上限；这些部署风险见后文。没有把已明确未实现的全局撤权误报成已证明的远程越权。

版本关系已核对 [Alibaba 官方矩阵](https://sca.aliyun.com/docs/2025.x/overview/version-explain/) 和 [Spring Cloud 支持表](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)：Boot 3.5.x、Cloud 2025.0.x、Alibaba 2025.0.0.0 属于匹配版本线，矩阵列出 Nacos 3.0.3、Sentinel 1.8.9、RocketMQ 5.3.1。根构建通过支持工程兼容性判断，但不等于已完成所有传递依赖的 CVE 审计。

## 3. 已证实问题清单与分级

这里的“确认”包括能够由确定代码路径与数据库约束证明的缺陷，**不表示每项都完成真实环境复现**。测试方式与尚未验证的后果逐项列明；没有把探针源文件中尚未执行的断言算成通过。

| 编号 | 级别 | 问题 | 确认证据 | 真实环境复现 |
| --- | --- | --- | --- | --- |
| R1 | P2 | 设备字段通过校验后仍可能超过 VARCHAR(500) | 实际 DTO Validator、Jackson 探针通过；SQL及异常路径确认 | 未新增真实 MySQL/HTTP 复现 |
| R2 | P2 | 切换业务消费组时，组内 receipt 去重与全局事件唯一约束冲突 | 当前消费者代码、DDL、重试入口组成确定路径 | 未完成；消息探针启动失败 |
| R3 | P2 | 两个验收入口未检查 Docker 是否为本地目标 | Lua 入口隔离拦截测试；PowerShell 入口静态确认 | 没有访问远程 Docker，也无需这样验证 |
| R4 | P3 | 更新房间时将旧 updated_at 显式写回 | 当前 Mapper 生成 SQL 探针通过，结合 MySQL 自动更新规则 | 未新增真实 MySQL 更新复现 |
| R5 | P3 | 登录密码错误被提示为登录已过期 | 后端 401 到前端拦截器的完整静态路径 | 未新增浏览器复现 |

P0/P1 均为 0，表示本次有证据支持的发现中没有这两个等级，**不表示证明整个项目不存在任何严重问题**。P3 是真实但低影响的问题，并非个人编码风格偏好。

### R1 / P2：设备 JSON 长度未与数据库列容量对齐

- **位置**：`backend/src/main/java/com/geek/smartoffice/model/dto/RoomSaveDTO.java:21`；`backend/src/main/java/com/geek/smartoffice/service/impl/MeetingRoomServiceImpl.java:117`；`backend/src/main/resources/schema.sql:5`。写入入口在同一 service 的第 66、77 行；一般异常处理在 `shared/src/main/java/com/geek/smartoffice/common/exception/GlobalExceptionHandler.java:75`。
- **原因**：DTO 仅约束每个设备名非空，没有总长度约束；服务序列化整个列表，数据库却限定 `equipment VARCHAR(500)`。JSON 的括号、引号、逗号和转义也占存储字符。
- **触发条件**：ADMIN 正常提交创建或更新请求，其他字段合法，设备列表为一个含 497 个 `x` 的名称；或者一个含 249 个双引号的名称。两者都通过现有校验，序列化后分别为 501、502 个字符。
- **证据**：`logs/code-review/booking-room-readonly-probe.log` 实际记录 `DTO_VIOLATIONS=0 ENCODED_LENGTH=501/502 DB_MAX=500` 及 `PROBE_PASS`。使用当前 DTO、Hibernate Validator 和 Jackson，没有 DataSource。严格模式下 MySQL 拒绝越界写入，随后一般异常映射为 500，这一 HTTP 后果由调用链推导，未在本轮真实请求中复现。MySQL 长度与严格模式行为见[官方说明](https://dev.mysql.com/doc/refman/8.0/en/char.html)。
- **影响**：合法通过接口校验的管理员输入落到数据库后失败，用户得到服务器错误而非可解释的参数错误。没有证据表明造成重复预约或权限绕过；主动关闭严格模式的环境还有截断风险，但本轮没有验证这种非默认配置。
- **建议**：在统一序列化后的存储边界校验长度，超限返回 400；或按业务需求调整列类型并保留明确容量上限。不能只增加单个设备名长度限制而忽略合计和转义；Unicode 字符计数应与数据库语义一致。
- **补测**：创建/更新分别覆盖序列化后 500 和 501 的边界、引号/反斜杠、多设备合计、非 BMP 字符；超限时断言没有数据库修改和缓存失效。真实 MySQL 测试应在独立测试库执行。

### R2 / P2：消费组切换后已消费事件持续撞唯一键

- **位置**：`backend/src/main/java/com/geek/smartoffice/messaging/BookingEventConsumer.java:25`、`:40`、`:43`、`:49`；`backend/src/main/resources/message-schema.sql:14`、`:19`、`:25`；`backend/src/main/java/com/geek/smartoffice/messaging/RocketMqRuntime.java:57`、`:61`、`:76`、`:81`。
- **原因**：receipt 以 `(event_id, consumer_group)` 判重，通知和审计却分别要求 `event_id` 全局唯一。配置新消费组后，旧事件在新组中没有 receipt，处理器会重新执行普通 INSERT，而非识别已经存在的业务结果。
- **可信触发路径**：① 组 G1 成功消费事件 E，三张表各有记录；② 保留业务数据库，将应用组配置改为 G2；③ 新组 FIRST_OFFSET 读到仍在 Broker 保留期内的 E；④ `(E,G2)` 查询为 0；⑤ 通知 INSERT 因 E 已存在而失败，事务回滚，G2 的 receipt 仍不存在；⑥ Runtime 返回 RECONSUME_LATER，后续相同处理仍然失败。
- **证据与验证状态**：上述字段与 SQL 在当前源码/DDL 中确定存在，因此确认**配置切换场景的代码缺陷**。`logs/code-review/MessagingAuditProbe.java` 虽包含复现断言，但 `messaging-probe.log` 显示 Spring 启动阶段缺少 `SpringDocConfigProperties`，尚未执行任何业务断言；**不能称 H2 或真实 Broker 复现成功**。该失败是审计探针配置问题，也不能反推正常业务服务启动失败。真实重试次数、队列积压及 DLQ 后果仍待集成验证。
- **影响**：切换组或另起一组复用相同业务表时，已成功处理的历史消息不能被正常确认，产生无效重试和排障噪声，可能进入 DLQ。原通知、审计没有被删掉；当前固定组的正常重复消费仍然成立，不能夸大为所有通知丢失、原订单损坏或整个队列永久阻塞。
- **建议**：将“某业务副作用已完成”的幂等标识与可变的 MQ 部署组名称分开。新组遇到已完成的同一事件，应核实业务记录一致并安全补记本组 receipt，所有步骤保持原子；也可明确固定业务消费组身份并设计受控迁移。不要通过移除通知唯一约束来允许重复，也不要无条件吞掉所有 DuplicateKeyException。
- **补测**：先 G1 消费，再 G2 重放同一事件，断言两组均可成功确认、通知/审计仍各一份；覆盖旧数据缺一张业务记录、不同内容冒用同一 eventId、两组并发。之后在真实 Broker 验证新组 FIRST_OFFSET、确认偏移及无无效重试。现有新组 observer 探针只证明能读取消息，不能证明此业务处理器成功入库。

### R3 / P2：验收探针没有完整约束 Docker 执行目标

- **位置**：`deploy/upgrade/real-redis-lua-check.mjs:42`、`:50`、`:94`、`:99`；`deploy/upgrade/run-middleware-probe.ps1:69`、`:73`、`:157`、`:167`。实际 Redis/MQ 写入见 `deploy/upgrade/RealMiddlewareProbe.java:138`、`:264`、`:328`。运行说明涉及 `docs/runbook.md:148`、`:149`、`:159`。
- **原因**：Lua 脚本直接调用 Docker Compose exec；PowerShell 探针核对项目、容器及镜像身份，但没有验证 Docker endpoint 为本机。项目标签相同不能证明远端不存在同名项目。runbook 将这些命令一并描述为“脚本拒绝远程 Docker”，保证范围过宽。
- **触发条件**：操作者的 DOCKER_HOST 或活动 context 指向远程 Engine，远端存在匹配项目/服务，然后执行这两个独立入口。没有断言当前用户已经这样操作。
- **证据**：`logs/code-review/remote-probe-boundary.test.mjs` 用 Node 内置 module mock 导入未改动的 Lua 脚本，将 DOCKER_HOST 设为文档保留地址 `tcp://192.0.2.1:2375`，拦截所有子进程和报告写入。`remote-probe-boundary.log` 为 1/1 通过，证明仍走到 EVAL，实际没有访问网络或 Docker，也没有覆盖历史验收报告。日志中的 `FAIL version-lua-create` 是 stub 故意中止写命令，不能当真实 Redis 失败。PowerShell 同类入口仅静态确认。
- **影响**：验收工具可能向授权范围之外的目标追加测试键或 MQ 消息。这是**工具安全边界缺陷，不是业务应用的远程漏洞**。Redis 使用预检查不存在的随机键及 TTL，没有证据表明覆盖既有业务键；MQ 探针可重发指定的已有事件，仍属于写操作。未发现实际误操作或数据删除。
- **建议**：任何 exec/cp/run 前解析实际生效的 Docker endpoint，拒绝远程目标，固定已核验的 context/项目并贯穿后续命令；为每个独立入口补一致的保护。同步收窄或修正 runbook 的承诺。不要连接真实远端来验证拒绝逻辑。
- **补测**：使用 stub 覆盖远程 DOCKER_HOST、远程活动 context、本地 endpoint、环境变量优先级及标签不符；拒绝时必须断言零变更命令。其他已有 API/迁移/故障脚本的目标检查不能代替这两个独立入口。

### R4 / P3：房间 updated_at 在修改后仍保持旧值

- **位置**：`backend/src/main/java/com/geek/smartoffice/service/impl/MeetingRoomServiceImpl.java:74` 至第 77 行；`backend/src/main/java/com/geek/smartoffice/mapper/MeetingRoomMapper.java:9`；`backend/src/main/java/com/geek/smartoffice/model/entity/MeetingRoom.java:23`；`backend/src/main/resources/schema.sql:9`。
- **原因/触发**：管理员修改房间时，SELECT * 读出含旧 updatedAt 的实体，copy 仅替换业务字段，updateById 把旧时间一并作为非空字段显式更新。并非只有同一秒更新才发生。
- **证据**：实际 Mapper 探针生成 `UPDATE meeting_room SET room_name=?, capacity=?, equipment=?, status=?, created_at=?, updated_at=? WHERE id=? AND is_deleted=0`，参数包含 `et.updatedAt`，值为探针设置的旧日期 `2020-01-01T00:00`。MySQL 对显式写回当前值的自动时间列不会再自动更新时间，见[官方规则](https://dev.mysql.com/doc/refman/8.0/en/timestamp-initialization.html)。这是生成 SQL 加确定数据库语义的确认，未新增真实 MySQL UPDATE 复现。
- **影响**：房间详情及数据库修改时间不准确，影响追踪和展示。现有缓存使用 UUID 版本，预约不依赖 updatedAt，所以不按预约一致性缺陷升级严重程度。
- **建议**：更新实体仅携带可修改的业务字段，让数据库负责自动时间；或显式赋予一致时间源的当前 updatedAt，避免写回 createdAt。
- **补测**：独立 MySQL 库设置固定旧时间，调用实际事务 service 修改房间，断言 created_at 不变、updated_at 前进；回滚时字段与缓存保持原状。现有 H2 表没有等价 ON UPDATE 语义，不能拿它替代该断言。

### R5 / P3：首次登录密码错误的提示不准确

- **位置**：`src/utils/request.ts:66` 至第 74 行；`src/views/Login.vue:96`；`user-service/src/main/java/com/geek/smartoffice/service/impl/SysUserServiceImpl.java:29` 至第 32 行。
- **原因/触发**：未登录用户提交错误密码，后端正确返回 HTTP 401、“用户名或密码错误”；Axios 错误拦截器忽略已提取的后端 message，对所有 401 固定提示“登录已过期，请重新登录”。
- **证据**：当前登录调用、后端失败分支、前端错误分支构成完整静态路径；没有新增浏览器复现。现有 8 项前端测试不加载该拦截器，历史浏览器的无效 Token 测试也不是错误密码提示测试。
- **影响**：误导用户，但可以再次输入密码，未造成鉴权绕过或数据错误。
- **建议**：登录接口 401 展示认证失败原因；受保护接口 401 继续清除 Token、保留 redirect 并要求重新登录。
- **补测**：首次登录错误密码与已登录页面 Token 过期分别断言提示、Token 清理和跳转行为。

## 4. 待验证风险与已知设计限制

### 4.1 待验证风险（不计入上述 5 项）

| 事项 | 已知线索 | 仍缺少什么证据 / 建议后续验证 |
| --- | --- | --- |
| Relay 多实例、租约竞争、ACK 不确定窗口 | OutboxStore 有条件认领和 owner 校验；源码允许至少一次重试 | 精确暂停在发送确认后、SENT 更新前；两实例竞争及旧 owner 迟到更新。现有消息审计探针未启动成功，不能把其中计划的 12 路测试算通过 |
| 消费事务后半段失败 | 三表在同事务；已有测试覆盖通知写失败 | audit/receipt INSERT 失败的实际回滚和恢复；该审计探针中的预写断言未执行 |
| DLQ 耗尽、过期消息和人工重放 | Runtime 16 次重试；runbook 有人工定位说明 | 真实耗尽到 DLQ、保留期越界、按原 eventId 重放的完整演练；未确认已存在消息永久丢失 |
| 同 booking 的不同 eventId | Consumer 按 eventId 判重，不校验与 Outbox eventId 一一对应 | 正常 Outbox 对 aggregate/type 唯一，当前生产发布路径不会生成第二个创建事件；只有额外生产者、运维改写或 Broker 信任边界被破坏才可能到达，不报成普通用户可利用漏洞 |
| Redis 恢复交错 | 成功失效的版本条件成立；失败失效依赖 TTL | 慢加载超过 5 秒租期、仅写实例断连、提交后失效前崩溃等；未证明会破坏订单一致性 |
| Gateway 重建换 IP | `deploy/upgrade/nginx.conf:16` 是静态 upstream 主机名 | 需实际发生地址变化并检查 Nginx 解析/连接恢复；没有已发生 502 的证据 |
| 容量与资源耗尽 | 订单列表和导出取全量；登录注册无专门限流；LoginDTO 未设长度上限 | 大数据执行计划、堆占用、并发认证成本及部署暴露面。没有做攻击性压测，不声称已证实 DoS |
| 公网安全与依赖风险 | 当前部署绑定 loopback、使用演示账号，旧 compose 有历史弱口令/公开端口；完整安全插件扫描未成功 | 公网部署前独立处理账号、TLS、内部网络身份、依赖漏洞核对；不能把旧文件的条件性风险说成当前升级容器已暴露公网 |

### 4.2 已知设计限制（不冒充已完成，也不直接当 Bug）

- 单 Broker、单机 Nacos、单实例 Sentinel 规则；没有 HA、全局 QPS 配额或性能承诺。
- 缓存弱一致、失效失败靠 TTL；Bloom 的 `mightContain` 没有生产读路径调用，当前仍有初始化/写入成本，不能宣传“Bloom 已拦截缓存穿透”。穿透保护实际依赖空值缓存；可后续移除冗余 Bloom 链路。通用 RedisTemplate 及未被生产调用的 BookingTimeRule 也可择机整理。
- 用户服务远程检查仅覆盖新预约，其他接口依赖有效期内 JWT；未实现全局即时撤权、注销令牌、refresh token。演示可接受，权限变更有即时撤销需求时需另行设计。
- 无待确认预约状态，因此没有超时取消；无跨库写事务，不需要为简历新增 Seata。通知是数据库记录，没有短信/邮件投递。
- 前端有登录、注册、预约、我的预约和取消；没有完整管理员页面、通知 UI，表单仅支持同日且主题长度 32，后端支持跨天和更长主题。这是功能边界，不能把所有管理 API 描述成已有完整管理界面。
- V1 是需先核对列/索引的一次性 SQL；没有自动迁移编排或部分成功后的自动修复。真实迁移脚本用合成旧 schema 验证 V1，不代表原业务整库拆分迁移已验收。
- 历史验收日志、探针和浏览器截图主要位于 ignored 或本机目录；它们是本机证据，不是任何克隆者都能直接获得的 CI 历史。

## 5. 测试有效性与证据复核

| 测试/材料 | 可以支持的结论 | 不可以支持的结论 |
| --- | --- | --- |
| BookingConcurrencyTest / ConcurrentIdempotencyTest | Spring 事务服务、H2 SQL、并发线程；首次 miss 后 barrier 能制造针对性竞态 | 不能单独代替 MySQL 隔离/锁行为。数学工具 BookingTimeRule 的 3 个测试也不能冒充生产冲突 SQL 测试 |
| BookingServiceImplTest 的提交前后顺序测试 | Mock 调用顺序符合“内部调用返回后再 unlock” | 没有直接观察实际 commit 事件，不能只凭测试名证明真实事务先提交 |
| 取消、删除竞争测试 | 至多一个有效预约、逻辑删除保护等不变量 | 单次调度未必覆盖两种锁先后；后续可分别用 barrier 固定胜序 |
| RoomCacheTest 的 FakeStore | 缓存编排、异常降级及禁止重复执行 loader | 不证明真实 Lua 原子性、TTL 或 Redisson 所有权；这些另有历史 Redis 原脚本/部署 JAR 证据 |
| MessagingReliabilityTest 的 5 项 | 订单/Outbox 回滚、同组重复、通知失败、发送重试、人工置过期租约恢复 | 不覆盖组切换、多 Relay 竞争、进程硬崩溃、DLQ 耗尽、真实 ACK 丢失 |
| 新组 FIRST_OFFSET 历史探针 | observer consumer 能读到之前发送的真实事件 | 不等于当前业务 consumer 换组后可完成三表写入，不能排除 R2 |
| 本次 MessagingAuditProbe | 只证明探针编译产物存在且尝试启动 | `messaging-probe.log` 无任何业务 PASS；缺 SpringDocConfigProperties 即退出，所有计划断言未执行 |
| 前端 8 项 Node 测试 | 真实 TS 提交/取消控制器的 pending、key 重用、失败状态，以及 API 配置 | 不覆盖真实 Vue DOM、Axios 的全部错误分支或跨浏览器行为 |
| 历史 Edge 桌面/手机 9 项 | 真实后端注册、登录、双击、取消、409、失效 Token、布局 | 409 后重试不是“服务端已成功但响应丢失”；该端到端故障仍未验证 |
| 历史 MySQL 迁移 7 项与真实故障脚本 | 指定 fixture、实际服务版本和当时故障恢复路径 | 不代表任意旧数据库或所有事故都可恢复；finally 也不保证宿主被强杀后执行 |

历史 API 记录 `real_20261014T140145_660669fa` 为 17 PASS，167 HTTP/17 SQL/16 Redis 命令；迁移 7 项、Redis Lua 21 项、四类 MySQL/Redis/用户服务故障各 5 阶段与浏览器报告已有对应记录。这里保留的是此前证据的核对结果，不是 2026-10-16 新执行结果。

现有测试有实质价值，没有因使用 Mock/H2 就否定全部测试，也没有因测试数为 98 就推定业务正确。对本次新增问题，原测试确实缺少相应边界或场景。

## 6. 功能完成度矩阵

“已正确实现且验证”限定为列出的测试范围；涉及真实中间件的验证均指 2026-10-14 历史验收，本次收尾没有重跑。

| 功能 | 完成度 | 依据与边界 |
| --- | --- | --- |
| 时间段冲突、相邻预约、房间事务行锁 | 已正确实现且验证 | 生产 SQL、事务代理、H2 并发及历史真实 MySQL；无容量/HA 保证 |
| requestId 幂等、参数冲突、大小写与跨用户 | 已正确实现且验证 | 唯一键、flushCache、锁后重查、并发测试及真实 API/迁移；无 key 不承诺重放 |
| 取消 CAS、所有权、重复取消及替代预约 | 已正确实现且验证 | 服务授权、条件更新、真实取消/竞争；不涉及库存恢复 |
| 管理员房间管理 API | 部分实现 | 主流程可用；R1、R4 待修复，未提供完整管理 UI |
| Redisson 辅助锁与 Redis 故障下数据库兜底 | 已正确实现且验证 | watchdog、线程所有权、历史 Redis 停机并发验收 |
| 详情缓存、空值、版本 Lua、提交后失效 | 已正确实现且验证 | 已测成功失效/旧 token 拒绝及降级；多实例复杂交错验证不足 |
| Bloom 读路径穿透拦截 | 未实现 | 保留了构建/写入，当前无生产 mightContain 调用，不应列作实际收益 |
| 订单与 Outbox 原子提交 | 已正确实现且验证 | 同库事务、MANDATORY、回滚测试和真实故障注入 |
| Outbox 重试、租约及 owner 条件更新 | 已实现但验证不充分 | 基础重试与过期恢复已验证；精确崩溃窗口、多实例交错未验证 |
| 同一消费组的消息重复、异常重试 | 已正确实现且验证 | 同事务三表、唯一约束、历史 Broker 重复/失败/重启证据 |
| 业务消费组更名后历史事件重放 | 部分实现 | FIRST_OFFSET 已配，R2 的业务去重冲突未解决；不能宣传任意换组无损恢复 |
| 消息状态追踪及人工排查 | 部分实现 | Outbox 字段、管理员查看、日志和 runbook；告警、长期归档、DLQ 实操闭环不足 |
| DLQ 自动回灌 | 未实现 | 当前只有人工检查说明 |
| JWT、BCrypt、角色及数据所有权 | 已正确实现且验证 | 现有安全/API 测试及真实验收；登录提示有 R5，不支持即时全局撤权 |
| Nacos 注册发现、Gateway 路由、Feign 校验 | 已正确实现且验证 | 历史 cloud 服务注册/路由/远程调用及用户服务故障；Nacos 自身失效未专项验证 |
| Sentinel 预约限流 | 已正确实现且验证 | 单实例 20 QPS，真实 burst 命中对应限流文案；非集群、非熔断 |
| 完整本地 Compose 构建和通信 | 已正确实现且验证 | 历史十容器验收与持久卷；不代表当前 Engine 正运行或生产部署能力 |
| 所有验收工具的本地目标保护 | 部分实现 | 多数故障脚本已有保护，但 R3 的独立入口缺失 |
| Vue 登录、预约、取消、重复提交与异常提示 | 已正确实现且验证 | 8 单测与历史 Edge 9 项；R5 文案问题，真实丢响应恢复未测 |
| CSV 导出与管理员操作权限 | 已正确实现且验证 | 过滤器/方法权限、导出及公式转义；全量导出容量未验证 |
| 原业务整库迁移、跨时区产品行为 | 已实现部分准备但验证不充分 | 有 SQL/运行说明；没有原库全量迁移结果及跨时区业务约定 |
| 待确认预约超时取消、Seata 跨库事务 | 无实际必要，不建议引入 | 当前没有对应业务状态或跨库写需求 |
| Nacos 配置中心、短信/邮件、Sentinel 集群 | 未实现；当前展示不必为技术数量引入 | 简历应准确区分实际功能与未来选项 |

## 7. 总体评价、简历建议与修复顺序

### 总体评价

| 维度 | 结论 |
| --- | --- |
| 业务完整性 | 主要用户预约闭环可运行；管理 API 基本具备，边界输入和更新时间有缺陷，管理界面与通知展示未完整实现 |
| 并发安全 | 时间区间模型、房间行锁、事务、唯一键与取消 CAS 的职责清楚；未发现成立的重复预约路径，不能延伸为任意分布式故障都正确 |
| 数据一致性 | 预约及同组消费的本地事务设计成立；展示缓存是弱一致；消费组迁移的幂等语义需修正 |
| 安全性 | 常规认证/角色/所有权校验有代码与测试；未成立严重绕过发现；公网防护及独立安全扫描覆盖仍不充分，验收工具目标保护必须补齐后再作安全承诺 |
| 可维护性 | 服务拆分克制、版本线匹配、文档和失败记录较充分；迁移/消息运维仍依赖人工，部分冗余代码及外置测试资产可继续整理 |
| 面试展示价值 | 适合作为 Java 后端实习核心项目，尤其适合解释时间区间互斥、幂等、事务消息和故障恢复边界；不宜包装成生产级高并发平台 |

### 投递前哪些必须处理

**没有发现要求暂停投递的已证实 P0/P1。** 修复优先级应与准备对外宣称、演示的能力对应，不能把所有小问题都升级为求职阻断项。

1. **投递前必须纠正能力描述**：不得写“消息零丢失/exactly-once”“任意消费组切换无损恢复”“生产 HA”“已实现全局撤权、集群限流、完整管理前端、Bloom 拦截”。测试数量和历史验收必须保留范围及日期。
2. **下一修复阶段优先完成 R1、R2、R3**：R1 是容易现场复现的 API 边界问题；R2 应在强调“消息恢复方案完整”或演示消费组迁移前修复、补测；R3 应在继续运行相关验收工具或向他人推荐其安全保证前修复。保持当前固定组的本地演示并不需要先实现 HA 或 DLQ 自动平台。
3. **R4、R5 可以随后修复**：更新时间和登录文案影响工程完成度，但不推翻核心预约设计。大包优化、分页/流式导出、冗余 Bloom 清理、测试工具移入可移植目录也可列后续迭代。

若简历只陈述已经验证的预约、同组消息幂等和本地微服务链路，当前版本可以投递；若要写“完整可靠性闭环”，必须先补齐 R2 和对应恢复验证，不能用文档措辞代替修复。

### 可以真实写入简历的亮点

- 依据半开时间区间设计预约冲突校验，以 MySQL 房间行锁和 READ_COMMITTED 保证同房间并发串行判定，Redisson 辅助削峰并支持 Redis 故障降级。
- 使用用户/requestId 唯一键、锁前后复查及 MyBatis 会话缓存处理实现请求幂等，以条件更新实现重复取消无副作用。
- 使用 Transactional Outbox 与 RocketMQ 实现预约事件至少一次投递；在固定消费组内，通过数据库唯一约束及通知/审计/receipt 同事务实现重复消费幂等，验证过发送失败、消费异常及正常容器重启。
- 使用 Redis 详情缓存、空值 TTL、UUID 版本和 Lua 条件回填；明确成功失效的旧回填防护与失效失败时 TTL 收敛的区别。
- 拆分用户服务、预约服务与 Gateway，通过 Nacos 发现、OpenFeign 当前用户校验及 Sentinel 单实例限流形成可运行链路，并有真实中间件的本地验收记录。

### 应准备回答的面试追问

为什么是时间区间而不是库存？为什么 Redis 锁失效后仍不重复预约？READ_COMMITTED 与 MyBatis 一级缓存分别解决什么？取消与重新预约谁先获得锁会怎样？SEND_OK 后进程退出为何仍要重发？业务幂等键为何不应随消费组更名变化？缓存失效失败为什么不能保证立即一致？新组 observer 测试为什么不能证明业务消费成功？这 98 项测试中哪些是 Mock、H2、HTTP，哪些真实中间件证据来自另外的脚本？

能解释这些边界比堆叠更多组件更有说服力。

## 8. 审计材料、失败记录与完成状态

### 已利用的独立结果

- `logs/code-review/booking-cache-review.md`：预约/缓存/SQL 审查完成记录；两个房间问题的探针结果已保留。
- `logs/code-review/frontend-ops-review.md`：前端/部署/工具边界及历史证据核对；包含隔离目标检查测试。
- 消息任务在中断前留下 `MessagingAuditProbe.java`、编译产物与 `messaging-probe.log`，**没有完成独立最终报告，也没有成功的业务断言日志**。本报告仅据已读源码/DDL确认 R2，将其他拟验证消息场景保留为覆盖缺口，没有重新进行完整扫描或执行探针。

可复核的已执行命令包括：

```powershell
# 以下是已有执行记录，不是本次收尾重新运行的命令
& 'D:/apache-maven-3.9.4-bin/apache-maven-3.9.4/bin/mvn.cmd' -B -ntp -o verify
npm test
npm run build
& 'C:/Program Files/Java/jdk-17/bin/java.exe' '@logs/code-review/booking-room-readonly.args'
node --experimental-test-module-mocks --test logs/code-review/remote-probe-boundary.test.mjs
```

房间探针因 Windows 沙箱内 Java 类路径访问受限，曾失败，后在标准批准权限下退出 0；没有修改系统访问权限。消息探针的现存失败日志单独保留，不计入成功。Node 工具测试完全拦截进程/网络/历史报告写入，不是真实远端访问测试。上述日志及临时探针在 ignored `logs/code-review/`，不作为应用新增功能。

### 扩展安全扫描的限制

曾尝试运行扩展自动化安全扫描，但未完成，也未产生可复核的扫描结果。因此不将其计入通过项。本报告基于已有源码审查和实际测试记录，不能视为完整安全扫描已通过。

### 审计阶段完成记录（修复前）

2026-10-16：报告已补齐问题清单、分级、触发路径、证据强度、修复/补测建议、风险与设计限制、测试有效性、功能矩阵和简历评估。最终计数 **5 项：P0=0、P1=0、P2=3、P3=2**。三个重点中，设备长度与更新时间有成功隔离探针；消费组切换是静态确认的条件性缺陷，运行复现尚未完成。

本次仅完善此审计报告，没有修改核心代码或把问题标记为已修复，没有重复完整测试或全项目扫描。审计到此结束，后续修复由用户决定。

## 9. 五项修复与最终回归（2026-10-16）

本阶段按用户后续授权实施最小修复，不扩展审计范围或新增框架；没有生产数据库迁移，没有删除业务数据或数据卷。以下是当前修复后的状态，前文的问题等级保留用于追踪原发现。

| 原问题 | 当前修改与证据 | 尚未验证的边界 |
| --- | --- | --- |
| R1 / P2 | `MeetingRoomServiceImpl.java:127` 对最终 JSON 按 code point 计数，超过 500 返回 HTTP 400。新增合法边界保存、超限创建/更新拒绝、引号/多项/非 BMP、数据库及缓存不变测试；定向与完整 Maven 通过 | 真实 MySQL utf8mb4 存储与 HTTP 边界本轮未执行 |
| R2 / P2 | `BookingEventConsumer.java:43` 在原订单行锁和事务内核对/补齐事件通知及审计，再幂等补记各消费组 receipt；全局事件唯一约束和组 receipt 主键均保留。新测试 7/7：新旧组重放、两组并发、已有部分记录、内容冲突、receipt 失败回滚和恢复 | 新测试是 H2 SQL/事务，不是真实 Broker offset/确认或 MySQL 锁测试；新组真实历史重放仍未复验 |
| R3 / P2 | 两个验收入口拒绝非本地 pipe/socket，固定 `--host`、Compose 项目和经核验的完整容器 ID，检查镜像与容器身份；对真实脚本入口做无网络/无 daemon 的隔离回归 | Docker CLI 元数据核对与容器实际执行分开；Engine 不可用，当前版本探针未在真实容器执行 |
| R4 / P3 | `MeetingRoomServiceImpl.java:78` 不回写创建时间，更新时间取注入 Clock 的当前整秒时间；实际事务更新/回滚测试通过 | DATETIME(0) 同秒更新可显示相同值；本轮真实 MySQL 更新未执行 |
| R5 / P3 | `src/utils/request.ts:22` 仅 POST 登录凭据错误走登录错误分支；受保护请求 Token 失效仍清 Token/跳转。新增真实 Axios 请求拦截器测试 7/7，完整前端 15/15、类型检查与构建成功 | HTTP/browser 外围在 Node 测试中受控，本轮没有新的真实后端浏览器结果 |

**R2 的证据已增强，但没有重写历史**：旧 `MessagingAuditProbe` 在 Spring 启动时失败的记录仍保持失败。本轮使用现有可启动测试配置编写 `ConsumerGroupIdempotencyTest`，修复前确实出现全局唯一键冲突，修复后业务断言全部通过；这确认事务层修复有效，不证明 Broker 消费组切换、重试耗尽或跨进程恢复已经全部验收。

后端定向 RED 为 28 tests / 7 failures / 4 errors，GREEN 为 33/33；完整 Maven `verify` 为 **110/110**（shared 21、user-service 15、backend 68、gateway 6），恢复后的 22:41:52 再次成功；前端完整 **15/15**，`vue-tsc + Vite` 成功，既有包体/PURE 提示保留。探针隔离回归、只读复核及保存状态以 [final-fix-verification.md](final-fix-verification.md) 的最终记录为准。

最终探针边界 **57/57**（22:45:06），另有 5 项真实 CLI 元数据只读核对；中途 OS 临时目录执行策略导致的测试失败已记录，改用仓库隔离 fixture 后通过，没有更改系统策略。独立只读复核对五项修改及回归未发现新增 critical/important/minor。原审计 **P0=0、P1=0、P2=3、P3=2** 的五项均已完成代码修复；原数量不抹去，真实环境补验状态也不改记完成。

22:39 本机 `desktop-linux` endpoint 为 `npipe:////./pipe/dockerDesktopLinuxEngine`，但 Engine 管道不存在。因此新版本真实 MySQL、Redis、RocketMQ、Nacos、容器与浏览器复验全部记为 **NOT_EXECUTED / 环境不可用**，不把 2026-10-14 的旧镜像验收结果记为本次修复后通过。

### 修复后的功能矩阵增量

| 能力 | 当前结论 |
| --- | --- |
| 房间设备边界与更新时间 | 已修复，服务/事务/API 隔离回归通过；真实 MySQL 待复验 |
| 跨消费组的业务事件幂等 | 已实现并通过 H2 并发/回滚回归；真实 Broker/两业务组验收仍不足 |
| 两个探针的本地目标保护 | 已实现，隔离边界验证与 CLI 元数据核对；实际容器执行待复验 |
| 登录错误与 Token 失效提示 | 已修复并通过真实 Axios 拦截器测试，浏览器联调待复验 |
| 第 6 章其他能力及第 4 章设计限制 | 没有扩大实现或证据范围，原边界继续有效 |

### 学习与简历准备结论

五项缺陷修复与工程回归已完成，本轮开发阶段可以正式结束；真实环境增量验收仍需 Docker 恢复后进行。这两件事分开记录：项目可以进入学习、演示整理和 Java 后端实习简历准备，不能写成“最终版本所有真实中间件回归全绿”或“生产级零丢失”。

可新增的准确描述是：**将消息业务幂等键与消费组 receipt 区分，在数据库唯一约束及本地事务下处理跨组重放、旧记录一致性与失败回滚，并补充并发及异常回归测试**。真实 Broker 部分只引用有日期和部署版本支撑的历史验收；当前跨组修复注明 H2 事务测试。无需为了简历继续增加组件、HA 框架或未定义的业务功能。
