# 前后端目录整理与验收

日期：2026-10-17（Asia/Shanghai）。基线 `a29823e`，执行分支 `目录迁移工作分支`；迁移开始时工作区干净。范围仅为目录、构建/部署路径及文档，未增加组件、功能或业务重构。

## 目录与映射

模块职责和当前开发入口见 [project-structure.md](project-structure.md)，完整源码树见 [directory-tree.txt](directory-tree.txt)，只列受版本控制的文件；不将 node_modules、dist、target、logs、IDE 配置、私有环境与备份当作源码。

| 原路径（基线） | 当前路径 | 说明 |
| --- | --- | --- |
| src/ | frontend/src/ | Vue 业务代码原样移动 |
| frontend-tests/ | frontend/frontend-tests/ | 现有测试与 fixture 原样移动 |
| package.json / package-lock.json | frontend/ 下同名文件 | scripts 仅调整 Vite 文件名，依赖与锁版本不变 |
| vite.config.mjs | frontend/vite.config.ts | 配置内容不变，保留 /api proxy 与 native loader |
| index.html / env.d.ts / tsconfig*.json | frontend/ 下同名文件 | 原样移动 |
| .env.development / .env.production 及 example | frontend/ 下同名文件 | 私有文件保留、继续忽略，没有打印值 |
| 无独立 public | frontend/public/.gitkeep | 保留空静态资源目录，不新增业务资源 |
| pom.xml / .mvn/ | backend/pom.xml / backend/.mvn/ | 父 POM module backend 改为 booking-service，其余版本与插件不变 |
| backend/（原预约模块） | backend/booking-service/ | 子 POM、Java、资源、测试不变 |
| gateway/、user-service/、shared/ | backend/ 下同名模块 | 子模块 parent.relativePath 仍为 ../pom.xml |
| backend/Dockerfile、gateway/Dockerfile、user-service/Dockerfile | deploy/upgrade/booking-service.Dockerfile、gateway.Dockerfile、user-service.Dockerfile | builder 从新路径 COPY，JAR 及运行标识不变 |
| docker-compose.upgrade.yml / docker-compose.dev.yml | deploy/ 下同名文件 | build context 指向仓库根，挂载改为相对 deploy 的路径 |
| .env.upgrade / .env.upgrade.example | deploy/ 下同名文件 | 私有文件保留并忽略，示例可提交 |

升级 Compose 的 `name: smartoffice-upgrade`、服务名、端口、镜像名称、八个卷的 key 均保持原值。历史 dev Compose 显式设置原根目录默认项目名 `smartoffice-rebuilt`，防止因移到 deploy 变成新项目/卷。没有启动或重建容器，没有操作数据库或数据卷。

原有依赖缓存/构建产物随所属目录安全移动，未纳入 Git；根目录 44 个旧 `.log` 原样保留在 `logs/directory-layout/prior-root-logs/`。旧 `logs/local-smoke` 在本轮冒烟前移到 `logs/directory-layout/prior-local-smoke/`，未覆盖历史记录。根 `.idea`、`.upgrade-backup` 与其他既有日志保留且忽略，没有删除用途不明文件。历史验收文档保留原命令和行号，文首附迁移说明；恢复 patch 不改写。

## 内容不变的核对

基于 Git 原 blob 做路径映射核对，而不是只看文件是否存在：

- 原基线 206 个受跟踪文件均在新映射位置找到。
- 157 个 Java/资源/SQL、前端源码/测试、子 POM、Maven 配置、锁文件、恢复补丁等内容与原 blob 一致。
- 父 POM 仅 module 路径变更；frontend package.json 仅 Vite config 文件名变更。全部 Java package、启动类、artifactId、Nacos 名、数据库 SQL 及业务接口保持不变。
- Compose 除路径及 legacy 项目名外没有配置语义变化；六个真实验收入口对照原文只有路径修改，R3 本地 Docker guard 保留。
- 私有前端 env 移动前后 SHA256 一致；8 项部署凭据仅在内存中检查未进入源码，不打印值。现有 `.gitignore` 的通用模式覆盖新层级，`.dockerignore` 改用递归模式排除嵌套依赖、产物、日志、私有 env 和 IDE 目录。

证据：ignored `logs/directory-layout/source-and-hygiene.json`、`frontend-content-check.log`、`frontend-move.log`、`deploy-path-invariants.json`。这是目录迁移内容/泄露检查，不代表重新完成全项目安全扫描。

## 实际验证

| 验证 | 命令 / cwd | 结果与证据 |
| --- | --- | --- |
| 完整后端编译、测试、打包 | backend：Java17 下 `mvn -B -ntp -o verify` | **110/110，0 failures/errors/skipped**；shared 21、user-service 15、booking-service 68、gateway 6；21:05:26 BUILD SUCCESS，1分51秒。`maven-verify.log`、逐 XML `surefire-summary.json` |
| 前端独立安装 | frontend：`npm.cmd install --offline --ignore-scripts --no-audit --no-fund` | exit 0，复用缓存，lock 未变；没有验证清空缓存后的联网下载。`frontend-install.log` |
| 前端测试 | frontend：`npm.cmd test` | **15/15**；实际 Node 行为测试，未改变断言。`frontend-test.log` |
| 类型检查与构建 | frontend：`npm.cmd run build` | exit 0；vue-tsc + Vite，8.86秒；既有 PURE 与大包提示保留。`frontend-build.log` |
| 前端开发启动 | frontend：`npm.cmd run dev -- --host 127.0.0.1 --port 15173 --strictPort` | `/`、`/src/main.ts`、`/src/App.vue`、`/login` HTTP 200；本次开发进程已停止。`frontend-dev.log`、`frontend-http.log` |
| 探针安全回归 | 根目录：`node --experimental-test-module-mocks --test deploy/upgrade/tests/probe-boundary.test.mjs` | **57/57**；调用真实脚本入口，Docker/Java 外部边界被拦截，没有访问 daemon。`probe-tests.log` |
| 升级 Compose 私有 env | 根目录：`docker compose --env-file deploy/.env.upgrade -f deploy/docker-compose.upgrade.yml config -q` | exit 0，不打印 resolved secrets |
| 升级 Compose 示例 env | 根目录：`docker compose --env-file deploy/.env.upgrade.example -f deploy/docker-compose.upgrade.yml config -q` | exit 0，模板仅验证结构，不用于真实启动 |
| 旧开发 Compose | 根目录：`docker compose -f deploy/docker-compose.dev.yml config -q` | exit 0；未启动历史容器 |
| 从 deploy 运行 | deploy：`docker compose --env-file .env.upgrade -f docker-compose.upgrade.yml config -q` | exit 0；四组 Compose 结果见 `compose-validation.json` |
| Docker 源路径 | 检查 Compose bind、各 Dockerfile COPY 与文件位置 | **40 个路径存在**；仅文件/配置检查，未构建镜像。`deploy-source-paths.json` |
| 三 JAR + Vite HTTP 冒烟 | 下方命令，仓库根 | **8/8**；21:11:22 完成，具体范围见下文；`host-smoke.log` 与 `logs/local-smoke/result.json` |

本机实际 Maven 为 `D:/apache-maven-3.9.4-bin/apache-maven-3.9.4/bin/mvn.cmd`，JAVA_HOME 为 `C:/Program Files/Java/jdk-17`；所有日志在 `logs/directory-layout/`，不提交。前端使用 Node 24.11.1/npm 11.6.2；native TS 配置需要对应 Node 支持，最低说明已写入 README/runbook。

```powershell
# 在仓库根运行，不读取真实业务数据库
./deploy/upgrade/smoke-local.ps1 `
  -JavaHome 'C:/Program Files/Java/jdk-17' `
  -H2Jar 'D:/download/apache-maven-3.9.4-bin/apache-maven-3.9.4/mvn_repo/com/h2database/h2/2.3.232/h2-2.3.232.jar' `
  -WithFrontend
```

该冒烟启动新布局下三个实际 JAR 和 Vite，端口为 loopback 动态分配；检查 Vite HTTP、经 Gateway 登录、身份、房间列表、实际 Feign 新预约/幂等重放、只一条订单、重复取消和唯一 PENDING Outbox。数据库为**新建内存 H2**，Redis 不可用降级，MQ/Nacos 关闭，browser 标记为 not executed；不是 MySQL/Redis/Broker/Nacos 或浏览器端到端验收。本次进程在 finally 停止，不影响其他服务。

## 失败、未执行与保留限制

- Docker 21:02 只读检查指向 `desktop-linux` / `npipe:////./pipe/dockerDesktopLinuxEngine`，没有 DOCKER_HOST/CONTEXT 覆盖；Server 查询退出 1，named pipe 不存在。恢复收尾时再次只读检查结果相同；记录 `docker-status.json`、`docker-resumed-status.json`。
- **未执行**：迁移后的 Docker 镜像构建、十容器启动/健康、真实 MySQL/Redis/RocketMQ/Nacos 回归及浏览器联调。Compose config 和 H2 冒烟不能替代这些项目。没有反复重试未变化的 Engine，也没有删除/重置 Docker 数据。
- 前端开发 HTTP 首次被沙箱套接字权限阻止，经标准工具权限提升后通过；没有调整 ACL、执行策略或关闭安全检查。Maven/综合冒烟也使用标准批准路径。
- 未在 Docker Node 22 镜像中构建；README/runbook 明确 native TS 的 Node 版本要求。既有 Vite 大包/PURE 提示没有因目录整理而扩展为包体重构。

## IDEA 与当前启动入口

1. File → Open → 选择 `backend/pom.xml` → Open as Project。
2. Project SDK、Maven importer 和 runner 使用 JDK 17，Reload All Maven Projects。
3. 确认 shared、user-service、booking-service、gateway 四模块；不要将 booking-service 当作整个后端导入。
4. backend cwd 运行 Maven，frontend cwd 运行 npm；Docker 在仓库根指定 deploy 下的文件。完整 PowerShell 命令见 [README](../README.md) 与 [runbook](runbook.md)。

## 收尾状态

- `Review directory layout` 独立只读复核完成，未发现剩余阻塞问题或需修正的路径遗漏；POM、Docker COPY/context/mount、Compose 项目与卷身份、脚本和文档入口一致。原 206 个文件均保留，当前完整源码树共 211 个文件。
- 已核对上表实际日志和退出结果；恢复收尾阶段没有重复运行已通过的完整测试。三 JAR + Vite 的 8 项冒烟已经完成，其中 API 请求从 Vite 的 `/api` 进入 Gateway，再调用两个业务服务，包含真实 HTTP Feign；该验证依然只使用内存 H2。
- `README.md`、`project-structure.md`、`runbook.md` 和完整 `directory-tree.txt` 已同步；历史证据与当前结果明确区分。IDEA 的导入路径和 POM 结构已检查，未实际操作 IDEA GUI。
- Git 暂存前后检查文件映射、未改动业务 blob、忽略规则及私有值泄露；只保存迁移源码、配置、测试与文档，不包含私有 env、依赖缓存、构建产物、日志、数据库数据或备份。检查结果保存在 ignored `source-and-hygiene.json`。
- 本次只在 `目录迁移工作分支` 保存一次本地目录迁移提交，保留 `a29823e` 作为父提交，不推送；提交标识以 `git log -1 --oneline` 和本轮完成回复为准。

目录整理范围已收尾。真实容器、迁移后的真实中间件及浏览器回归仍按上文标记为未执行，不将历史验收或本轮 H2 结果算作通过。
