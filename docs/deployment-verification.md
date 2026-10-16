# 独立本地部署与验证

本配置面向本地演示与升级验证：Java 17、Spring Boot 3.5.13、Spring Cloud 2025.0.0、Spring Cloud Alibaba 2025.0.0.0，MySQL 8、Redis 7、RocketMQ 5.3.1、Nacos 3.0.3。Nacos 单机、客户端鉴权关闭且仅作 discovery；RocketMQ 为单 broker remoting 模式。不能将这组配置直接用于生产。

## 隔离范围和请求路径

使用独立 `docker-compose.upgrade.yml`，项目名固定为 `smartoffice-upgrade`，不修改 `docker-compose.dev.yml`。未指定 `container_name`、外部卷或旧卷名字；新卷由 Compose 加前缀 `smartoffice-upgrade_`。两个 MySQL 实例分别保存 `smartoffice_booking` 和 `smartoffice_user`，账号与数据独立。数据库创建由 MySQL 镜像完成，表与本地 demo 数据由 MySQL 镜像在空卷首次启动时执行挂载的 `schema.sql`/`data.sql`，booking 同时执行 `message-schema.sql`；业务服务 SQL init=never。booking 库只保存会议室、订单、outbox、通知、审计；user 库保存 `sys_user`。

浏览器请求 `http://localhost:18080/api/...`，Nginx 去掉 `/api` 后代理到 `gateway:8080`。Gateway 通过 `lb://smartoffice-user-service` 路由 `/users/**`，通过 `lb://smartoffice-booking-service` 路由 `/rooms/**`、`/orders/**`、`/notifications/**`。前端构建固定 `VITE_API_BASE_URL=/api`；浏览器不使用容器域名。内部服务接口无需通过 Gateway 公开。

默认仅开放以下 localhost 端口：

| 用途 | 地址 | 容器端口 |
| --- | --- | --- |
| 前端 | `http://localhost:18080` | frontend:80 |
| 网关诊断 | `http://localhost:18000` | gateway:8080 |
| Nacos 控制台 | `http://localhost:18880` | nacos:8080 |

数据库、Redis、NameServer 和 broker 不发布宿主端口。Nacos 的注册 HTTP 8848 与客户端 gRPC 9848 保持容器内互通；3.x 控制台在 8080，不是 8848。所有业务服务启用 `cloud` profile，`NACOS_SERVER_ADDR=nacos:8848`；booking 使用空 `USER_SERVICE_URL` 启用 Feign discovery、`MQ_ENABLED=true`、`ROCKETMQ_NAMESRV_ADDR=namesrv:9876`。

## PowerShell 启动

在仓库根目录执行。示例文件只含可识别的本地占位值，没有真实密钥。以下脚本首次生成 `.env.upgrade`，若已有文件则保留；文件已被 `.gitignore` 和 `.dockerignore` 排除。

```powershell
Set-Location 'C:\Users\11707\Desktop\smartoffice-rebuilt'
if (-not (Test-Path -LiteralPath '.env.upgrade')) {
    function New-LocalSecret {
        $secretBytes = New-Object byte[] 48
        $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($secretBytes) } finally { $generator.Dispose() }
        [Convert]::ToBase64String($secretBytes)
    }
    $envContent = Get-Content -LiteralPath '.env.upgrade.example' -Raw
    foreach ($key in @('BOOKING_DB_ROOT_PASSWORD', 'BOOKING_DB_PASSWORD',
                        'USER_DB_ROOT_PASSWORD', 'USER_DB_PASSWORD', 'REDIS_PASSWORD',
                        'JWT_SECRET', 'NACOS_AUTH_TOKEN', 'NACOS_AUTH_IDENTITY_VALUE')) {
        $envContent = [regex]::Replace($envContent, "(?m)^$key=.*$", "$key=$(New-LocalSecret)")
    }
    [IO.File]::WriteAllText((Join-Path $PWD '.env.upgrade'), $envContent)
}
docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml config --quiet
docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml build
docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml up -d --wait --wait-timeout 360
docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml ps
```

每个 Java Dockerfile 从仓库根 Maven reactor 执行 `mvn -B -ntp -pl <module> -am clean package`，不传 `skipTests`；测试失败会阻止构建。前端先 `npm ci`，再执行包含 `vue-tsc --noEmit` 的 `npm run build`。首次 Maven/npm/镜像下载需要联网。

NameServer 与 broker 共用本地派生镜像 `smartoffice-upgrade-rocketmq:5.3.1`，基础镜像明确为 `apache/rocketmq:5.3.1`。派生步骤只预建 `logs`/`store` 并赋予 `rocketmq` 用户，以便新命名卷继承目录权限；MQ 仍以该用户运行，不对旧卷执行权限或数据修改。

MySQL、Redis、NameServer、broker、Nacos 的健康检查通过后才启动对应服务。broker 通过 `mqadmin clusterList` 检查自己已注册到 NameServer；Nacos 检查 `http://127.0.0.1:8080/v3/console/health/readiness` 的 `data=ok`。业务服务检查各自 `/actuator/health` 的 `status=UP`；网关等待两个业务服务健康，前端等待网关健康。依赖检查是启动时门槛，运行中仍须看日志与业务请求。首次访问 Nacos 控制台按照界面初始化管理员密码。

环境参数：

| 参数 | 作用 |
| --- | --- |
| `BOOKING_DB_USERNAME/PASSWORD/ROOT_PASSWORD` | booking MySQL 的应用与初始化管理员凭据 |
| `USER_DB_USERNAME/PASSWORD/ROOT_PASSWORD` | user MySQL 的应用与初始化管理员凭据 |
| `REDIS_PASSWORD` | Redis 和 booking 使用同一密码 |
| `JWT_SECRET` | 两个后端必须相同，至少 32 UTF-8 字节 |
| `NACOS_AUTH_TOKEN` | Base64 编码，本例生成 48 随机字节；用于 Nacos 管理/控制台鉴权 |
| `NACOS_AUTH_IDENTITY_KEY/VALUE` | Nacos 服务端身份参数，官方镜像启动时必填 |
| `FRONTEND_HOST_PORT/GATEWAY_HOST_PORT/NACOS_CONSOLE_HOST_PORT` | 仅改变 localhost 映射 |
| `CORS_ALLOWED_ORIGINS` | 更换前端端口时同步更新 origin |

服务内部的 `DB_URL/DB_USERNAME/DB_PASSWORD`、`REDIS_HOST/PORT/PASSWORD` 由 Compose 映射。数据库初始化凭据只在空数据目录首次生效；保留已有卷时修改 `.env.upgrade` 密码不会自动修改 MySQL 用户，需在本地数据库内有计划地调整账号。

## 运行后的真实验证步骤

以下为待执行命令与验收标准，不代表已获得这些结果：

```powershell
$composeArgs = @('--env-file', '.env.upgrade', '-f', 'docker-compose.upgrade.yml')
docker compose @composeArgs ps
Invoke-RestMethod 'http://localhost:18000/actuator/health'
Invoke-RestMethod 'http://localhost:18880/v3/console/health/readiness'
Invoke-WebRequest 'http://localhost:18080/healthz'
# 从每个业务容器读取实际健康结果
docker compose @composeArgs exec booking-service curl -fsS http://localhost:8081/actuator/health
docker compose @composeArgs exec user-service curl -fsS http://localhost:8082/actuator/health
# 检查注册路由和 broker 公告地址（地址应为容器内网 IP）
docker compose @composeArgs exec broker sh mqadmin clusterList -n namesrv:9876
docker compose @composeArgs exec broker cat /tmp/smartoffice-broker.conf
docker compose @composeArgs logs --tail 100 gateway booking-service user-service nacos broker
```

浏览器或 PowerShell 使用前端同源代理验证登录与受保护路由：

```powershell
$api = 'http://localhost:18080/api'
$login = Invoke-RestMethod "$api/users/login" -Method Post -ContentType 'application/json' `
    -Body '{"username":"user","password":"123456"}'
if ($login.code -ne 200 -or -not $login.data) { throw 'Local demo login failed' }
$headers = @{ Authorization = "Bearer $($login.data)" }
Invoke-RestMethod "$api/rooms/list" -Headers $headers
Invoke-RestMethod "$api/orders/my" -Headers $headers
Invoke-RestMethod "$api/notifications/my" -Headers $headers
```

仅在新建本地 demo 数据中验证：创建未来时段预约，检查预约返回 ID 与 `/orders/my`；重复同一个 `requestId` 不应新增订单；取消预约后检查状态为取消，随后轮询 `/notifications/my` 检查相应事件通知。检查 booking 日志、outbox 待发送数和 broker 消费结果，确认真实异步链路，而非仅见 HTTP 成功。停止/重启 booking 与 broker 后检查积压事件能重试、同一 `eventId` 不重复落通知。模拟 user-service 停止时，booking 的远程用户校验应给出受控业务错误并停止创建预约。上述故障操作只用于这个本地项目，执行后恢复服务。

检查数据库职责：

```powershell
docker compose @composeArgs exec mysql-booking sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -D"$MYSQL_DATABASE" -e "SHOW TABLES"'
docker compose @composeArgs exec mysql-user sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -D"$MYSQL_DATABASE" -e "SHOW TABLES"'
```

## Host 模式与 RocketMQ 地址

本 Compose 专供所有 Java 服务运行在容器内的模式。broker 启动脚本从当前容器取得 IP 并设置 `brokerIP1`；Java remoting 客户端会使用 NameServer 返回的 broker IP。只映射 NameServer 端口到 Windows，不能使宿主 Java 自动访问容器 broker IP。

若改为宿主运行 Java 服务，应另建本地 override，公开数据库/Redis 的 localhost 端口，将 `DB_URL`、`REDIS_HOST` 指向 localhost。Nacos 必须同时发布例如 `127.0.0.1:8848:8848` 与 `127.0.0.1:9848:9848`，客户端 `NACOS_SERVER_ADDR=localhost:8848`。发现注册的服务实例必须使用宿主和客户端可达的 IP；不要把容器注册地址混进宿主服务列表。也可让 booking 的 `USER_SERVICE_URL=http://localhost:8082` 显式调用宿主 user-service。

RocketMQ 需发布 `9876`、`10911` 及 VIP 通道 `10909`，broker `listenPort` 与宿主端口应相同，`brokerIP1` 改为所有宿主客户端可达的宿主 IP，宿主 Java 设置 `ROCKETMQ_NAMESRV_ADDR=localhost:9876`。全部客户端在同一宿主时可公告 `127.0.0.1`；这时容器内 Java 不可共用该公告地址。Windows Docker Desktop 的 host networking 支持与设置须单独验证，本文件不启用 host networking。

## 保留数据的迁移与停止

旧服务与卷原样保留。先离线备份旧本地数据库，再把 `sys_user` 导出至新 user 库，把会议室/订单导出至 booking 库；保留主键和用户引用，核对用户角色、BCrypt 密码、订单状态、时间范围和记录数。新表变更应使用经过评审的增量迁移脚本。SQL demo 数据只能初始化本地演示，不能向生产执行；生产迁移需要独立方案、备份和回滚验证。迁移副本核验完成前不切换旧应用。

```powershell
# 只停止这个新项目；保留全部命名卷
docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml stop
```

不要执行 `down -v`、`volume rm`、`volume prune` 或全局清理。此任务没有删除数据库、卷或旧项目资源，没有访问生产。

## 本次验证记录（2026-10-14）

- `docker compose version`：`Docker Compose version v5.4.0`。
- `docker compose --env-file .env.upgrade.example -f docker-compose.upgrade.yml config --quiet`：退出码 0，配置可解析。仅使用示例占位值做静态验证，不生成或记录真实凭据。
- 读取 Compose JSON 后以 PowerShell 断言检查：10 个服务、8 个 `smartoffice-upgrade_` 命名卷且无 external 卷、所有发布端口仅绑定 `127.0.0.1`、三个 Java 服务 cloud/Nacos 环境正确、两后端 JWT 一致且示例至少 32 字节、Nacos token 可解码且至少 32 字节、broker 脚本无 CRLF。检查退出码 0。
- `docker version`：客户端 29.7.2；退出码 1，`dockerDesktopLinuxEngine` named pipe 不存在，无法连接 Docker daemon。
- 因 daemon 不可用，未执行镜像拉取、Docker build、Compose up、数据库初始化、健康检查或 RocketMQ/Nacos 容器集成测试。未自动启动 Docker Desktop、系统服务或变更系统设置。
- Maven 和前端代码验证由升级流程另行记录；Compose 静态验证不能替代真实容器运行证据。
- 恢复后再次config退出0；broker配置为SYNC_FLUSH，仍无副本/高可用承诺。非Docker三JAR+Vite/H2 HTTP与Edge桌面/手机验收已通过，详见browser-qa.md；这不表示Compose已启动。

## 官方依据

- [Nacos v3.0.3 镜像 Dockerfile](https://github.com/nacos-group/nacos-docker/blob/v3.0.3/build/Dockerfile)：版本、Java 17、内置 curl、暴露 8848/9848/8080。
- [Nacos v3.0.3 镜像启动脚本](https://github.com/nacos-group/nacos-docker/blob/v3.0.3/build/bin/docker-startup.sh)：standalone、naming 模式及必填 token/identity 参数。
- [Nacos v3.0.3 配置](https://github.com/nacos-group/nacos-docker/blob/v3.0.3/build/conf/application.properties)：主端口、独立控制台端口与鉴权开关。
- [Nacos 3.0 控制台 API](https://nacos.io/docs/v3.0/manual/admin/console-api/)：公开 readiness 路径和 `data=ok`。
- [RocketMQ 5.3.1 官方发布](https://github.com/apache/rocketmq/releases/tag/rocketmq-all-5.3.1) 与 [官方 Docker 仓库](https://github.com/apache/rocketmq-docker)：版本、`mqnamesrv`/`mqbroker`、数据目录、broker 公告地址和 remoting 端口。官网当前快速开始可能使用较新镜像，本文件明确固定 5.3.1。
- [RocketMQ 镜像构建脚本](https://github.com/apache/rocketmq-docker/blob/master/image-build/Dockerfile-alpine)：非 root 的 `rocketmq` 用户、工作目录与脚本位置。具体固定镜像的实际内容仍须在 daemon 可用后确认。
- [官方 RocketMQ Docker 快速开始](https://rocketmq.apache.org/docs/quickStart/02quickstartWithDocker/)：`brokerIP1` 的地址配置要求。本例使用传统 Java remoting，不需要 gRPC Proxy。
- [MySQL 官方镜像](https://hub.docker.com/_/mysql) 与 [Redis 官方镜像](https://hub.docker.com/_/redis)：初始化环境变量及数据目录。
