# SmartOffice D 盘迁移后开发环境续验

日期：2026-10-21，Asia/Shanghai。本报告接续当天上午的恢复记录，不重新开发现有功能。

## 当前目录与数据边界

- 工作目录与 Git 根目录均为 `D:\A-project\smartoffice-rebuilt`，分支仍为 `codex/project-directory-layout`。
- Docker Desktop 数据目录为 `D:\DockerData\DockerDesktopWSL\DockerDesktopWSL`。用户已确认这是迁移后的预期位置；本轮没有执行 Docker 数据迁移。
- 旧盘 `D:\DockerData\DockerDesktopWSL\disk\docker_data.vhdx` 独立保留。启动前及 14:23 收尾只读检查均为未挂载，完整 SHA-256、长度和修改时间均与原基线一致。
- 保留全部既有未提交代码、私有配置、数据库、八个命名卷和历史证据；没有初始化 SQL 重放、V1 迁移重放、reset、prune、数据覆盖或远程推送。

前轮14点续接证据位于 Git ignored 的 `logs/docker-recovery-20261021-d-drive-resume/`；最终收尾新增的 `finalize` 与 `knife4j-final` 证据目录见下文。10 月 20 日和 21 日上午的记录未覆盖。

## 最终收尾结果（2026-10-21）

**Knife4j 真实浏览器发送已成功，标准 Authorization 请求头生效；缺失和无效 JWT 均仍返回 401。** 三个 cloud 服务使用修复后的 JAR 完成启动及浏览器验收后，本轮测试进程已停止，端口留给 IDEA；六个中间件继续运行。以下旧记录按时间保留，最终结论以本节为准。

### 路径、镜像、卷和数据保护

- 唯一项目根及 Git 根：`D:\A-project\smartoffice-rebuilt`。Compose/Dockerfile/PowerShell 均通过当前目录推导路径，七项生效的项目文件绑定全部在 D 盘，无旧桌面项目挂载。
- Desktop 配置和 docker-desktop WSL 注册目录均为 `D:\DockerData\DockerDesktopWSL\DockerDesktopWSL`（WSL 主盘位于其 `main` 子目录）。镜像和命名卷使用其 `disk\docker_data.vhdx`；Linux 的 `data/docker`、`data/desktop-containerd`、`data/containerd` 经 `df` 确认同在 `/mnt/docker-desktop-disk` 的 `/dev/sde` 数据文件系统上，Engine 使用 containerd snapshotter、DockerRootDir 为 `/var/lib/docker`。
- 原八个卷的名称、创建时间和挂载点与收尾前一致。旧 VHDX 17:46 再次只读核对：未挂载，完整 SHA-256、长度及最后修改时间均与原基线一致。没有移动、删除或覆盖旧盘，没有初始化或重建数据卷。
- `C:\Users\11707\AppData\Local\Docker\wsl` 无文件或 VHDX；桌面同名项目目录已不存在。没有把项目复制回桌面。
- C 盘保留正常 Desktop 安装程序、主机日志、socket、Java 和工具目录。只读统计时安装程序约 3389 MiB、主机日志约 29 MiB；这些不是旧项目挂载或 Linux 镜像/卷数据，本轮没有清理系统文件。
- `.idea/workspace.xml` 仅修正旧 TypeScript 引用为 `$PROJECT_DIR$/frontend/node_modules/typescript/lib`。`backend/.idea/workspace.xml` 的三个现有 Spring Boot 配置仅补齐工作目录和私有配置参数；原文已备份，XML及其余文本不变检查通过，没有写入秘密值。

新证据在 ignored `logs/docker-recovery-20261021-finalize/`：`engine-storage.json`、`linux-storage-paths.json`、`c-drive-wsl-check.json`、`c-drive-normal-runtime-usage.json`、`volumes-after.json`、`old-vhd-final.json`、`storage-final.json` 和 `path-backups/`。

### Knife4j 根因与最小修复

问题由两层原因组成：Authorize 能正常保存，但旧版已打开的 Debug 页只在初始化时读取授权缓存；授权后需要关闭该接口页签并重新打开。重开后原版 UI 实际发送了 `bearerAuth: Bearer ...`，缺少 `Authorization`，因此后端正常返回 401。真实请求始终为 `http://127.0.0.1:8081/rooms/list`，未改地址；同源请求没有 OPTIONS，跨域预检不是这次失败原因。

本次仅在 `backend/booking-service/src/main/java/com/geek/smartoffice/config/OpenApiConfig.java` 的既有 HTTP bearer 安全方案中增加 `paramName="Authorization"` 和 `in=HEADER`，保留 `bearerAuth` 方案标识、HTTP/bearer 类型及 Controller 引用。当前 Knife4j 4.5.0 因此使用正确的头名称。未改 JWT 校验、Spring Security、角色权限、CORS 白名单、接口路由或业务 SQL。

新增一项真实 Springdoc 生成回归，断言方案仍为 HTTP bearer、头名称为 Authorization、位置为 header，且会议室操作仍引用 bearerAuth。实际测试记录：

| 验证 | 结果与实际边界 |
| --- | --- |
| 定向 RED | 4 项中 1 项因 OpenAPI 缺少 `name` 失败；同次受限进程还出现 Mockito/ByteBuddy 附加及 fork 退出超时，失败日志保留 |
| 正常宿主定向 GREEN | 4/4 通过，0 失败/错误/跳过，15:42:44 BUILD SUCCESS；执行环境问题已通过正常宿主运行解决 |
| 修改后 Java 测试 | shared26 + user15 + booking72 + gateway6 = 119，全为 0 失败/错误/跳过；逐 XML 核实为本次 15:44—15:45 的结果 |
| 打包 | 原 verify 在最后 Gateway repackage 阶段被会话中断，没有将其记为完整 BUILD SUCCESS；17:39 仅补 `-DskipTests -Dmaven.jar.forceCreation=true package`，BUILD SUCCESS，不重跑已通过测试 |
| 实际部署元数据 | 运行 JAR 的 `/v3/api-docs` 返回 `type=http`、`scheme=bearer`、`name=Authorization`、`in=header` |

对应 `openapi-header-red.log`、`openapi-header-green.log`、`surefire-final-summary.json`、`maven-verify.log`、`package-after-interruption.log`、`frontend-and-live-openapi.json`。此前 89 项 API/SQL、21 项 Lua **未重复执行**。

### 最终真实浏览器证据

Browser 插件不可用，复用已安装的 Playwright 1.56.1 + Edge，1440×1000，Chromium 沙箱保持启用。流程为真实 Authorize 输入并失焦保存 → 可见关闭/重新打开 listRooms → 点击发送，没有注入请求头、改请求 URL 或用 curl 冒充页面操作。

| 浏览器实际操作 | Network 与页面结果 |
| --- | --- |
| 正确 JWT，经真实 Knife4j UI 发送 | GET `http://127.0.0.1:8081/rooms/list`，HTTP/业务码200，6条数据且页面呈现；Authorization 为标准 Bearer，旧 bearerAuth 头缺失 |
| 无效 JWT，经真实 Authorize UI 后重开发送 | 同源 GET，HTTP/业务码401，页面呈现；没有绕过鉴权 |
| 缺失 JWT，新无凭据浏览器上下文直接导航 | 同一实际接口 HTTP/业务码401；此项为独立浏览器鉴权检查，不冒充 Knife4j 按钮操作 |
| CORS / OPTIONS / 控制台 | 同源8081，OPTIONS=0；静态资源无失败，警告0；仅无效 JWT 的预期401资源错误，未扩大CORS来源 |

脚本退出0、`passed=true`，浏览器已关闭。报告只记录头名称、形状、匹配布尔值和状态码，不保存密码、JWT或完整头值。新浏览器证据为40文件，10项秘密逐值比对及JWT模式均匹配0。

证据目录：`C:\Users\11707\.codex\visualizations\2026\10\21\01a15d06-fda3-7103-8747-15633f375863\knife4j-final\complete-2026-10-21T09-44-56-226Z\`，其中 `report.json`、`07-valid-jwt-ui-200.png`、`08-invalid-jwt-ui-401.png`、`09-missing-jwt-browser-401.png` 已核对。修复前401证据在同级 `original-2026-10-21T07-37-01-361Z/`，未覆盖。

### Java 与前端的最终状态

本轮新 JAR 均以 **cloud** 启动，实际健康为 UP：user PID6056/8082、booking PID21764/8081、gateway PID5952/8080。验收完成后只停止这三个记录且启动时间匹配的测试进程，便于直接使用 IDEA；它们不是当前仍运行的后台 JAR。六个中间件与 Desktop 保留运行。

前端已实际运行文末命令，Vite 在 `127.0.0.1:5173` 启动；页面200、应用挂载点及Vite客户端存在；`/api/rooms/list` 通过 Gateway 8080 代理后正常返回匿名401。该项不是完整Vue端到端测试。本次临时Vite亦已停止。

17:51 正常宿主权限收尾核实：三个原 JAR 均已停止，8080/8081/8082/5173 无监听；原六容器的完整 ID 未变、全部 running/healthy、重启计数0，七项项目挂载仍为D盘。对应 `host-cleanup-final.json`、`containers-after.json`。

Git 与秘密保护核对：原18个未提交文件中，只有本次新增回归所在测试和本报告发生预期变化；其余原有修改保持原样。OpenApiConfig 的新增生产差异仅3行；另有两个 ignored IDEA workspace 的已备份最小修改。五个私有配置哈希未变；私有env/properties、logs和IDEA workspace均未被Git跟踪且忽略规则有效。220个Git候选文本及90个新证据文本与18项新旧秘密逐值比对无匹配，新证据JWT模式亦无匹配，未打印秘密值。`git diff --check` 退出0，仅有既有行尾提示；没有commit/push/reset/clean。证据 `source-preservation-final.json`、`private-ignore-check.txt`、`secret-and-git-hygiene-final.json`、`git-diff-check-final.json`。这仅是跟踪与凭据保护检查，不是全项目审计。

### 剩余问题和未验证范围

Knife4j 的发送和鉴权已验收通过。部分既有会议室中文名称/设备在响应 JSON 与页面中显示乱码；本轮没有改这些数据，乱码来源尚未单独验证，不能宣称所有业务文字显示正常。后续处理数据前应先限定原因及范围，不直接覆盖或重新导入。

完整 Vue 浏览器业务流程、Nacos 控制台登录、其他浏览器/移动视口、本轮故障注入/HA/生产部署均未追加验证；它们不纳入本次成功项。没有全项目审计、额外功能开发、环境清理或 Git commit/push/reset/clean。

## 前轮实际恢复过程（14:00—14:30）

1. 初始 Engine 管道不存在。直接启动已有 Desktop 后，日志显示阻塞在 `docker-secrets-engine/engine.sock`；仅有零字节 socket 的运行时目录已只读核对。准备的目录改名操作因检测到 Desktop 已运行而在任何改名前中止，本轮没有移动这些目录。
2. 用户确认手动重启 Desktop 后保持运行。先观测到 Engine 29.7.2，随后为 Desktop 4.96.0 / Engine 29.9.1，Engine ID 一致。本轮没有运行安装器、升级命令或修改 Desktop 设置，不将外部版本变化记作本轮操作。
3. 枚举确认六个既有中间件容器、八个既有命名卷。两个 MySQL 及 Broker 仍绑定旧 C 盘项目路径，无法正常恢复；Redis、Nacos、NameServer 已健康。
4. 以无网络、只读文件系统和只读卷挂载的临时容器检查两个 MySQL 卷：系统库、业务库目录及 `auto.cnf` 均存在。未运行数据库初始化或修复命令。
5. 使用现有 Compose，从 D 盘重建两个 MySQL 和 Broker 的容器配置，仍挂载原来的同名数据卷；没有重建数据卷。启动限定为这三个服务，未启动 Java/前端容器。
6. 六个中间件均恢复 healthy。只读 SQL 确认原专用管理员 ID3、用户 ID4、会议室 ID5、已取消订单 ID1 及其 SENT Outbox 仍在。
7. 使用既有 JAR 和原私有 properties，从 D 盘启动三个宿主 Java 服务，完成下面的真实验收。没有改业务源码或依赖版本。

14:23 收尾复核：八个卷的名称、创建时间和挂载点全部与启动前一致；六个中间件 healthy、重启计数均为 0。已有源码未变，两个私有 env 和三个私有 properties 的 SHA-256 均未变。本轮仅新增/补充恢复文档和 ignored 验收证据。

### 桌面同名空目录的来源

Desktop 启动时，旧容器仍引用 `C:\Users\11707\Desktop\smartoffice-rebuilt`。Docker 自动创建了缺失的绑定源目录，时间为 14:09:23；其中 `schema.sql`、`broker.conf` 等名字也实际是目录。这不是项目源码复制。

迁移修复后，七项文件绑定均指向 D 盘项目。对桌面同名路径的完整检查没有发现文件或链接；其内部空目录已逐层清理，未删除文件。顶层空目录被另一个进程占用，未强行停止进程或递归删除。当前开发和验收输出都使用 D 盘。

相关证据：`containers-before.json`、`containers-after.json`、`compose-targets.json`、`migrated-bind-start.log`、`existing-data-continuity.json`、`old-vhd-after.json`、`volumes-after.json`、`source-preservation-after.json`、`desktop-placeholder-before-cleanup.json`、`desktop-placeholder-cleanup.json`。

## 前轮真实验收（14:15—14:30）

| 项目 | 本轮结果 | 证据 |
| --- | --- | --- |
| 六个中间件 | MySQL booking/user、Redis、Nacos、NameServer、Broker 均 healthy | 容器记录、`migrated-bind-start.log` |
| 原开发数据连续性 | 14:15 验收前四个用户、五个会议室、一条订单；原专用账号和验收记录存在 | `existing-data-continuity.json` |
| Redis 原应用 Lua | 21/21 通过，独立 UUID 键，TTL 最长 600 秒 | `redis-lua-host-dev-result.*.json` |
| 实际 HTTP/SQL/API | 89/89 通过，30 次 HTTP、10 次 SQL 读取 | `result.json` |
| Knife4j 浏览器发送 | 当时未通过；最终标准头修复及浏览器200/401结果见上方最终收尾节 | 前轮浏览器证据 |

真实 API runId：`recovery_c054d0a3bf674f769975`，14:16:48—14:16:57。包含三服务健康、真实库表、Nacos 注册、文档 HTTP、正确/错误密码、匿名及普通用户权限、容量 31 拒绝且未 INSERT、容量 30 成功、Nacos/Feign 预约、相同 requestId 返回同订单、重复取消。

真实 MQ 结果：本次订单对应 Outbox、SENT、消费回执、通知、审计各一条，且 SENT 有 Broker 消息 ID；六容器验收期间没有重启。账号均为既有账号，`createdThisRun=false`，没有注册或提升角色。

本次新增专用会议室 ID6、订单 ID2 及其消息记录均保留；只取消本次订单。Redis 临时键自行到期。没有删除业务数据。

上午已完成 Maven 118 项验证，本轮业务代码未改，因此不重复整套构建，不把旧 Maven 数字与本轮 89 项真实验收相加。Knife4j 的浏览器结果与后台 HTTP 验收分开记录。

### 前轮 Knife4j 未通过的定位记录

使用已有 Playwright 1.56.1 和 Edge，在 1440×1000 视口实际操作 `doc.html → listRooms → 调试`，原样发送、切换可见媒体类型、在真实 Authorize UI 输入 JWT 并失焦后发送，均未观察到会议室 GET。页面、API 树和静态资源正常，控制台错误及警告均为 0。没有自动注入 Authorization 请求头，没有用直接 HTTP 请求冒充 UI 发送。

一次定向方法跟踪确认：真实发送点击进入 `validateCommonHeaders()`，返回 `validate:false`、消息“请求头 bearerAuth 不能为空”，并调用 UI 提示；未进入 HTTP 发送方法。原始 `urlFormFlag=true`，推翻了上午离线准备的“三个媒体类型标志均未初始化”假设，切换媒体类型没有改善结果。

当时尚未确认 Authorize 值为何没有传播到端点调试请求头，未据此修改业务代码。随后最终收尾已定位旧调试页缓存及 bearer 请求头名称兼容问题，并用真实 UI 完成 GET 200 和负例401验证，详见上方最终收尾节。

Browser 插件不可用。普通进程沙箱首次启动在页面访问前遇到 Crashpad 管道错误，正常宿主权限下运行成功，保持 Chromium 沙箱启用；失败证据保留。浏览器已关闭，截图已遮罩输入，未保存 Token、密码或完整头信息。

本轮浏览器证据根目录为 `C:\Users\11707\.codex\visualizations\2026\10\21\01a15d06-fda3-7103-8747-15633f375863\knife4j\`。这是本会话允许的工具证据目录，不是桌面项目目录：

- `run-2026-10-21T06-22-40-018Z/report.json`：完整 UI 操作及发送未发生的结果。
- `diagnostic-2026-10-21T06-25-52-257Z/report.json`：具体请求头校验守卫。
- `evidence-privacy-check.json`：30 个新证据文本、10 项秘密值及 JWT 模式均未匹配。

收尾通过持有脚本的唯一停止信号，仅停止其记录且 PID/启动时间匹配的三个测试 JAR（user12728、booking9700、gateway16656），释放端口供 IDEA 使用；六个中间件和 Desktop 保持运行。

14:30 正常宿主权限复查确认：三个原测试进程已停止，8080/8081/8082 无监听；六个中间件仍 healthy，旧盘仍未挂载。新增证据文本的限定检查为 73 个文件、18 项新旧秘密值及 JWT 模式，匹配 0；Git diff 空白检查退出 0。对应 `host-cleanup.json`、`new-evidence-hygiene.json`、`git-diff-check.json`。该检查不属于全项目审计。

## 每天直接照做的开发步骤

### 1. 开启 Desktop，启动既有六个中间件

每天需要 Docker Desktop 的 Linux Engine 运行；若已运行就无须重复打开。现有镜像和卷已就绪，不需要每天 pull、build、生成密码或导入 SQL。

```powershell
Set-Location D:\A-project\smartoffice-rebuilt
docker --context desktop-linux version
$taskDockerEndpoint = docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}'
if ($LASTEXITCODE -ne 0 -or $taskDockerEndpoint -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
  throw '不是已核实的本机 Linux Engine，停止操作。'
}

# 只启动已经存在的六个中间件，不创建卷、不重建容器。
docker --context desktop-linux compose -p smartoffice-host-dev --env-file deploy/.env.host-dev -f deploy/docker-compose.upgrade.yml -f deploy/docker-compose.host-dev.yml start --wait --wait-timeout 180 mysql-booking mysql-user redis nacos namesrv broker
```

`start` 已在当前环境执行成功。若提示资源不存在，应先排查，不能改用初始化 SQL 或创建空卷来掩盖问题。环境变化后再核对八卷及旧盘：

```powershell
docker --context desktop-linux volume ls --filter label=com.docker.compose.project=smartoffice-host-dev
$taskOldDisk = Get-DiskImage -ImagePath 'D:\DockerData\DockerDesktopWSL\disk\docker_data.vhdx' -ErrorAction Stop
if ($taskOldDisk.Attached) { throw '旧盘已挂载，先核对用途。' }
```

### 2. 中间件连接地址

| 中间件 | Windows 宿主连接 | 凭据/用途 |
| --- | --- | --- |
| MySQL booking | `127.0.0.1:13306/smartoffice_booking` | 私有 env 的 `BOOKING_DB_USERNAME` / `BOOKING_DB_PASSWORD` |
| MySQL user | `127.0.0.1:13307/smartoffice_user` | `USER_DB_USERNAME` / `USER_DB_PASSWORD` |
| Redis | `127.0.0.1:16379`，默认 DB0 | `REDIS_PASSWORD`；不要连接或清理其他项目实例 |
| Nacos | 服务 HTTP `127.0.0.1:18848`；gRPC `19848/19849` | 控制台 `http://127.0.0.1:18880`；控制台账户独立，本轮未初始化或重置 |
| RocketMQ | NameServer `127.0.0.1:19876`；Broker `10909/10911/10912` | 宿主 Java 使用私有 properties 中的 NameServer 地址；本配置没有另加 MQ 控制台 |

真实密码只在 ignored `deploy/.env.host-dev` 和 `backend/.env.host-dev-*.properties`。不要粘贴到共享运行配置、命令行或 Git。这里使用的是独立开发库，不连接本机原生 MySQL 的 3306。

### 3. IDEA 启动三个 Java 服务

打开 `D:\A-project\smartoffice-rebuilt\backend\pom.xml`，加载四个 Maven 模块；Project SDK、Maven importer/runner 用 JDK17。已有三个 Spring Boot 配置已补齐下列工作目录和参数。依次启动，等前一服务健康后再启动下一项：

| 顺序 / 运行配置 | Main class | 端口 | Program arguments |
| --- | --- | --- | --- |
| 1 / UserApplication | `com.geek.smartoffice.user.UserApplication` | 8082 | `--spring.config.additional-location=file:./.env.host-dev-user.properties` |
| 2 / SmartOfficeApplication | `com.geek.smartoffice.SmartOfficeApplication` | 8081 | `--spring.config.additional-location=file:./.env.host-dev-booking.properties` |
| 3 / GatewayApplication | `com.geek.smartoffice.gateway.GatewayApplication` | 8080 | `--spring.config.additional-location=file:./.env.host-dev-gateway.properties` |

三个 Working directory 都是 `D:\A-project\smartoffice-rebuilt\backend`（backend IDEA 工程中的 `$PROJECT_DIR$`）。私有配置已设置 **cloud** Profile、loopback 地址和 Nacos 发现；Active profiles 可留空或显式 cloud，不要设 local。不要在运行配置中残留旧 DB_URL、SERVER_PORT、USER_SERVICE_URL 或其他容器地址覆盖。

依赖：user 使用独立 MySQL user 库及 Nacos；booking 使用 booking 库、Redis、Nacos、RocketMQ，并通过 Feign 调用 user；gateway 使用 Nacos 的服务名路由。shared 是公共 JAR，不单独启动。

启动后分别访问 `http://127.0.0.1:8082/actuator/health`、`8081/actuator/health`、`8080/actuator/health`，预期 `status=UP`。不要同时用 IDEA 和 JAR 启动同一服务。

### 4. 前端、接口与 Knife4j

另开一个 PowerShell：

```powershell
Set-Location D:\A-project\smartoffice-rebuilt\frontend
$env:VITE_GATEWAY_URL = 'http://127.0.0.1:8080'
npm.cmd run dev -- --host 127.0.0.1 --strictPort
```

前端为 `http://127.0.0.1:5173`。现有依赖已在本机，不需要每天 npm install。Vite 将同源 `/api` 转到宿主 Gateway 8080；Gateway 的 `/api/users/**` 转 user，`/api/rooms/**`、`/api/orders/**` 等转 booking 并去掉 `/api`。cloud 使用 Nacos 服务名，不改成固定业务端口。

- 浏览器/前端的后端入口：`http://127.0.0.1:8080/api`。
- Knife4j：`http://127.0.0.1:8081/doc.html`，其调试直接访问 booking 的 `/rooms/list`，没有 `/api`；不要把 8081 文档误当作 Gateway 聚合文档。
- OpenAPI：`http://127.0.0.1:8081/v3/api-docs`。
- 在 Authorize 的 bearerAuth 一项输入 `Bearer <登录 JWT>` 并失焦保存；**关闭已打开的 listRooms 页签，再从左侧重新打开后调试发送**。该版本已打开的调试页不会自动刷新授权缓存。JWT 仅本机临时使用，不写报告或截图。

### 5. 日志与停止

Java 日志看 IDEA 的 Run/Debug 控制台；私有配置另写入 `D:\A-project\smartoffice-rebuilt\backend\logs\host-dev-user.log`、`host-dev-booking.log`、`host-dev-gateway.log`。例如：

```powershell
Get-Content D:\A-project\smartoffice-rebuilt\backend\logs\host-dev-booking.log -Tail 80 -Wait

# 下列 Docker 命令从项目根执行，始终固定项目名和服务列表。
Set-Location D:\A-project\smartoffice-rebuilt
docker --context desktop-linux compose -p smartoffice-host-dev --env-file deploy/.env.host-dev -f deploy/docker-compose.upgrade.yml -f deploy/docker-compose.host-dev.yml ps
docker --context desktop-linux compose -p smartoffice-host-dev --env-file deploy/.env.host-dev -f deploy/docker-compose.upgrade.yml -f deploy/docker-compose.host-dev.yml logs -f --tail 80 mysql-booking mysql-user redis nacos namesrv broker
```

开发结束先在 IDEA 按 Stop 停止 gateway、booking、user；前端终端 Ctrl+C。确需停止 SmartOffice 中间件时：

```powershell
Set-Location D:\A-project\smartoffice-rebuilt
docker --context desktop-linux compose -p smartoffice-host-dev --env-file deploy/.env.host-dev -f deploy/docker-compose.upgrade.yml -f deploy/docker-compose.host-dev.yml stop --timeout 30 mysql-booking mysql-user redis nacos namesrv broker
```

以上只作用于 `smartoffice-host-dev` 的六服务，保留八个卷；不操作 PetTravel。若其他项目仍依赖 Desktop，保持 Desktop 运行。不要全局停止所有容器或 Java 进程，不使用 Factory Reset、prune、`down -v`，不对旧 VHDX 执行移动、覆盖、删除或挂载。

本轮不含旧盘历史数据恢复、生产部署、HA/故障注入、压测、完整 Vue 端到端或 Nacos 控制台登录，也没有重新审计全项目。
