# SmartOffice 前后端目录整理实施计划


**Goal:** 将现有源码分为独立 frontend 和 backend 聚合工程，业务行为及服务身份保持不变。

**Architecture:** frontend 保留现有 Vue/API 契约；backend 包含 shared、user-service、booking-service、gateway；Compose 放在 deploy，Dockerfile 放在 deploy/upgrade，部署构建上下文仍为仓库根。

**Tech Stack:** 保留当前 Java 17、Maven、Vue 3、Vite、Docker 及所有运行时依赖版本。

**Spec:** 用户 2026-10-17 目录整理要求；稳定基线 a29823e；新分支 目录迁移工作分支。

## Global Constraints

- 只改目录及构建/部署/文档路径，Java package、artifactId、启动类、Nacos 名称、API、数据库逻辑均不改。
- 不删除已有业务数据、Docker 卷、备份或私有文件；不推送。现有工作区干净。
- 私有 env 移至所属目录且保持忽略；生成文件可为复用缓存移动，不作为源码提交。
- 根目录只保留项目说明、通用 Git/Docker 忽略及 frontend/backend/deploy/docs；既有 ignored 日志/IDE/备份保留。

## Review Focus

1. frontend 从自身目录安装/测试/构建，Vite proxy 继续 /api，静态资源和 tests 相对路径正确。
2. backend/pom.xml 可独立加载所有四模块；.mvn/settings.xml 不再依赖仓库根 cwd。
3. Compose 相对目录变化不改变 smartoffice-upgrade 项目、服务、卷；COPY 和 SQL/broker 挂载均存在。
4. 无 Docker 的隔离探针与三 JAR 冒烟使用新路径且本地目标保护不减弱。
5. Git move 差异证明 Java/SQL/业务 TS 内容未变；私有 env、target、node_modules、dist、logs 不进入提交。

## Tasks

- [x] 前端：src、frontend-tests、package/lock、index、env/tsconfig/Vite 及私有开发环境迁入 frontend；配置名调整 vite.config.ts；保留或创建 public 目录。运行 npm install（锁版本不变）、test、build 和开发启动检查。
- [x] 后端：原 backend 整体成为 backend/booking-service；其余三个模块进入 backend；父 pom/.mvn 迁入 backend，module backend 改 booking-service；验证 relativePath 和 Maven 全量 verify。
- [x] 部署：两个 Compose 移入 deploy；三个 Java Dockerfile 迁入 deploy/upgrade；前端 Dockerfile、COPY/挂载、环境路径及所有执行脚本适配；运行 compose config 和 probe 隔离回归。
- [x] 文档：更新 README、runbook、architecture/interview 等当前入口；旧审计/验收证据保留历史路径并附迁移映射；生成完整受版本控制目录树和迁移验收记录。
- [x] 收尾：只读复核分支差异、测试/构建/Compose；Engine 可用才验证本地启动/API，否则记录未执行；敏感文件检查后本地提交，不推送。

结果：Maven 110、前端 15、探针 57、三 JAR + Vite/H2 HTTP 冒烟 8 项通过，前端构建/开发启动、四种 Compose 配置及 40 个路径检查通过。Engine 的本地 pipe 不存在，真实容器构建/启动、真实中间件及浏览器回归未执行。详情见 [目录迁移验收记录](../../directory-layout-verification.md)。
