# SmartOffice 本地开发恢复续验报告（2026-10-21）

> **D 盘迁移后续接：** [最新实际恢复与 D 盘每日命令](docker-development-recovery-d-drive-20261021.md)。下午已复用原八卷恢复六个中间件，真实 API/SQL 89 项和 Redis Lua 21 项通过。下文保留上午 Engine 阻塞时的检查快照。

本报告区分本次执行与 [2026-10-20 恢复记录](docker-development-recovery.md)。**截至本次检查，Docker Engine 未恢复，今天的真实 MySQL/Redis/MQ/Nacos 和业务 HTTP 验收尚未执行。** 昨日的 118 项 Maven、90 项真实验收不能替代今天的结果。

## A. 恢复了哪些上下文和既有成果

- 仓库分支仍为 `目录迁移工作分支`，HEAD `65e65fb`；没有 reset、撤销、暂存、提交或推送。
- 阅读昨日恢复报告、计划、真实验收 JSON、完整 Maven 记录、进程清理记录和旧盘基线。确认昨日六个中间件/八个独立卷已建立并验收，三个测试 JAR 在收尾时已停止。今天没有重新初始化数据库。
- 既有部署覆盖、配置生成脚本、文档 GET 安全开关、Knife4j enhancement 兼容性修复和两组回归测试均保留。
- 保留用户 DTO Max30（原消息仍写100）、RoomCacheStore、POM 格式变更、backend.zip 和新出现的 smartoffice-directory.txt，未覆盖这些文件。
- 今天只补充续验文档、进度记录和 ignored 的定向探针/证据；未修改业务 Java、数据库 SQL、原 Compose 或现有私有配置。

初始 `git status --short` 为 9 个 tracked 修改和若干 untracked 新文件；`git diff --stat` 为 tracked `151 insertions / 48 deletions`，不包含 untracked 文件，不能与 UI 的历史行数直接等同。`git diff --check` 退出0，只有既有 LF/CRLF 提示。

本次证据目录：`logs/docker-recovery-20261021/`（Git ignored）。昨日目录保持原样。

## B. 旧数据盘保护

实际只读执行 `Get-DiskImage`、文件元数据、Desktop settings 指定字段及完整 `Get-FileHash -Algorithm SHA256`：

| 检查 | 本次结果 |
| --- | --- |
| D:\DockerData\DockerDesktopWSL\disk\docker_data.vhdx | 存在，12,149,850,112 字节，Attached=false |
| Desktop 配置的 WSL 数据目录 | C:\Users\11707\AppData\Local\Docker\wsl |
| 旧盘长度、修改时间、完整 SHA256 | 与昨日基线全部相同 |
| 当前 C 盘数据文件 | 存在，6,682,574,848 字节，初始最后修改于10月20日23:30；不能仅据此证明内部容器/卷健康 |
| 对旧盘写入、挂载或迁移 | 未执行 |
| PetTravel、本机原生 MySQL | 未连接或修改 |

证据 `old-vhd-check.json`。当前 Engine 没有运行，准确说法是 Desktop **配置指向 C 盘**，旧 D 盘未挂载；不能把它描述为当前有运行中的 Engine 正在写 C 盘。

启动新环境不要求移动旧盘。以后若备份：先确认旧盘未挂载且无写入者，选择空间充足的独立目录复制整盘，再比较源和副本 SHA256；不要直接复制活动盘并宣称一致，不替换当前 C 盘数据。此次未擅自复制、清理或修复旧盘。

## C. Docker 故障和中间件状态

### 实际排查与启动结果

1. 正常权限执行 `docker version / info / context ls / compose version / ps -a / images / volume ls / network ls`。CLI 29.7.2、Compose 5.4.0 可用；desktop-linux 指向固定本机 named pipe。**Server 管道不存在，资源清单命令失败，当前容器/镜像/卷状态未知，不能记为0或已丢失。**
2. 初始无 Docker Desktop/backend 进程，Ubuntu 和 docker-desktop WSL 均 Stopped，目标 Java/中间件端口无监听。
3. 常见全用户安装路径不存在；通过 `Get-Command docker` 找到真实按用户安装位置：`C:\Users\11707\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe`。仅启动这个已有程序，未重装或升级。
4. Desktop 启动后90秒内 Engine 未就绪。后台日志实际报错：`initializing Ingest server ... sailor-ingest.sock ... The file cannot be accessed by the system`，随后 `engines shutdown complete` / `backend crashed`。这是今天的直接启动阻塞，不是已证明的网络拉取失败或数据库损坏。
5. 对 `C:\Users\11707\AppData\Local\Docker\run\sailor-ingest.sock` 只读检查：0字节、Archive/ReparsePoint、最后修改10月20日21:17；`fsutil reparsepoint query` 返回 Windows Error1920。没有删除/重命名 socket 或目录、改 ACL、改全局配置、关 WSL、reset 或动卷。
6. `docker desktop status` 同样无法取得状态；已结束本次等待，没有反复重试相同启动命令。
7. 已请用户从 Windows 开始菜单手工打开 Desktop；若有错误窗口先 Quit 后重开，不选 Factory Reset。目的是对照启动上下文；**尚不能断言 MSIX、文件权限或 Windows 本身是最终根因。**

12:25:59 收尾再次单次检查：`docker --context desktop-linux version --format '{{.Server.Version}}'` 退出1，同一本机管道仍不存在。证据 `final-engine-check.json`。用户手工启动结果尚未回复，因此没有继续枚举失败命令或创建资源。

脱敏启动日志摘录：`desktop-start-diagnostic.json`。原日志位置：`C:\Users\11707\AppData\Local\Docker\log\host\com.docker.backend.exe.log`。这些日志未加入 Git。

Docker 的[官方问题仓库记录过相似 socket 启动失败](https://github.com/docker/desktop-feedback/issues/531)。该公开问题记录不是本机根因的证明；本轮未照搬其中的全局目录改动、升级或清理操作。

| 中间件 | 预期本机地址 | 今天启动/健康 | 昨日证据，仅作背景 |
| --- | --- | --- | --- |
| MySQL booking | 127.0.0.1:13306 | 未验证，Engine 阻塞 | healthy，8.0.46，独立新卷 |
| MySQL user | 127.0.0.1:13307 | 未验证，Engine 阻塞 | healthy，8.0.46，独立新卷 |
| Redis | 127.0.0.1:16379 | 未验证，Lua 未执行 | 认证读写通过 |
| Nacos | HTTP18848、gRPC19848/19849、console18880 | 未验证 | 三个实例注册发现通过 |
| RocketMQ NameServer/Broker | 19876；10909/10911/10912 | 未验证，不以 Running 代替消息验收 | 真实 Outbox SENT、通知/审计/消费记录各1 |

### 初始化/迁移安全核对

沿用项目 `smartoffice-host-dev`，预期网络 `smartoffice-host-dev_default`，八个卷均为同项目前缀；原 compose + host-dev 覆盖没有重新设计。`compose config -q` 今天退出0。

- booking 初始化为 `01 schema.sql → 02 message-schema.sql → 03 data.sql`；user 为 `01 schema.sql → 02 data.sql`，仅空 MySQL 数据目录首次执行。
- 当前 booking schema 已含 request_id 和联合唯一索引；`deploy/migrations/V1__booking_request_id.sql` 是给旧表的非幂等 ADD，不应对昨日新库再执行。
- 三个私有 properties 已存在；再次执行 Prepare-HostDevelopment 后全部保留，两个 env 也未改变。相关文件 SHA 前后仅比较布尔值，不输出配置内容。SQL init 仍为 never。
- 现有 real-migration-check / real-redis-lua-check 等原探针指向 smartoffice-upgrade / .env.upgrade，不直接拿来操作 host-dev。今天未运行它们、未重放 demo SQL、未创建新的 MySQL 数据卷。

## D. Java 服务与本次构建

本次真实命令（cwd backend，JDK17）：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& 'D:/apache-maven-3.9.4-bin/apache-maven-3.9.4/bin/mvn.cmd' -B -ntp -o verify
```

**2026-10-21 12:15:29 BUILD SUCCESS，1分13秒；118项，0 failures/errors/skipped**：shared26、user15、booking71、gateway6。三JAR打包成功。证据 `maven-verify.log` / `surefire-summary.json`。这些隔离测试包含 H2/Mock，不冒充今天真实中间件通过。

| 服务 | 端口 / profile | 今天结果 | 昨日结果 |
| --- | --- | --- | --- |
| user-service | 8082 / cloud | 构建成功，未启动；依赖 Engine | actuator UP、真实MySQL登录通过 |
| booking-service | 8081 / cloud | 构建成功，未启动；依赖 Engine | actuator UP，Redis/Feign/MQ链路通过 |
| gateway | 8080 / cloud | 构建成功，未启动；依赖服务 | actuator UP，Nacos发现路由通过 |

今天未留下新的 Java/浏览器服务进程。私有配置匹配检查确认 cloud、loopback、两个独立数据库、JWT一致、Redis/MQ地址和Feign发现模式符合昨日已验证方式，证据 `config-and-credential-check.json`；这是配置检查，不是运行验收。

## E. 接口和 Lua 验收状态

| 必验项 | 今天真实结果 | 昨天实际结果，仅供对照 |
| --- | --- | --- |
| 正确登录 | 未执行，Engine 阻塞 | 新管理员/普通账号登录200 |
| 错误密码 | 未执行，不能只凭源码判通过 | 昨日90项未覆盖，今天探针已补401及明确凭据错误断言 |
| Gateway /api/rooms/list | 未执行 | 登录200，匿名401 |
| 直接8081 /rooms/list | 未执行 | 匿名401；今天补登录后200断言 |
| 管理员capacity30 | 未执行 | HTTP200，创建专用房间 |
| 管理员capacity31 | 未执行 | HTTP400、code400，SQL确认未INSERT |
| 普通用户/匿名创建 | 未执行 | 普通用户403；今天补匿名POST401断言 |
| 真实Redis Lua/旧值回填防护 | 未执行 | 昨日恢复90项只含认证读写，不是Lua测试 |
| 真正MQ生产/消费 | 未执行 | Outbox SENT + 消费/通知/审计各1 |
| Knife4j实际浏览器发送 | 未执行 | 页面/授权输入验证，发送后无GET，整体未通过 |

准备但未执行的本次脚本：

- `logs/docker-recovery-20261021/verify-host.mjs`：复用昨日脚本的固定本机 endpoint、project/service 标签和完整容器ID边界；增加错误密码（Gateway及直接user）、直接booking查询、匿名创建权限断言。保留已有专用账号密码，不覆盖；新测试房间/订单使用唯一 runId，保留数据，只取消自己的预约。
- `logs/docker-recovery-20261021/real-redis-lua-host-dev-check.mjs`：要求预先核实的 Engine ID 和完整 Redis 容器ID；固定本机 named pipe、host-dev 标签、镜像和运行状态；从应用源码提取实际Lua，使用独立UUID键、最长600秒TTL，仅INVALIDATE删除本次detail键，不做全局清理。语法检查通过，未接触Redis。
- Knife4j 定向离线检查发现一种可验证假设：默认媒体类型已显示，但发送分支的三个布尔标志可能均未初始化，造成静默无请求。尚未在真实服务上证实，不能记作已修复；没有为此修改业务代码。

Knife4j 本次离线方法测试及两个脚本语法检查退出0；隔离浏览器夹具在访问页面之前因Edge Crashpad管道错误退出1，不是应用页面失败或通过的证据，未再重复启动。没有遗留该子任务的Node/Edge进程。准确离线报告/探针在 `[历史本机验证资料，未随仓库公开]` 的 `offline-findings.md`、`send-probe.mjs`、`static-send-test-result.json`、`fixture-evidence/report.json`；真实浏览器验收仍未执行。

目前 capacity 实际注解是 `@Max(30)`，用户原文案仍为“会议室容量最多为100”；保留原修改，不把消息文字误判成允许容量100。

## F. 凭据和 Git 安全

仅在内存读取并比较，未打印值：

- 确认旧工具记录中的秘密类别正是 **JWT_SECRET、NACOS_AUTH_TOKEN、NACOS_AUTH_IDENTITY_VALUE**。没有发现该记录包含新环境八项秘密；没有证据表明此次误输出涉及数据库或Redis密码。
- 具体记录在 `[历史本机验证资料，未随仓库公开]`。此文件在项目外，未修改、删除或纳入Git。
- 新环境八项秘密均存在、非占位符且与旧值不同；两开发账号沿用昨日独立密码。原 `.env.upgrade` 及旧properties保留，当前开发方式不使用它们。未来重启旧环境前需要协调轮换上述三个旧秘密，JWT轮换会让旧Token失效；不擅自改其他项目共享凭据。
- `deploy/.env.host-dev`、`.env.upgrade`、新旧宿主properties、logs、target、node_modules、backend.zip均被忽略；Git候选没有私有env。新旧18个秘密值与源码候选/本轮日志逐项比对无匹配，证据 `source-hygiene-final.json`。这不能撤回已进入历史工具记录的旧秘密。
- 专用账号：`dev_admin_20261020`、`dev_user_20261020`；密码仅保存在本机私有env的 DEV_ADMIN_PASSWORD / DEV_USER_PASSWORD，不写进文档、提交、终端输出或共享运行配置。

## G. 每日 PowerShell / IDEA 开发步骤

**先从 Windows 开始菜单打开 Docker Desktop，确认 Engine Running。当前 socket 错误未解决前不要执行 reset、重装、改全局数据目录、移走runtime目录或重新初始化数据库。**

Engine恢复后，从仓库根使用以下现有命令。静态config与配置保留已验证；下列容器启动及运行验收尚未完成，不能记为通过：

```powershell
Set-Location D:\A-project\smartoffice-rebuilt
docker --context desktop-linux version
$taskDockerEndpoint = docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}'
if ($taskDockerEndpoint -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
    throw '不是已核实的本机Docker Linux Engine，停止操作'
}
$taskOldDisk = Get-DiskImage -ImagePath 'D:\DockerData\DockerDesktopWSL\disk\docker_data.vhdx'
if ($taskOldDisk.Attached) { throw '旧D盘已挂载，需要先确认用途，不继续创建资源' }

docker --context desktop-linux compose -p smartoffice-host-dev --env-file deploy/.env.host-dev `
  -f deploy/docker-compose.upgrade.yml -f deploy/docker-compose.host-dev.yml config -q
docker --context desktop-linux compose -p smartoffice-host-dev --env-file deploy/.env.host-dev `
  -f deploy/docker-compose.upgrade.yml -f deploy/docker-compose.host-dev.yml `
  up -d --no-build --wait --wait-timeout 360 mysql-booking mysql-user redis nacos namesrv broker
docker --context desktop-linux compose -p smartoffice-host-dev --env-file deploy/.env.host-dev `
  -f deploy/docker-compose.upgrade.yml -f deploy/docker-compose.host-dev.yml ps
./deploy/upgrade/Prepare-HostDevelopment.ps1
```

先核对现有容器、卷和端口；不要省略上述六服务列表去启动Java/前端容器。host-dev Broker公告127.0.0.1专供Windows宿主Java。现有密码不能随意重生成，因为不会自动同步已有MySQL卷。

IDEA Open `backend/pom.xml`，Project SDK / Maven importer / runner 使用JDK17，Reload四模块。迁移后三个 Spring Boot 配置的Working directory固定为 `D:\A-project\smartoffice-rebuilt\backend`。Private properties已启用cloud，不用local；清理旧运行配置里冲突的profile/端口/容器地址。

| 顺序 / Main class | Program arguments |
| --- | --- |
| 1 user / com.geek.smartoffice.user.UserApplication | `--spring.config.additional-location=file:./.env.host-dev-user.properties` |
| 2 booking / com.geek.smartoffice.SmartOfficeApplication | `--spring.config.additional-location=file:./.env.host-dev-booking.properties` |
| 3 gateway / com.geek.smartoffice.gateway.GatewayApplication | `--spring.config.additional-location=file:./.env.host-dev-gateway.properties` |

数据库连接分别为127.0.0.1:13307/smartoffice_user、13306/smartoffice_booking，用户名密码在私有properties；无须连接本机原生3306数据库。cloud通过Nacos发现；local是原直连开发方式，不用于本次Nacos验收。启动后看三个 `/actuator/health`，再用Gateway `POST http://127.0.0.1:8080/api/users/login` 登录。Knife4j地址 `http://127.0.0.1:8081/doc.html`，尚需补完浏览器发送验证。

## H. 完成度与下一步边界

| 类别 | 本次状态 |
| --- | --- |
| 已完成并验证 | 昨日成果保留；本次118项Maven及打包；Compose静态解析；私有配置不覆盖及匹配；旧VHD完整哈希不变；凭据类别/新旧隔离/Git忽略 |
| 已准备但未运行 | 本次真实API扩展探针、host-dev真实Redis Lua探针、三个JAR宿主启动探针 |
| 因环境限制未完成 | Docker Engine恢复后的资源清单、容器健康、真实库/缓存/MQ/注册发现、HTTP和Knife4j浏览器验收 |
| 需要用户操作 | 从Windows开始菜单手工Quit/打开Desktop，报告是否Engine Running；若仍报错，提供脱敏错误文字即可，不要Reset |
| 需确认后才可考虑 | 任何Docker升级/重装、全局配置/目录变动、旧盘操作或数据库覆盖；本轮均未执行 |

当前不能宣称“今天环境已恢复并全部通过”。如果手工启动仍失败，应先针对已保存的socket错误选择可保留数据的方案；不通过删除数据、绕过权限或无限重复同一失败命令推进。
