# SmartOffice 云智办

Java 17 / Spring Boot 3 / Spring Cloud / Vue 3 会议室预约系统。前端与后端是两个独立工程；目录整理保留 Java 包名、Maven artifactId、服务注册名、业务逻辑和同源 `/api` 契约。

## 目录与职责

```text
smartoffice-rebuilt/
├── frontend/
│   ├── src/                    # Vue 页面、组件、API 和请求封装
│   ├── public/                 # 静态资源
│   ├── frontend-tests/         # Node 行为回归与隔离 fixture
│   ├── package.json
│   ├── package-lock.json
│   ├── index.html
│   ├── env.d.ts
│   ├── vite.config.ts
│   ├── tsconfig.json
│   └── tsconfig.app.json
├── backend/
│   ├── gateway/                # 路由、/api 处理、伪造身份头清理
│   ├── user-service/           # 登录、注册、有效用户查询；独立用户库
│   ├── booking-service/        # 会议室、预约/取消、缓存、Outbox、通知/审计
│   ├── shared/                 # JWT、安全、Result、异常、AOP 公共库
│   ├── .mvn/                   # Maven 项目配置与 HTTPS mirror
│   └── pom.xml                 # IDEA 打开此父工程
├── deploy/
│   ├── docker-compose.upgrade.yml
│   ├── docker-compose.dev.yml  # 历史 MySQL/Redis 示例
│   ├── .env.upgrade.example    # 私有 .env.upgrade 不提交
│   ├── migrations/
│   └── upgrade/                # Dockerfile、Nginx、Broker 配置与验收脚本
├── docs/
└── README.md
```

模块职责与独立开发入口见 [project-structure.md](docs/project-structure.md)，完整源码目录树见 [directory-tree.txt](docs/directory-tree.txt)。依赖、构建产物、日志、IDE 配置与备份均被忽略，不属于源码。现有缓存仅为复用保留，不纳入 Git。

三个服务注册名仍为 `smartoffice-gateway`、`smartoffice-user-service`、`smartoffice-booking-service`，shared 只生成公共 JAR。预约以 MySQL 房间行锁、READ_COMMITTED、requestId 唯一键与取消 CAS 保证一致性；Redis 用于辅助锁和缓存，Outbox 与 RocketMQ 用于至少一次事件投递。

## Windows PowerShell：前端

需要 Java 17、Maven 3.9.x、Node.js/npm；本机验证 Node 24.11.1。`vite.config.ts` 使用 native loader，Node 22 需至少 22.18（该版本默认开启 TypeScript 支持，见 [Node 官方说明](https://nodejs.org/docs/latest-v22.x/api/typescript.html#modules-typescript)）；容器保留 Node 22 镜像，本轮未执行镜像构建。每组命令从注明目录执行，根目录不再是 npm 工程。

```powershell
# 从仓库根进入前端
Set-Location frontend
npm install
npm test
npm run build
# 连接完整 Docker 环境映射到宿主的 Gateway
$env:VITE_GATEWAY_URL = 'http://127.0.0.1:18000'
npm run dev
```

开发页默认 `http://localhost:5173`。若 Gateway 直接在宿主运行，将代理目标改为 `http://127.0.0.1:8080`。开发与生产浏览器均访问 `/api`，由 Vite/Nginx 转发。私有前端环境放 `frontend/.env.*.local`，不要将密钥放入会被打包的 `VITE_*` 变量。

## Windows PowerShell：后端与 IDEA

```powershell
# 新终端，从仓库根进入 backend
Set-Location backend
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -B -ntp verify
```

IDEA 选择 **File → Open → backend/pom.xml → Open as Project**，Project SDK、Maven importer/runner 设为 JDK 17，再 Reload All Maven Projects。应出现 shared、user-service、booking-service、gateway 四个子模块。不要只打开 booking-service/pom.xml。前端可单独打开 frontend；原根目录 .idea 保留，不作为新后端工程入口。

独立本地 MySQL/Redis 已按 [runbook](docs/runbook.md) 准备时，在三个 PowerShell 终端分别运行下面一条命令（cwd 均为 backend）。用户/预约进程分别配置自己的 DB_URL、DB_USERNAME、DB_PASSWORD，预约进程另设 Redis 环境，两业务服务使用同一 JWT_SECRET；密码不写入命令或提交。

```powershell
# 终端 1：用户服务，默认 8082
java -jar user-service/target/smartoffice-user-service-1.0.0.jar --spring.profiles.active=local
# 终端 2：预约服务，默认 8081；local 模式默认关闭 MQ
java -jar booking-service/target/smartoffice-booking-service-1.0.0.jar --spring.profiles.active=local
# 终端 3：Gateway，默认 8080；local 模式宿主直连
java -jar gateway/target/smartoffice-gateway-1.0.0.jar --spring.profiles.active=local
```

无需 Docker 的三 JAR + Vite 冒烟，从仓库根运行 `./deploy/upgrade/smoke-local.ps1 -H2Jar '<本机 H2 驱动绝对路径>' -WithFrontend`。只用新内存 H2、关闭 MQ/Nacos，不能代替真实中间件验收。

## Windows PowerShell：完整 Docker

以下从仓库根执行，必须确认本机 pipe/socket 与 Engine 可用。首次生成 `deploy/.env.upgrade` 的命令见 [runbook](docs/runbook.md#一键完整本地-docker)；保留已有私有文件，不重新生成密码覆盖既有数据库账号。

```powershell
docker context show
docker context inspect --format '{{.Endpoints.docker.Host}}'
docker version
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml config --quiet
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml build
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml up -d --wait --wait-timeout 360
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml ps
# 仅停止，保留数据卷
docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml stop
```

前端 `http://localhost:18080`；Gateway 健康 `http://localhost:18000/actuator/health`；Nacos 控制台 `http://localhost:18880`。固定项目 smartoffice-upgrade、服务、端口和八个卷保持不变。原有 admin/user、密码 123456 只供隔离本地 demo，没有公开管理员注册接口。

## 证据与文档

本次目录迁移结果、完整目录树和旧路径映射见 [迁移验收记录](docs/directory-layout-verification.md)。2026-10-16 五项修复为 Java 110、前端 15、探针隔离 57 项通过，见 [修复报告](docs/final-fix-verification.md) 和 [最终审计](docs/final-code-review.md)。

2026-10-14 的真实 MySQL/Redis/RocketMQ/Nacos、Gateway/Feign/Sentinel 和浏览器验收属于当日版本，见 [真实环境报告](docs/real-integration-report.md)。不能当作目录迁移后容器已构建/启动，也不代表生产 HA、断电恢复或容量压测。

- [架构](docs/architecture.md)、[核心流程](docs/core-flows.md)、[技术取舍](docs/tech-decisions.md)
- [运行手册](docs/runbook.md)、[升级进度](docs/upgrade-progress.md)
- [面试笔记](docs/interview-notes.md)、[历史浏览器记录](docs/browser-qa.md)

原稳定基线为本地 a29823e；目录整理在独立分支进行，不推送远程仓库。历史文档、补丁和原始备份继续保留。
