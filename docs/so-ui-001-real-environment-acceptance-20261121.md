# SO-UI-001 真实中间件最终验收

日期：2026-11-21，Asia/Shanghai。结论：本次要求的真实 MySQL、Redis、Gateway、用户服务、预约服务及 RocketMQ 基础投递/消费验收通过；未发现需要修改业务代码的问题。[PR #4](https://github.com/linkbb110-bit/SmartOffice/pull/4) 保持打开，未合并，等待人工确认。

后续已按用户要求整理中文演示数据：当前同一入口切换至新演示预约库，旧验收库保留，新的进程路径和初始化方式见 [中文演示数据修复记录](demo-data-cleanup-20261121.md)。下文为本次真实中间件验收时的历史环境记录。

## 验收对象与隔离方式

- 项目：`D:\A-project\smartoffice-rebuilt`；分支：`feat/admin-console`。
- 被测业务源码提交：`abaeb1729bf036377d077f8850d38179e530052f`，即验收开始时 PR #4 的 HEAD。
- 使用 `git archive HEAD` 导出干净源码至 `logs/so-ui001-final-20261121/source`，Java 17 编译生成本次测试 JAR。没有使用原工作区已有 target JAR，也没有将用户 13 个未提交后端文件混入被测服务。
- Java 使用 **local Profile + MySQL 驱动**，没有使用 test/H2 Profile。用户服务地址显式指向隔离实例，Gateway 使用独立路由配置，Nacos 注册/发现关闭。
- Compose 项目唯一标识：`so-ui001-final-20261121`。全新两个 MySQL 容器、一个 Redis、一个 NameServer、一个 Broker；独立网络和六个新卷，全部使用现有本机镜像，未重建旧容器或旧卷。
- Docker Desktop 设置仍为 `D:\DockerData\DockerDesktopWSL\DockerDesktopWSL`。Docker Engine 内部路径为 `/var/lib/docker`；此 Linux 路径由上述 D 盘数据镜像承载。
- Docker 启动前 Engine 未运行；只启动已有 Desktop，未修改设置。已有容器随其原重启策略恢复运行，没有对现有项目执行 Compose 启停或数据库操作。
- 数据库结构和演示账号仅通过新 MySQL 的初始化目录导入干净源码中的 SQL，原业务数据库没有作为测试目标。

## 环境地址与实测状态

| 组件 | 独立地址 | 数据/配置 | 状态 |
| --- | --- | --- | --- |
| MySQL 8.0.46 用户库 | `127.0.0.1:23307` | `so_ui001_user_20261121`，账号 `qa_user` | healthy |
| MySQL 8.0.46 预约库 | `127.0.0.1:23306` | `so_ui001_booking_20261121`，账号 `qa_booking`，InnoDB | healthy |
| Redis | `127.0.0.1:26379` | 独立服务，DB 0，有密码 | healthy |
| RocketMQ 5.3.1 NameServer | `127.0.0.1:29876` | 独立网络与日志卷 | healthy |
| RocketMQ Broker | `127.0.0.1:20911`，VIP `20909`，HA `20912` | `ui001-final-broker`，新 store/logs | healthy |
| user-service | `http://127.0.0.1:21802` | Java 17.0.17，local Profile | `/actuator/health` UP |
| booking-service | `http://127.0.0.1:21801` | Java 17.0.17，local Profile，真实 Redis/MQ | `/actuator/health` UP |
| Gateway | `http://127.0.0.1:21800` | Java 17.0.17，local Profile，路由到以上两服务 | `/actuator/health` UP |
| Vue/Vite | `http://127.0.0.1:25173` | `/api` 同源代理到隔离 Gateway | HTTP 200 |

测试账户为新用户库中初始化的 `user`/`admin`，角色分别为 USER/ADMIN。密码、JWT 及数据库/Redis 密码不写入报告。服务端原有鉴权、CORS 来源限制保持启用。

## 浏览器、HTTP 与 SQL 验收结果

使用真实 Chromium，所有成功业务响应均来自隔离服务，没有 Mock 成功响应或伪造数据。18 组业务验收全部通过；另执行手机键盘回归和八个页面的健康检查。

| # | 场景 | 观测与验证结果 |
| --- | --- | --- |
| 1 | 独立 MySQL 与初始状态 | 实测 MySQL 8.0.46；预约初始 0 条；订单、Outbox、通知、回执表均为 InnoDB |
| 2 | 普通用户、管理员登录 | 浏览器经 Gateway 登录成功，`/users/me` 分别返回 USER/ADMIN |
| 3 | 管理员新增会议室 | UI POST 成功，测试会议室 ID 5；MySQL 确认存在；详情 API 连续读取以预热真实 Redis 缓存 |
| 4 | 管理员修改与缓存一致性 | UI 修改名称/维护状态成功；已预热的详情立即反映更新；普通用户维护会议室按钮禁用；恢复后详情为可预约 |
| 5 | 普通用户预约及幂等重放 | UI 创建订单 ID 1；原样 requestId 重放仍返回 ID 1；数据库仅 1 个订单、1 条 Outbox |
| 6 | 时间冲突 | 同会议室、同时间的新预约返回 HTTP 409，表单保留；订单与 Outbox 计数均不增加 |
| 7 | 管理员删除冲突 | 存在未结束预约时删除返回 HTTP 409；UI 保留该行，MySQL `is_deleted=0` |
| 8 | ADMIN API 授权 | 普通用户直接调用全部预约、Outbox、会议室新增/修改/删除及 CSV 导出六类接口，全部 HTTP 403；前端管理路由也拒绝 |
| 9 | 本人取消与重复取消 | UI 取消成功，数据库 status=2；重复取消仍成功，未产生额外订单 |
| 10 | 取消后重新预约 | 同一会议室和时间重新提交，新 requestId 生成订单 ID 2；旧单取消、新单有效，Outbox 总数为 2 |
| 11 | 全部预约及 CSV | 管理页显示两单与真实用户 ID；下载 `bookings.csv`，HTTP 200、`text/csv`、227 字节，文件含两条实际测试预约 |
| 12 | 实际消息投递/消费 | 两条 Outbox 均 SENT 且有 Broker 消息 ID；通知、业务审计、本组消费回执各 2 条 |
| 13 | Outbox 监控与详情 | 真实 API 和页面均读取两条 SENT 记录；详情可打开，字段与 MySQL 记录对应 |
| 14 | 通知中心与用户隔离 | 普通用户页面显示两条消息消费产生的通知；管理员本人通知列表为 0，无跨用户混入 |
| 15 | 管理员取消他人预约 | UI 取消普通用户第二单成功；MySQL status=2；普通用户刷新后显示已取消 |
| 16 | 最终删除及历史保留 | 两单取消后删除成功，会议室为逻辑删除；详情返回 404、列表消失；原两单及两条 Outbox 仍保留 |
| 17 | 真实 Redis 锁与浏览器异常 | 观测到预约房间锁的 EVAL/EVALSHA 共 8 次；服务日志无 Redis 降级告警；浏览器脚本异常 0 |
| 18 | 手机页面 | 390×844 正常显示两条真实 Outbox 数据；表格内部滚动，文档无横向溢出 |

预约测试使用隔离房间和 2099-03-04 09:00–10:00 的未来时间，未写入原业务库。ID 5、1、2 均属于独立测试库，不是原业务系统中的同号数据。

### 权限请求明细

普通用户使用有效 JWT，以下请求均为 HTTP 403，而非仅通过隐藏菜单证明权限：

| 方法 | Gateway 后端路径 | 结果 |
| --- | --- | --- |
| GET | `/orders` | 403 |
| GET | `/admin/messages/outbox` | 403 |
| POST | `/rooms` | 403 |
| PUT | `/rooms/5` | 403 |
| DELETE | `/rooms/5` | 403 |
| GET，Accept: text/csv | `/orders/export` | 403 |

授权请求使用标准 `Authorization` 请求头。证据记录只保存头名称、请求路径、方法和状态码，不保存 JWT 值。

### RocketMQ 与数据库交叉证据

- 独立 topic：`ui001-final-20261121-booking`。
- 独立 producer group：`ui001-final-20261121-producer`。
- 独立 consumer group：`ui001-final-20261121-notification`。
- `booking_outbox`：2 条 SENT，2 个非空 Broker 消息 ID。
- `message_consumption`：2 条，均属于上述消费组。
- `booking_notification`：2 条，每条对应一个测试预约事件。
- `business_event_audit`：2 条，每条对应一个测试预约事件。
- 首次 requestId 重放和冲突请求均没有产生第三个订单事件。
- 通知不是通过测试 SQL 手工插入；SQL 检查只读，通知由真实 Broker 的监听消费事务产生。

以上证明本次两条消息的实际投递和消费结果，不据此宣称任意故障下的全局 exactly-once 保证。SENT 本身只表示投递，本次另核对消费回执、通知和审计。

### Redis 证据与范围

使用独立 Redis 的 MONITOR 连接，在内存中过滤 `smartoffice:booking:room:<id>` 的 EVAL/EVALSHA，只保留命中次数，未保存原始命令、AUTH 参数或 Lua 参数。本次计数 8，符合预约、幂等重放、冲突及重预约期间的锁获取/释放调用；日志降级告警为 0。详情缓存先读、修改后读、删除后 404 也已验证。

## 编译、前端及页面健康

| 检查 | 结果 |
| --- | --- |
| `npm test` | 31/31 通过，0 失败、0 跳过 |
| `npm run build` | vue-tsc 类型检查、Vite 生产构建通过 |
| Node | 26.13.0 |
| 干净源码 Java 17 构建 | Maven 离线 `-DskipTests package`，四模块及父工程全部 BUILD SUCCESS |
| 手机键盘回归 | 通过：关闭侧栏不聚焦、打开移入、双向 Tab 循环、Escape 返回和路由后关闭 |
| 八个页面健康检查 | 工作台、会议室、我的预约、通知、个人中心、管理会议室、全部预约、Outbox 均标题正确、非空、无 Vite 错误覆盖层 |
| 正常页面控制台 | error=0，warning=0，pageerror=0 |
| 响应式 | 1440×1000、390×844，截图已检查 |

业务测试中的预期 403/409/404 是负向用例结果。正常页面控制台检查独立执行，没有把这些预期失败误判成页面通过或脚本异常。构建保留已有 PURE 注释和大于 500 KB chunk 的非致命提示。

本轮没有业务源码修改，未执行 `mvn verify`；上述 Maven 命令用于生成干净被测 JAR，测试执行跳过，并非新的后端单元测试通过声明。实际业务结果来自本轮真实服务、浏览器及数据库检查。

## 数据、代码和秘密值保护复核

- 原有 **11/11 数据卷**元数据保持一致：原八个 SmartOffice 卷、两个 PetTravel 卷、一个 security-redis 卷。
- 原有 **9/9 容器**的 ID、镜像 ID、挂载保持一致。没有替换或删除原容器，没有将新测试服务连入原库或原 Redis。
- 新增卷仅六个，全部为 `so-ui001-final-20261121_` 前缀：`booking_mysql`、`user_mysql`、`redis`、`namesrv_logs`、`broker_logs`、`broker_store`。没有新增无法归属的匿名卷。
- 原有 **13/13 后端未提交文件** SHA256 与验收开始前一致；暂存区 diff 摘要也一致，UserMapper 仍留在原暂存区。
- 没有对历史 VHDX 执行删除、覆盖、移动或其他文件操作；没有执行数据清理命令。
- 新测试账号密码随机生成，配置位于 Git 忽略的 `logs/so-ui001-final-20261121/private.json`、`compose.private.json`。JWT 密钥通过子进程环境传入，不在 Java 命令行。
- 私有配置、源码副本、服务日志、JSON 证据及测试数据目录均被 Git 忽略。报告及 PR 不包含密码、Token 或密钥。
- 本次复核证明资源身份、挂载及代码保护状态；未尝试冻结或逐文件哈希正在运行的原 MySQL 卷，也不声称正常运行中的原容器停止了自身后台写入。

## 环境当前保留与停止方法

验收环境保持运行，供人工检查；未继续新增业务模块。首页为 `http://127.0.0.1:25173`。测试会议室已完成逻辑删除，测试订单和通知/Outbox 历史保留，可在各列表和数据库中核对。

以下命令只针对独立测试项目，不使用原 SmartOffice/PetTravel Compose 配置：

```powershell
Set-Location 'D:\A-project\smartoffice-rebuilt'
docker compose -p so-ui001-final-20261121 -f '.\logs\so-ui001-final-20261121\compose.private.json' ps
docker compose -p so-ui001-final-20261121 -f '.\logs\so-ui001-final-20261121\compose.private.json' logs --tail 80

# 需要停止验收环境时：先停止本次 Java/Vite，再停止本次中间件，保留新旧全部卷。
& '.\logs\so-ui001-final-20261121\stop-apps.ps1'
docker compose -p so-ui001-final-20261121 -f '.\logs\so-ui001-final-20261121\compose.private.json' stop
```

Java/Vite 日志为 `logs/so-ui001-final-20261121/{user,booking,gateway,frontend}.{out,err}.log`。停止脚本只接受本次记录的 PID，并核对命令行中的测试源码目录或专用 Vite 端口；PID 被复用或身份不符时拒绝操作。已只读确认 4/4 进程匹配，没有实际停止。

## 证据文件与未执行项

Git 忽略目录 `logs/so-ui001-final-20261121` 中保留：

- `run.json`、`baseline.json`、`jar-hashes.json`：测试标识、PR 提交、资源/代码基线与 JAR 指纹。
- `browser-result.json`：18 组业务场景、81 条请求/响应元数据、SQL/MQ/Redis 结果。
- `render-result.json`：八个真实页面与控制台检查。
- `safety-result.json`：11 卷、9 容器、13 文件、暂存及健康检查。
- `maven-build.log`：干净源码构建记录。

浏览器截图位于本机 Codex 的 `C:\Users\11707\.codex\visualizations\2026\10\21\01a15d06-fda3-7103-8747-15633f375863`，文件前缀 `so-ui001-real-`，包括预约列表、管理员列表、SENT Outbox、真实通知及手机页。本轮内置浏览器再次报 `privileged native pipe bridge is not available; browser-client is not trusted`，使用已有 Playwright/Chromium，不增加产品依赖。

未执行：生产部署、多副本/Nacos 服务发现、高并发压力、MySQL/Redis/Broker 故障注入、MQ 死信/强制重投/长期租约恢复、跨浏览器及全尺寸矩阵。这些不影响本次基础验收通过，但不应被本报告扩大解释为已通过。原 13 项未提交后端扩展没有包含在被测 PR 源码中，本报告不代替其单独验收。

本轮只更新验收文档，没有修改业务代码、依赖、SQL/Lua、事务或安全配置；PR #4 留待人工确认合并。
