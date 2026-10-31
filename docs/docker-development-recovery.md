# SmartOffice 本地 Docker / IDEA 开发环境恢复报告

> **D 盘迁移后日常开发：** 使用 [2026-10-21 下午续接记录](docker-development-recovery-d-drive-20261021.md) 的目录和命令。下面的 10 月 20 日验收数保留为历史结果。

> **2026-10-21 上午快照：** [上午恢复检查与阻塞报告](docker-development-recovery-20261021.md)。上午完整Maven118项通过，但当时Desktop启动被socket错误阻塞；下午实际恢复结果见上方D盘续接记录。下面是10月20日历史实际结果。

日期：2026-10-20，Asia/Shanghai。工作区：`C:\Users\11707\Desktop\smartoffice-rebuilt`。

## 1. 最终状态与恢复边界

**全新、独立的本地中间件和 Java 开发环境已恢复。** 六个中间件容器健康，三个 Java JAR 已使用真实 MySQL、Redis、Nacos、RocketMQ 启动并完成接口验收。完整 Maven 验证为 **118/118**，真实环境验收脚本为 **90/90**，二者分别记录，不能互相代替。**Knife4j 浏览器显示和授权输入已验证，但“发送”后的 GET 请求未验证成功，不能宣称全部验收完成。** 详见第 5 节。

本次是用户批准的“方案 1”：**未恢复历史业务数据**。新项目 `smartoffice-host-dev`、新命名卷、新私有凭据与旧环境隔离。保留旧 Docker 盘、原 `.env.upgrade`、本机 MySQL、PetTravel 以及用户未提交修改。未执行 prune、Factory Reset、down -v、删除数据或远程推送。

验收期间创建的专用账号、会议室、已取消预约和消息记录均保留在新开发库；不是生产或历史数据。Redis 探针使用唯一键和 60 秒 TTL。

## 2. Docker 实际情况、镜像恢复及旧盘保护

### 2.1 初始检查

实际执行：`docker version`、`docker info`、`docker context ls`、`docker compose version`、`docker ps -a`、`docker images`、`docker volume ls`、`docker network ls`。

- 正常权限下 Docker Desktop 4.87.0 / Engine 29.7.2 / Compose 5.4.0 正常；`desktop-linux` 指向 `npipe:////./pipe/dockerDesktopLinuxEngine`。工具受限沙箱的 permission denied 不代表 Engine 停止。
- 初始为 **0 容器、0 数据卷、3 个默认网络**；剩余镜像只有 mysql:8.4、redis:7.4-alpine、testcontainers/ryuk:0.6.0。没有直接证据证明历史资源的具体删除过程，不能把“重置后可能丢失”写成已取证原因。
- 当前无法开发的直接原因是所需容器/卷不存在、原版本镜像不齐，以及原 Compose 面向容器内 Java，缺少宿主 IDEA 的连接映射；无需重装 Docker。

### 2.2 镜像网络问题与实际修复

原 `docker pull mysql:8.0` 和同 digest 官方缓存尝试均未完成；直接访问 Registry/auth 15 秒超时。Windows 已有代理 127.0.0.1:7897 可以访问官方鉴权和 manifest，但部分 CDN 层持续卡住或产生 TLS 错误。没有修改全局代理、Docker daemon、镜像源或关闭证书校验。

恢复方式：经现有代理从 **Docker 官方 Registry** 取得原 manifest/config/layers，分段限次重试，校验 SHA-256、解压 diffID 和加载后 RootFS，再 `docker load`。Nacos 最后缺片使用 Python/OpenSSL 的标准证书校验通路完成；没有换不兼容版本。已有 Redis 镜像与所需标签官方 digest 相同，核验后本地 tag。

| 原镜像标签 | 本轮结果 | 官方 index digest |
| --- | --- | --- |
| mysql:8.0 | 已恢复，实际 8.0.46 | `sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b` |
| redis:7-alpine | 已恢复，实际 7.4.11 | `sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499` |
| apache/rocketmq:5.3.1 | 已恢复 | `sha256:d2b231c1b9204129e4f4dd65ec1521c81b8d6826e5f1fe8daa521dab0db5bf16` |
| nacos/nacos-server:v3.0.3 | 已恢复 | `sha256:a223937902d4292e49ce6bcca8c9d47d29d508075b7f7c6ba98e1a34ff9c3f3b` |

随后实际执行原 MQ Dockerfile 的 `compose ... build namesrv`，成功生成 namesrv/broker 共用的 `smartoffice-upgrade-rocketmq:5.3.1`，没有构建或启动 Java/前端容器。

详细官方通路、CLI 和逐层证据在 ignored 的 `logs/docker-recovery-20261020/official-image-recovery-report.md`、`image-recovery-result.json`、`official-recovery-reproduction.json`、`rocketmq-build.log`。本轮没有遗留镜像下载进程；网络间歇问题可能在未来重新拉取镜像时出现，本次所需镜像已缓存。

### 2.3 旧盘未使用、未修改

- 旧盘：`D:\DockerData\DockerDesktopWSL\disk\docker_data.vhdx`，12,149,850,112 字节。
- `Get-DiskImage -ImagePath ...` 返回 **Attached=false**；Desktop 的 `CustomWslDistroDir` 为 `C:\Users\11707\AppData\Local\Docker\wsl`。
- 对旧盘只读计算的前后完整 SHA256 一致：`5A602D92B726B9091E9B8253B25A9CDF0F6C4F27EBB6CAF5C7602B573D750CDB`。长度、最后修改时间也一致。证据：`old-vhd-before.json`、`old-vhd-after.json`。
- 一次中间比较因 JSON 日期自动解析导致类型不一致而误报，已改为 UTC ticks 比较，并重新计算完整哈希确认未变；失败比较记录保留，不能解读成旧盘实际修改。
- 旧盘没有挂载、导入、移动、覆盖或格式化。本机 `D:\mysql data\MySQL Server 8.0\Data\smartoffice` 也没有连接或修改。

不需要为启动当前新环境移动旧盘。将来恢复历史数据时，先确认未挂载，在空间足够的独立目录复制整个 VHDX，比较源/副本 SHA256，再针对副本检查或导出；不要直接替换当前 Docker 活动盘。本轮没有擅自创建 12 GB 备份或声称旧数据已丢失。

## 3. 已恢复的中间件和数据库

使用文件：原 `deploy/docker-compose.upgrade.yml` + 新 `deploy/docker-compose.host-dev.yml` + ignored `deploy/.env.host-dev`。项目名固定 `smartoffice-host-dev`。

| Compose 服务 | Windows 本地地址 | 本轮验收 |
| --- | --- | --- |
| mysql-booking | 127.0.0.1:13306 | running / healthy，重启 0，MySQL 8.0.46 |
| mysql-user | 127.0.0.1:13307 | running / healthy，重启 0，MySQL 8.0.46 |
| redis | 127.0.0.1:16379 | running / healthy，重启 0，认证 PING/SET NX EX/GET 通过 |
| nacos | HTTP 18848；gRPC 19848/19849；console http://127.0.0.1:18880 | running / healthy，重启 0，三个服务实例注册和发现通过 |
| namesrv | 127.0.0.1:19876 | running / healthy，重启 0 |
| broker | 127.0.0.1:10911、10909、10912 | running / healthy，重启 0，真实消息发送/消费通过 |

新网络 `smartoffice-host-dev_default`，新卷前缀均为 `smartoffice-host-dev_`：booking_mysql_data、user_mysql_data、redis_data、namesrv_logs、broker_logs、broker_store、nacos_data、nacos_logs，共 8 个。没有复用或删除其他项目卷。

MySQL 是**全新初始化的开发库**。复用原 Compose 的只读初始化挂载：

- booking：`backend/booking-service/src/main/resources/schema.sql`、`message-schema.sql`、`data.sql`。
- user：`backend/user-service/src/main/resources/schema.sql`、`data.sql`。

实际查询确认：user 库 sys_user；booking 库 meeting_room、booking_order、booking_outbox、message_consumption、booking_notification、business_event_audit，合计 7 张业务表，均为 InnoDB。预约 requestId 联合唯一索引、消息通知/审计 event_id 唯一索引、消费记录联合主键、Outbox 认领/重试索引均存在。equipment 为 varchar(500)，预约 start/end_time 为 datetime(0)，Outbox available_at 等为 datetime(6)。没有重复导入初始化 SQL。

## 4. 宿主 Java、配置和最小修复

新增 `deploy/upgrade/Prepare-HostDevelopment.ps1`，从新私有 env 生成三个 ignored 配置：

- `backend/.env.host-dev-user.properties`
- `backend/.env.host-dev-booking.properties`
- `backend/.env.host-dev-gateway.properties`

脚本使用 CreateNew，已存在的配置保留不覆盖；重复运行后哈希不变。三个服务均监听 loopback，cloud profile 注册 Nacos；Gateway 使用发现路由，booking 的 Feign URL 留空以使用 Nacos，真实 MQ 开启。数据库 init 为 never，不改业务 SQL、服务名、artifactId 或 API 契约。

原 Broker 公告 bridge IP，Windows 客户端不可达；独立覆盖在容器临时文件追加 brokerIP1=127.0.0.1，原 broker.conf 不覆盖。**此覆盖只适用于 Windows 宿主 Java；只启动六个中间件，不能同时用它启动 Java/前端容器。**

为使现有 Knife4j 真正可用，仅作两项最小修复：

1. shared 的 SecurityConfig 新增默认 false 的 `smartoffice.docs.public` 开关，只在显式启用时允许文档 GET。本机 booking 私有配置开启；业务接口、文档 POST、相似路径仍需认证。新增 DocsSecurityTest，RED 复现后 GREEN 5/5。
2. 真正请求 `/v3/api-docs` 时发现 Knife4j 4.5.0 enhancement customizer 调用与 Springdoc 2.8.8 不兼容，抛出 `NoSuchMethodError: SpringDocConfigProperties.getGroupConfigs()`，返回 500。通过真实 JAR 条件确认后，仅将 booking application.yml 的 `knife4j.enable` 改为 false，关闭不兼容 enhancement，保留原静态 UI 和标准 Springdoc；不升级依赖。BookingOpenApiTest 使用真实 Springdoc/应用上下文，RED 3 中 1 失败，GREEN 3/3；其数据库/外部依赖隔离，不冒称真实中间件测试。

## 5. 实际验收及失败记录

### 5.1 Maven 与真实环境分开计数

完整命令：在 backend，JDK 17，执行 `D:/apache-maven-3.9.4-bin/apache-maven-3.9.4/bin/mvn.cmd -B -ntp -o verify`。最终 **22:36:29 BUILD SUCCESS，约 1 分 1 秒，118 项，0 failures/errors/skipped**：shared 26、user-service 15、booking-service 71、gateway 6。三份最终 JAR 已打包。

真实验收命令（仓库根）：`node logs/docker-recovery-20261020/verify-host.mjs`，正常权限访问本机 Docker/HTTP。最终 runId=`recovery_b7e76f2a764d4130b12e`，**22:39:11—22:39:37，90/90，PASSED**。固定本机 context/endpoint，检查 project/service 标签和完整容器 ID；Token/密码只在内存。不是 H2、Mock 或旧日期验收结果。

| 检查 / 实际接口 | 结果与数据库证据 |
| --- | --- |
| 三 JAR 健康、Nacos | user 8082、booking 8081、gateway 8080 启动；三个实例 healthy，注册地址均 127.0.0.1；验收前后六容器重启 0 |
| POST /api/users/register、/api/users/login、GET /api/users/me | 新专用用户/管理员注册、登录及身份查询通过 |
| GET /api/rooms/list | 登录后 200；原开发 demo 会议室可查询 |
| API 权限 | 匿名 GET /api/rooms/list 为 401；普通用户 POST /api/rooms 为 403 |
| 管理员 POST /api/rooms，capacity=31 | HTTP 400，业务 code=400；SQL 确认没有写入该专用测试房间 |
| 管理员 POST /api/rooms，capacity=30 | HTTP 200；新专用房间 ID=5 |
| 预约、重复 requestId | 通过实际 Gateway → booking → Nacos/Feign → user → MySQL；返回相同订单 ID=1，SQL 确认一条订单 |
| 真实 RocketMQ | Outbox 一条 SENT 且有 broker_message_id；消费记录、通知、审计最终各一条，轮询等待到实际完成 |
| 同一预约取消两次 | 两次成功，数据库状态为 2（已取消） |
| Redis | 使用新密码实际 PING、唯一键 SET NX EX60、GET 通过 |
| Knife4j HTTP | /doc.html、/v3/api-docs、/v3/api-docs/swagger-config 和抽样的一项 webjars CSS 均 200；OpenAPI capacity.maximum=30；未认证业务依然 401 |
| Knife4j 浏览器 | 实际 Edge 渲染正常，API 树、listRooms 调试面板、Authorize 输入及失焦已验证；点击“发送”后 20 秒内未观察到目标 GET/响应，整体 passed=false，不能判定授权已在请求生效 |

capacity31 的**数值校验正确**，但响应文案是用户已有 `@Max(30)` 的“会议室容量最多为100”。本轮保留其修改，没有擅自改成其他上限。后续可将该 message 改为 30；当前文案不能当作数值约束为 100。

新账号：管理员 `dev_admin_20261020`（ID3），普通用户 `dev_user_20261020`（ID4）。独立随机密码只在 ignored `deploy/.env.host-dev` 的 DEV_ADMIN_PASSWORD / DEV_USER_PASSWORD；不要复制到报告、终端历史或 Git。通过实际注册生成 BCrypt；管理员提升只针对刚返回的专用用户名/ID/USER 记录，未覆盖已有账号密码。原初始化脚本的 demo 账号仍保留，练习优先用这两个新账号。

浏览器实际命令：`node C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/knife4j-host-check.mjs --execute-readonly`。Browser 插件不可用，复用已有 Playwright 与已安装 Edge，没有安装依赖或注入 Authorization 请求头。通过真实 Authorize 输入登录 Token，在真实 UI 将 URL 改为固定 loopback 后点击发送；仍未观测到目标请求。控制台错误/警告、资源网络失败、外部请求均为 0；没有明显 UI 校验提示。当前证据无法区分 UI 兼容性问题与自动化交互未触发，不直接断言业务接口故障。已进行有限定位，不无限重试；仍需手工复核该按钮或后续针对 UI 定位。浏览器已关闭。

脱敏 `report.json`、DOM、截图位于 `C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/knife4j-host-evidence/`；报告另复制为本项目 ignored 的 `knife4j-browser-report.json`。该浏览器结果独立于 90 项后台 HTTP/SQL 验收，不计入通过数。

### 5.2 失败不能记成通过

- 早期完整 Maven 115/115 是修复 OpenAPI 之前的基线；不代替最终 118。
- 一次最终打包因旧测试 booking JAR 仍占用 Windows 文件而失败。普通受限沙箱看不见有权限启动的 Java 进程，不能据此声称服务崩溃。正常权限下按本任务 PID、Java 名称和启动 UTC ticks 确认后停止本任务进程，再执行完整 verify 成功。失败日志保留。
- 第一轮真实验收因实际 OpenAPI 500 失败；最小修复后第二轮在探针 HTML 解析失败。页面实际使用合法的无引号 href，探针原先只接受带引号属性；修正解析同时接受两者后重跑，保留原静态资源 HTTP200 断言，最终 90 项通过。
- 浏览器在受限沙箱启动 Edge 时失败属于本地运行权限问题，不能算页面通过；浏览器结果单独记录。

### 5.3 证据索引

除另行注明外，均位于 ignored `logs/docker-recovery-20261020/`，原失败与最终成功结果同时保留：

| 证据 | 内容 |
| --- | --- |
| redis-start.log、mysql-start.log、rocketmq-start.log、nacos-start.log | 本轮启动命令输出 |
| infra-mysql-booking-mysql-user-redis-nacos-namesrv-broker.json | 六容器健康/重启，实际 MySQL 表、索引和字段精度查询 |
| maven-final-verify.log | Windows JAR 占用导致的失败，不能算通过 |
| maven-final-verify-retry.log、surefire-final-summary.json | 最终 118/118 和打包成功 |
| result.recovery_15bd5901a449494aa5c8.json | 真实 OpenAPI 500 失败 |
| result.recovery_cc63244eced844a19caa.json | 探针 HTML 属性解析失败 |
| result.json、result.recovery_b7e76f2a764d4130b12e.json | 最终 90/90、HTTP结果、SQL与保留 fixture |
| knife4j-security-red.log / green.log | 文档鉴权回归 RED/GREEN |
| ../recovery/booking-openapi-red.log / green.log | OpenAPI 兼容性回归 RED/GREEN |
| host-processes.json、user/booking/gateway.out.log、对应 err.log | 最终 JAR 进程和启动日志（不输出完整配置） |
| old-vhd-before.json、old-vhd-after.json | 旧盘前后完整哈希/元数据不变 |
| credential-isolation.json、source-hygiene-final.json | 最终 217 个文本文件与 18 个新旧秘密值比对，无匹配；忽略规则通过，不含秘密值 |
| knife4j-browser-report.json | 浏览器显示/授权输入的通过证据与 GET 未发出的失败范围 |
| host-cleanup.json | 22:50 确认本次三个 JAR 停止、8080/8081/8082 空闲；中间件保留 |

## 6. 每日 PowerShell 启动

从仓库根执行，Docker Desktop 开启 Linux Engine。当前镜像已缓存，不需要每天 pull/build。

```powershell
Set-Location D:\A-project\smartoffice-rebuilt

docker --context desktop-linux version
$taskDockerEndpoint = docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}'
if ($taskDockerEndpoint -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
    throw '不是预期的本机 Linux Engine，停止操作。'
}

docker --context desktop-linux compose -p smartoffice-host-dev --env-file deploy/.env.host-dev `
  -f deploy/docker-compose.upgrade.yml -f deploy/docker-compose.host-dev.yml config -q

# 只启动以下六个中间件；不要省略服务列表启动整套 Java 容器
docker --context desktop-linux compose -p smartoffice-host-dev --env-file deploy/.env.host-dev `
  -f deploy/docker-compose.upgrade.yml -f deploy/docker-compose.host-dev.yml `
  up -d --no-build --wait --wait-timeout 360 mysql-booking mysql-user redis nacos namesrv broker

docker --context desktop-linux compose -p smartoffice-host-dev --env-file deploy/.env.host-dev `
  -f deploy/docker-compose.upgrade.yml -f deploy/docker-compose.host-dev.yml ps

# 只补齐缺失的私有配置；已有文件绝不覆盖
./deploy/upgrade/Prepare-HostDevelopment.ps1
```

不要重新生成 env 密码后期待既有 MySQL 卷密码自动同步；也不要重跑初始化 SQL。以后需要主动调整私有配置时，先核对当前卷和连接信息，再逐项修改。

可选构建：**先停止 IDEA/JAR 服务，避免 Windows JAR 文件占用**。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location backend
mvn -B -ntp verify
```

命令行运行可替代 IDEA，三个终端的工作目录都为 backend，每终端一条，不要与 IDEA 重复启动：

```powershell
java -jar user-service/target/smartoffice-user-service-1.0.0.jar --spring.config.additional-location=file:./.env.host-dev-user.properties
java -jar booking-service/target/smartoffice-booking-service-1.0.0.jar --spring.config.additional-location=file:./.env.host-dev-booking.properties
java -jar gateway/target/smartoffice-gateway-1.0.0.jar --spring.config.additional-location=file:./.env.host-dev-gateway.properties
```

停止 Java 用对应终端 Ctrl+C 或 IDEA Stop。停止基础设施但保留数据，从仓库根：

```powershell
docker --context desktop-linux compose -p smartoffice-host-dev --env-file deploy/.env.host-dev `
  -f deploy/docker-compose.upgrade.yml -f deploy/docker-compose.host-dev.yml `
  stop mysql-booking mysql-user redis nacos namesrv broker
```

## 7. IDEA 与 Knife4j 操作

IDEA Open `backend/pom.xml`，加载全部四模块；Project SDK、Maven importer/runner 均用 JDK17，Reload Maven。迁移后三个运行配置的 **Working directory 均为 D:\A-project\smartoffice-rebuilt\backend**，启动顺序 user → booking → gateway：

| 模块 / Main class | Program arguments |
| --- | --- |
| user-service / com.geek.smartoffice.user.UserApplication | `--spring.config.additional-location=file:./.env.host-dev-user.properties` |
| booking-service / com.geek.smartoffice.SmartOfficeApplication | `--spring.config.additional-location=file:./.env.host-dev-booking.properties` |
| gateway / com.geek.smartoffice.gateway.GatewayApplication | `--spring.config.additional-location=file:./.env.host-dev-gateway.properties` |

清理运行配置中残留的旧 DB_URL、SERVER_PORT、NACOS_SERVER_ADDR、USER_SERVICE_URL、MQ_ENABLED 或旧容器地址；不要把真实秘密贴进可共享的 Run Configuration。新私有 properties 已包含所需配置。

1. 用新测试账号，通过 `POST http://127.0.0.1:8080/api/users/login` 获取 JWT。密码在本机新 env 内查看，不使用文档中的固定弱密码。
2. 浏览器打开 **http://127.0.0.1:8081/doc.html**。该 UI 属于 booking，不是 Gateway 文档聚合。
3. 在 Authorize 的 bearerAuth 输入登录 JWT；该版本没有单独保存按钮，填写后失焦。浏览器自动化使用 `Bearer <JWT>`，尚未观察到请求，故未确认该 UI 所需的前缀格式；手工测试时检查请求是否存在一个且仅一个 `Authorization: Bearer <JWT>`，不要公开其值。
4. 进入会议室 GET listRooms，点击“调试”，执行 `GET /rooms/list`。UI 直连 8081；前端/Gateway API 契约保持 `/api`，对应 `http://127.0.0.1:8080/api/rooms/list`。**本轮该按钮发送尚未通过浏览器验收**；可先用 PowerShell/HTTP 客户端验证已通过的后台接口，再针对 UI 单独排查，不能把页面打开当成按钮已正常工作。
5. 管理员可以测试 `POST /rooms` 的 capacity=31，预期 400（当前错误文案仍写 100）；普通用户创建预期 403。有效创建会真实写入新开发库，请用专用练习名称。

Nacos 实例查询已验证；本轮没有验证控制台账号初始化/登录，不把控制台页面地址等同可用后台账号。

## 8. 凭据误输出、轮换和 Git 安全

本次一项子任务搜索误将旧私有 env 的 **3 项秘密值**输出到工具记录；另有 1 项非秘密身份键。没有在此复述：

- 秘密：旧 JWT_SECRET、NACOS_AUTH_TOKEN、NACOS_AUTH_IDENTITY_VALUE。
- 非秘密：NACOS_AUTH_IDENTITY_KEY。
- 准确记录：`C:\Users\11707\.codex\sessions\2026\10\20\rollout-2026-10-20T21-25-15-01a1597c-d2a9-7f91-9f77-551317fab8d9.jsonl`，位于项目外，未提交 Git；未擅自删除或修改此记录。

新开发环境的八项基础设施/JWT/Nacos 秘密全部独立，与旧值不同；两个开发账号密码也独立生成。原 `deploy/.env.upgrade` 和旧 `backend/.env.host-*.properties` 保留，当前启动**不使用**。未来重新启用旧环境前，应轮换上述三个旧秘密；JWT 更新使旧 Token 失效，Nacos token/identity 需要协调更新。没有证据表明该误输出包含数据库/Redis 密码，不能声称这些值也已暴露。

私有 env、宿主 properties、logs、target、node_modules 等均被忽略；新增 `/backend.zip` 忽略规则，保留用户归档文件而不纳入提交。最终文本候选与新旧凭据逐项内存比对，结果另存无秘密值的 hygiene 记录。**忽略规则不能撤回已进入 Codex 工具记录的旧秘密。** 不要将该 jsonl 或私人 env 转发/上传。

## 9. 文件修改与未验证范围

本轮必要修改：新增 host-dev Compose 覆盖、私有配置生成脚本、恢复报告/计划；README 和 runbook 链接；shared 文档访问开关及 5 项回归；booking 关闭不兼容 Knife4j enhancement 及 3 项 OpenAPI 回归；.gitignore 排除 backend.zip。原升级 Compose、数据库 SQL、业务接口、依赖版本均未更改。

保留用户原有 RoomSaveDTO 的 Max30（文案仍100）、RoomCacheStore 格式修改、父/user POM 格式修改和 backend.zip，没有为环境恢复重写业务逻辑。POM 去除格式差异后内容相同。

未执行/不作结论：旧 VHD 业务数据恢复、原生 MySQL 历史库迁移、生产部署、HA/故障注入/断电恢复/本轮压力测试、完整 Java/前端容器镜像部署、Vue 浏览器流程、Nacos 控制台登录。当前单机开发环境通过不代表这些场景已经验收。

收尾已按 PID、Java 进程名和启动 UTC ticks 确认并停止本任务测试 JAR。22:50 正常权限复查：三个旧测试进程均已停止，8080/8081/8082 无监听，供 IDEA 使用；六个中间件仍 running / healthy、重启 0。没有停止其他项目进程。未创建本轮 Git 提交，没有 push。
