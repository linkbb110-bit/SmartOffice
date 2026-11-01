# 本地运行、验证与故障排查

2026-10-20 本机 Docker 恢复、历史数据盘保留、宿主 IDEA/Knife4j 的配置与实际结果见 [docker-development-recovery.md](docker-development-recovery.md)。IDEA 模式需要新增 host-dev 覆盖；不要把该覆盖与全部 Java 容器同时启动。

## 环境与安全边界

Java17、Maven3.9.x、Node.js（本机验证24.11.1）与 npm；完整部署需要运行中的 Docker Desktop Linux engine/Compose。PowerShell 启动脚本使用标准 Windows 进程和 Hidden 窗口，本地冒烟使用 PowerShell7。Compose 含10服务，首次镜像/依赖下载需要网络，Docker应分配足够内存；本轮未测定最低内存或实际启动耗时。

目录迁移后 Vite 配置名为 `frontend/vite.config.ts`，保留 `--configLoader native`。宿主使用已验证的 Node 24.11.1，Node 22 至少需 22.18 默认 TypeScript 支持（[官方说明](https://nodejs.org/docs/latest-v22.x/api/typescript.html#modules-typescript)）；旧缓存 Node 22 镜像也须核对实际版本。本轮 Engine 不可用，不能把宿主构建成功称为 Docker Node 22 镜像构建成功。

项目已有本地稳定基线 `a29823e`，2026-10-17 在 `目录迁移工作分支` 分支分离前后端目录，没有推送。原源码归档 `.upgrade-backup/original-2026-10-14.zip`；另有保留目录结构的 `original-sources-layout-2026-10-14.zip`，旧环境文件单独保留 `.env.development.original`/`.env.production.original`。这些备份与真实环境文件被 git/docker ignore。恢复应解压到独立目录核对，避免覆盖正在使用的项目。

不自动连接现有数据库。升级 Compose 使用两个新 MySQL、独立账号/新项目卷，原 `deploy/docker-compose.dev.yml` 和原数据卷保留。`deploy/run-host.sh` 是原单体部署示例，升级版使用本页和 `deploy/docker-compose.upgrade.yml`。任何真实云部署不在本次授权范围。

## 构建与 IDEA

从仓库根目录分别进入后端和前端；避免只导入 booking-service 而漏掉 shared/user/gateway：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Push-Location backend
mvn -B -ntp clean verify
Pop-Location
Push-Location frontend
npm ci
npm test
npm run build
Pop-Location
```

已有 frontend/node_modules 且依赖未改时，可在 frontend 直接 test/build，不必重复 npm ci。IDEA 打开 `backend/pom.xml` 作为 Maven 工程，Maven runner/importer 使用 Java17，应加载四个子模块。机器 Oracle javapath shim 曾无输出；显式 JAVA_HOME 可避免选错启动器。`backend/.mvn/settings.xml` 使用 HTTPS mirror，不改全局 settings；Surefire 固定3.5.4。Maven 命令的 cwd 为 backend，前端 npm 命令的 cwd 为 frontend；不使用任何 Git destructive clean。

可执行 JAR：backend/user-service/target/smartoffice-user-service-1.0.0.jar，backend/booking-service/target/smartoffice-booking-service-1.0.0.jar，backend/gateway/target/smartoffice-gateway-1.0.0.jar。后端聚合构建不跳测试。H2 driver 为 test scope，不被打入生产服务 JAR。

## 一键完整本地 Docker

从仓库根执行本节命令。先确认 Docker endpoint 为本地 pipe/socket，且 `docker version` 同时包含 Server。2026-10-14 曾验证十容器 healthy；2026-10-17 目录整理时本地 Engine 管道不存在，不能沿用历史健康状态。Compose config 只代表静态解析成功。

首次创建私有环境文件（已有文件保留）：

```powershell
if (-not (Test-Path -LiteralPath 'deploy/.env.upgrade')) {
    function New-LocalSecret {
        $taskBytes = New-Object byte[] 48
        $taskRng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $taskRng.GetBytes($taskBytes) } finally { $taskRng.Dispose() }
        [Convert]::ToBase64String($taskBytes)
    }
    $taskEnvText = Get-Content -LiteralPath 'deploy/.env.upgrade.example' -Raw
    foreach ($taskKey in @('BOOKING_DB_ROOT_PASSWORD','BOOKING_DB_PASSWORD','USER_DB_ROOT_PASSWORD',
        'USER_DB_PASSWORD','REDIS_PASSWORD','JWT_SECRET','NACOS_AUTH_TOKEN','NACOS_AUTH_IDENTITY_VALUE')) {
        $taskValue = New-LocalSecret
        $taskEnvText = [regex]::Replace($taskEnvText, "(?m)^$taskKey=.*$", "$taskKey=$taskValue")
    }
    [IO.File]::WriteAllText((Join-Path $PWD 'deploy/.env.upgrade'),$taskEnvText)
}
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml config --quiet
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml build
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml up -d --wait --wait-timeout 360
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml ps
```

访问 [前端](http://localhost:18080)、[Gateway健康](http://localhost:18000/actuator/health)、[Nacos控制台](http://localhost:18880)。演示账号 admin/user 密码123456来自原 demo BCrypt 数据；只用于隔离本地演示。可以自行注册 USER；没有注册 ADMIN 接口。Nacos控制台首次管理员初始化按其界面执行。

空 MySQL 数据目录首次启动时，镜像执行挂载的 schema/data，booking 还执行 message-schema.sql。业务服务正常配置 SQL init=never，不在每次重启混入初始化写操作。已有卷不再次执行 entrypoint SQL；若旧版本升级，按下一节手工审查增量，不删除卷。

## 无 Docker 的三服务联调

编译完成后可以使用已有 Maven H2 驱动进行可重复的本地冒烟（只建新内存库）：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
# 根据自己的 Maven localRepository 调整；本机实际驱动位置如下。
./deploy/upgrade/smoke-local.ps1 `
  -H2Jar 'D:\download\apache-maven-3.9.4-bin\apache-maven-3.9.4\mvn_repo\com\h2database\h2\2.3.232\h2-2.3.232.jar' `
  -WithFrontend
```

脚本启动三份实际 JAR、动态本地端口和 Vite，显式 H2、关闭 Nacos/MQ，Redis端口1模拟故障（健康检查暂时不包含 Redis）。它验证真实 Feign 与 HTTP 代理链、预约/重放/取消和 PENDING Outbox；finally 自动停止本次启动的进程。记录 logs/local-smoke/result.json 与独立日志，均被忽略，不保留令牌。不加 BrowserScript 时只执行8项HTTP检查，不能代替浏览器或真实中间件。

本机另已使用项目外的 Playwright 1.56.1 和已安装 Edge 完成真实浏览器验收。可在本机重跑（该临时脚本不是仓库依赖，换机器需另配置浏览器测试工具）：

```powershell
./deploy/upgrade/smoke-local.ps1 `
  -H2Jar 'D:\download\apache-maven-3.9.4-bin\apache-maven-3.9.4\mvn_repo\com\h2database\h2\2.3.232\h2-2.3.232.jar' `
  -WithFrontend `
  -BrowserScript '[历史本机验证资料，未随仓库公开]'
```

浏览器成功后，冒烟共9项且报告 browser=passed；正式冒烟运行中失败报告为 passed=false，不沿用旧结果（Java/H2路径等启动前参数错误直接抛出，应先修正参数）。桌面1366×900与手机390×844验证内容、截图和限制见 [browser-qa.md](browser-qa.md)。只允许访问本轮 loopback 地址，无外部账号或云服务。冒烟 JAR 正在运行时不要重新打包，以免 Windows 文件锁冲突。

若使用真实本地 MySQL/Redis直接运行 JAR，先由操作者建立独立库和账号，按 SQL 初始化。分别设置各进程 DB_URL/DB_USERNAME/DB_PASSWORD，同一 JWT_SECRET；user 8082、booking8081、Gateway8080。local 模式默认直连，MQ_ENABLED=false；Vue `npm run dev` 默认 localhost8080，可用 VITE_GATEWAY_URL 修改。`frontend/.env.development` / `frontend/.env.production` API已改 `/api`；示例文件也如此。前端对外只访问同源 `/api`，不使用容器主机名。

## 已有数据库增量

本轮未迁移用户原有业务库，仅在新建验收schema中实际验证V1增量迁移及旧fixture保留。单体数据迁移应先由操作者备份并只对本地副本操作：复制 sys_user 到用户库（保留 id/密码哈希/角色），复制 meeting_room/booking_order 到预约库。保留订单的 user_id 引用，不重新编号。

`deploy/migrations/V1__booking_request_id.sql` 是一次性的 ADD COLUMN/唯一键，需要先检查列/索引是否已存在；旧行requestId为NULL，保留全部订单。再执行 additive `backend/booking-service/src/main/resources/message-schema.sql` 建消息表。旧历史订单不会自动补造创建事件。schema.sql 的 CREATE IF NOT EXISTS 不会修改已存在表，因此不能替代 ALTER 迁移。不要重跑 demo data 去覆盖已有用户身份，也不要删除数据卷。

## 运行验收与常见故障

- 登录后查 `/api/rooms/list`，两次同requestId同payload预约返回同ID；换参数同键409；重叠预约409，相邻允许；本人取消与重复取消成功，越权403。
- admin 查看 `/api/admin/messages/outbox`：MQ可用时逐步SENT；通知 `/api/notifications/my`、审计表与receipt各只一份。新消费组测试先发事件再启动消费者，验证 FIRST_OFFSET；不要从同一个消费组旧offset推断新组行为。
- Outbox PENDING重试：查 broker NameServer地址/内网公告IP、Topic、连接日志；SENDING过期60秒由扫描恢复。已SENT但无通知：查消费日志、receipt、重试队列和 `%DLQ%smartoffice-notification-v1`。当前没有自动DLQ回灌，保留原eventId由操作者审查后重放；不直接改订单状态。
- Redis故障：详情/预约查库仍可用，记录降级。数据库不可用则业务失败，不能假装缓存足以预约。修复连接/密码后观察实际恢复；缓存失效失败有TTL窗口。
- Feign401：JWT身份与当前用户库不同/已删除；503：用户服务不可达、超时或远程服务错误。仅新预约受此检查，取消/查询不依赖远程。
- Gateway503：检查 Nacos注册名、健康和cloud profile；Gateway默认local直连，Composecloud用lb，不能混用容器与localhost地址。
- 首次健康失败：检查空卷是否完成SQL初始化、是否旧卷缺request_id/message表、JWT_SECRET是否一致、MySQL账号是否仍用旧密码。私有环境改密码不会自动修改已有MySQL账号。
- Docker broker仅公告容器内网地址；宿主机直连MQ需要另设本地网络/公告配置，不能把当前默认Compose当成宿主SDK可达环境。
- Windows构建EPERM/测试attach失败：本轮由标准权限批准执行本地验证；未修改ACL、node_modules或禁止的系统权限。IDEA正常本地终端复跑并查看实际退出码。

停止新项目可用 `docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml stop`。不提供删卷或清理用户数据的命令。

## 真实环境验收命令（2026-10-14新增）

先检查本机 Engine 当前状态；私有环境与八个升级项目卷来自历史验收，继续使用，不重新生成已有 MySQL 账号密码。Compose 与私有 env 已归入 deploy，固定 smartoffice-upgrade 项目名不变。完整实际验收和失败历史见[real-integration-report.md](real-integration-report.md)，本节命令不代表目录迁移后已自动通过。

```powershell
docker --context desktop-linux version
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml config -q
docker compose --progress plain --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml build
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml up -d --pull never --no-build --wait --wait-timeout 360
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml ps
```

Docker未启动时可正常执行`docker desktop start --timeout 45`，不使用恢复出厂或删除WSL/卷。前端`http://127.0.0.1:18080`，Gateway`http://127.0.0.1:18000`，Nacos console`http://127.0.0.1:18880`。Compose基础镜像仍可能需要公共网络；本机公共token鉴权超时已通过当前进程使用现有系统代理解决，不更改全局配置或TLS。只在需要时使用下面模式，finally恢复原变量：

```powershell
$previousHttp = $env:HTTP_PROXY
$previousHttps = $env:HTTPS_PROXY
try {
  $systemProxy = [Net.Http.HttpClient]::DefaultProxy.GetProxy([Uri]'https://auth.docker.io/token')
  if ($systemProxy.Host -notin @('127.0.0.1','localhost')) { throw '请核对本机实际系统代理；此示例只适用于本机loopback代理。' }
  $env:HTTP_PROXY = $systemProxy.AbsoluteUri
  $env:HTTPS_PROXY = $systemProxy.AbsoluteUri
  $containerProxy = [UriBuilder]::new($systemProxy)
  $containerProxy.Host = 'host.docker.internal'
  docker compose --parallel 1 --progress plain --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml build `
    --build-arg "HTTP_PROXY=$($containerProxy.Uri.AbsoluteUri)" `
    --build-arg "HTTPS_PROXY=$($containerProxy.Uri.AbsoluteUri)"
  if ($LASTEXITCODE -ne 0) { throw '镜像构建失败，请保留日志排查。' }
} finally {
  $env:HTTP_PROXY = $previousHttp
  $env:HTTPS_PROXY = $previousHttps
}
```

已构建镜像存在时，日常启动直接执行up，不必反复build。三个Java Dockerfile使用持久BuildKit Maven缓存且不跳测试；运行用户仍为999，`/app/logs`预创建可写。健康检查不等同于业务通过。

以下真实验收脚本使用Node内置模块及现有Java17/容器依赖，不安装新组件。按顺序单独执行，等待上一命令退出、报告cleanup通过后再下一条；不要在这些故障注入期间运行演示或其他测试。

```powershell
node deploy/upgrade/real-migration-check.mjs
node deploy/upgrade/real-api-check.mjs --with-sentinel-burst
node deploy/upgrade/real-redis-lua-check.mjs
./deploy/upgrade/run-middleware-probe.ps1 -Mode Redis
node deploy/upgrade/real-failure-check.mjs --mode mysql-lock
node deploy/upgrade/real-failure-check.mjs --mode mysql-rollback
node deploy/upgrade/real-failure-check.mjs --mode redis-outage
node deploy/upgrade/real-failure-check.mjs --mode user-outage
node deploy/upgrade/real-mq-failure-check.mjs --mode producer-outage
node deploy/upgrade/real-mq-failure-check.mjs --mode consumer-failure
node deploy/upgrade/real-mq-failure-check.mjs --mode consumer-restart
```

脚本拒绝远程Docker/非loopback API；只暂停固定smartoffice-upgrade项目容器，finally启动同一容器。迁移只建独立新schema并保留旧fixture；回滚/消费异常只装本轮唯一room/topic限定trigger，finally核对并删除自己的trigger；其他数据和卷不清理。测试账号/房间/订单持续保留，因此演示列表会出现验收数据。HTTP、SQL、Docker命令和结果写`logs/real-integration/`（ignored，先查看报告再判断通过）。默认admin凭据只是仓库本地演示账号，换账号可通过REAL_API_ADMIN_USERNAME/PASSWORD或REAL_MQ_ADMIN_USERNAME/PASSWORD环境变量传入，不写进命令参数/代码。

MQ历史/重复投递探针需要本轮真实已SENT且三消费表各1的eventId；从本轮JSON或只读SQL取得后运行，不能编造消息替代真实预约：

```powershell
./deploy/upgrade/run-middleware-probe.ps1 -Mode MqEarliest -EventId '<本轮真实eventId>'
./deploy/upgrade/run-middleware-probe.ps1 -Mode MqDuplicate -EventId '<同一个真实eventId>'
```

探针提取实际运行容器JAR并记录SHA，不用宿主旧JAR冒充部署版本；SDK读真实队列offset/reconsumeTimes与数据库各表，重复消息保留eventId。Broker当前单节点SYNC_FLUSH、持久卷，不等于HA或抗断电验证。消费者超过16次后仍需人工审查DLQ，不直接修改订单状态。

### 两个独立探针的目标约束（2026-10-16 修复）

`real-redis-lua-check.mjs` 和 `run-middleware-probe.ps1`（CompileOnly 除外）先读取 Docker CLI context 元数据，只接受本机 named pipe / Unix socket。TCP（即使 loopback）、SSH、远程 named pipe、缺失或错误 endpoint 均拒绝；不通过手工猜测环境变量优先级选目标。通过后每条 Docker 命令都显式使用验证后的 `--host` 并去掉 Docker 环境覆盖。

Compose 项目固定为 `smartoffice-upgrade`，容器必须具有正确 project/service/非 one-off 标签；exec/cp 固定完整容器 ID，不能被 COMPOSE_PROJECT_NAME 或容器名字后续变化改向。PowerShell 的显式 ContainerId 同样检查，OneOff 只用于 MqSendOnly，检查本地镜像且不构建/拉取、不启动依赖。请不要以改名或关闭检查的方式绕过拒绝结果。

可在没有 Engine 的环境执行安全回归（不运行真实中间件）：

```powershell
node --experimental-test-module-mocks --test deploy/upgrade/tests/probe-boundary.test.mjs
```

这组测试启动两个实际脚本入口，在外部边界拦截 Docker、JDK 和报告写入；成功只表示目标检查/命令固定行为通过。`MqEarliest` 仍是读取历史事件的 observer，不能代替“两个业务消费组都完成数据库事务”的真实增量验收。当前五项修复的真实环境未执行项及环境原因见 `final-fix-verification.md`。
