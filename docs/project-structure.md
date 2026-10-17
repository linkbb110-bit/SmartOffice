# SmartOffice 项目结构与开发入口

前端、后端与部署配置已分离。本文说明当前目录，历史迁移过程和测试证据见 [directory-layout-verification.md](directory-layout-verification.md)；完整逐文件目录树见 [directory-tree.txt](directory-tree.txt)。

## 目录结构

```text
smartoffice-rebuilt/
├── frontend/
│   ├── src/
│   │   ├── api/                # HTTP API
│   │   ├── components/         # 预约弹窗、订单组件
│   │   ├── router/             # Vue 路由
│   │   ├── styles/
│   │   ├── types/
│   │   ├── utils/              # Axios、Token、重复提交控制
│   │   ├── views/
│   │   ├── App.vue
│   │   └── main.ts
│   ├── public/
│   ├── frontend-tests/
│   ├── package.json
│   ├── package-lock.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── tsconfig.app.json
│   ├── env.d.ts
│   ├── index.html
│   ├── .env.development.example
│   └── .env.production.example
├── backend/
│   ├── .mvn/
│   ├── gateway/                # 各模块含 pom.xml 和 src/
│   ├── user-service/
│   ├── booking-service/
│   ├── shared/
│   └── pom.xml
├── deploy/
│   ├── .env.upgrade.example
│   ├── docker-compose.upgrade.yml
│   ├── docker-compose.dev.yml
│   ├── migrations/
│   ├── run-host.sh             # 保留的历史单体示例，不是当前启动入口
│   └── upgrade/
│       ├── booking-service.Dockerfile
│       ├── user-service.Dockerfile
│       ├── gateway.Dockerfile
│       ├── frontend.Dockerfile
│       ├── rocketmq.Dockerfile
│       ├── nginx.conf
│       ├── rocketmq/
│       ├── tests/              # Docker 目标保护隔离回归
│       └── …                   # 真实验收脚本、三 JAR 冒烟与 fixture，完整清单见目录树
├── docs/
├── .gitignore
├── .dockerignore
└── README.md
```

根目录不再包含 Vue src/package.json/node_modules 或单独的 Java 微服务模块。当前仍保留 ignored `.idea`、`.upgrade-backup`、`logs`；不会因为整理目录删除用户历史配置和证据。生成产物在 `frontend/node_modules`、`frontend/dist`、`backend/*/target`，均不提交。

## 模块与保留标识

| 目录 | artifactId / 工程 | 职责 | 默认端口 |
| --- | --- | --- | --- |
| frontend | smartoffice-admin | Vue 3、TypeScript、Element Plus；登录、预约、取消、请求幂等控制 | Vite 5173；容器映射 18080 |
| backend | smartoffice-parent | 四模块聚合、依赖版本与测试插件管理 | 无 |
| backend/shared | smartoffice-shared | JWT、Result、异常、安全、AOP 公共库 | 无 |
| backend/user-service | smartoffice-user-service | 用户、认证、当前有效身份，独立用户库 | 8082 |
| backend/booking-service | smartoffice-booking-service | 房间、预约/取消、缓存、Sentinel、Outbox、通知/审计 | 8081 |
| backend/gateway | smartoffice-gateway | API 路由、身份头清理、服务发现 | 8080；容器映射 18000 |

Nacos 注册名仍是三个服务各自的 `smartoffice-*` 名称；Java package、启动类、接口路径、SQL、事务及消息行为未变。后端四个子模块的 parent.relativePath 均为 `../pom.xml`，父工程模块列表为 shared、user-service、booking-service、gateway。

## Windows PowerShell：前端独立工程

每个命令块注明工作目录，建议分别开终端。宿主已验证 Node 24.11.1；Vite native TS 配置的 Node 22 最低要求见 [README](../README.md)。

```powershell
# 从仓库根目录开始
Set-Location frontend
npm install
npm test
npm run build
# 宿主启动 Gateway 默认 8080；若使用完整 Docker 改为 18000
$env:VITE_GATEWAY_URL = 'http://127.0.0.1:8080'
npm run dev
```

环境文件放 frontend 内，`.env.development`、`.env.production` 和 `*.local` 保持私有；example 可提交。`VITE_API_BASE_URL` 保持 `/api`。Vite 去掉 `/api` 前缀后转发 Gateway；生产 Nginx 也保持既有 API 契约。`VITE_*` 会进入浏览器构建，不能存放真实密钥。

## Windows PowerShell：后端构建、运行与 IDEA

```powershell
# 新终端，从仓库根目录开始
Set-Location backend
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -B -ntp verify
```

IDEA：File → Open → 选择 **backend/pom.xml** → Open as Project。Project SDK、Maven importer 与 runner 设为 Java 17，Reload All Maven Projects，确认四个模块都已加载。前端另开 frontend，或者在当前 IDEA 添加对应目录；不要把 booking-service/pom.xml 当作后端聚合入口，也不要沿用旧根 pom.xml 的导入路径。

真实本地 MySQL/Redis 库和账号准备好后，在三个终端分别设置该服务的 DB_URL、DB_USERNAME、DB_PASSWORD（两服务的 JWT_SECRET 相同；预约进程另有 Redis 参数），cwd 为 backend，再分别运行：

```powershell
# 终端 1
java -jar user-service/target/smartoffice-user-service-1.0.0.jar --spring.profiles.active=local
# 终端 2
java -jar booking-service/target/smartoffice-booking-service-1.0.0.jar --spring.profiles.active=local
# 终端 3
java -jar gateway/target/smartoffice-gateway-1.0.0.jar --spring.profiles.active=local
```

local 使用宿主直连、默认关闭 MQ；cloud 使用 Nacos 服务发现并由升级 Compose 提供环境。不要用真实业务库做冒烟。无需 Docker 时可运行既有内存库测试：

```powershell
# 仓库根；先完成 backend verify、frontend 安装
./deploy/upgrade/smoke-local.ps1 `
  -JavaHome 'C:\Program Files\Java\jdk-17' `
  -H2Jar '<本机 Maven 缓存中的 H2 JAR 绝对路径>' `
  -WithFrontend
```

## Windows PowerShell：Docker

先按 [runbook](runbook.md) 生成私有 `deploy/.env.upgrade`；已有文件及数据卷保留，不能重新生成密码覆盖现有数据库账号。仅连接本地测试 Docker；从仓库根运行：

```powershell
docker context show
docker context inspect --format '{{.Endpoints.docker.Host}}'
docker version
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml config -q
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml build
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml up -d --wait --wait-timeout 360
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml ps
# 停止服务，保留全部卷
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml stop
```

Compose 的 build context 为 `..`（相对 deploy，实际是仓库根），Dockerfile 从 frontend/backend 各自取源码，SQL 与 Broker 挂载相对 deploy。固定升级项目名 `smartoffice-upgrade`、服务与八个命名卷不变；dev 文件显式保留 `smartoffice-rebuilt`，不是升级环境入口。

## 已验证与未执行

本轮已执行：backend Maven 110/110；frontend 离线安装、15/15、类型检查/构建与开发 HTTP；探针隔离 57/57；四种 Compose 配置和 40 个源路径；三 JAR + Vite 冒烟 8/8，含 Gateway 登录、真实 HTTP Feign、预约幂等、取消与 Outbox。

冒烟使用内存 H2，MQ/Nacos 关闭；没有将这些结果记为真实中间件验收。本地 Docker Engine 管道不存在，因此新布局的镜像构建、容器启动/健康、真实 MySQL/Redis/RocketMQ/Nacos 与浏览器回归未执行。完整命令、日志索引和保留边界见 [目录迁移验收记录](directory-layout-verification.md)。
