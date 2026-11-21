# 中文演示数据与展示编号修复

日期：2026-11-21。当前入口仍为 `http://127.0.0.1:25173`，刷新页面即可读取新的演示数据。采用用户确认的“新建演示库、保留验收历史”方式，[PR #4](https://github.com/linkbb110-bit/SmartOffice/pull/4) 保持未合并。

## 乱码原因及修复

源 SQL 文件是正常 UTF-8，MySQL server、数据库及各表默认也已是 utf8mb4。问题出在初始化客户端：MySQL 命令行的默认 `character_set_client/connection/results` 为 latin1，原 `data.sql` 没有显式设置会话字符集，UTF-8 中文被当成 latin1 转码后存入 utf8mb4 列。原记录的 HEX 与乱码内容均证实数据已经损坏，前端只是显示真实返回值。

- 在 `backend/booking-service/src/main/resources/data.sql` 开头设置 `SET NAMES utf8mb4`，并替换四间中文会议室的数据。
- 添加 `.editorconfig` 的 SQL UTF-8、LF 设置；核对 12 个已跟踪 SQL 文件均可严格按 UTF-8 解码且无 BOM，没有批量格式化源码。
- 两个服务的 JDBC URL 统一为 `characterEncoding=UTF-8&connectionCollation=utf8mb4_0900_ai_ci`；Compose MySQL 显式配置 server 字符集和排序规则。
- 没有对已经正确的库表执行无意义的 ALTER。`request_id`、`event_id` 等技术字段原有的 ASCII/ascii_bin 约束继续保留，避免改变幂等或唯一键语义。
- 对同一个会话临时表，原初始化 SQL 在 latin1 客户端下不能保留中文和四字节字符，修复 SQL 可完整保留；临时表随连接结束消失，没有污染演示表。

JDBC 的 UTF-8 对应 MySQL utf8mb4，连接编码由驱动配置，不通过应用层额外执行 SET NAMES。参见 [MySQL Connector/J 官方字符集说明](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-charsets.html)。本次 SET NAMES 位于初始化 SQL，用于明确导入会话编码，并已验证现有 H2 测试兼容。

## 当前数据与历史保护

新演示预约库为 `so_ui001_demo_20261121`，位于现有独立验收 MySQL `127.0.0.1:23306`；复用独立用户库 `so_ui001_user_20261121`，不改账号和角色。Redis 使用独立 DB 1；MQ 使用新主题和消费组，与旧验收事件隔离。没有删除、重建数据卷或现有数据库。

| 会议室 | 容量 | 设备 |
| --- | --- | --- |
| 研发一号会议室 | 12 | 投影仪、白板、视频会议终端 |
| 产品评审室 | 8 | 电视、白板、无线投屏 |
| 培训室A | 20 | 投影仪、麦克风、音响、白板 |
| 面试室 | 6 | 电视、白板 |

保留原示例中面试室的维护状态。新增预约通过真实预约 API 创建，未直接插入订单或伪造 Outbox/通知：

| 会议 | 时间（Asia/Shanghai） | 会议室 | 展示编号 |
| --- | --- | --- | --- |
| 产品需求评审会 | 2026-11-23 10:00–11:00 | 产品评审室 | BK20261123-001 |
| 项目周例会 | 2026-11-24 14:00–15:00 | 研发一号会议室 | BK20261124-002 |

初始化脚本按执行当天选择接下来两个工作日，避免固定 2099 年。再次执行时如果当前账号已有预约，则不重复初始化。

旧验收库 `so_ui001_booking_20261121` 继续保留：四间乱码会议室通过正常管理员 API 更新，原主键不变，缓存按原业务路径失效。原两条验收订单及相关 Outbox、通知、消费回执、审计的逐字段摘要未变。整理期间原库新增了一条“内部会议”预约，也保留在原库，因此旧库现有三单；没有因为总摘要变化而删除或覆盖这条新增记录。

当前页面只连接新演示预约库，旧 SOUI001/2099 验收记录不再作为展示数据。新旧记录的数字 ID 即使相同，也属于不同数据库；没有重写旧订单、requestId、事件 UUID、事件载荷或审计快照。原业务 MySQL、Redis、PetTravel、历史 VHDX 以及原有数据卷未作为本次清理目标。

## 展示层修改

- 预约编号由真实预约日期和原 ID 派生，例如 `BK20261123-001`，不新增或修改数据库主键；悬停可看原始 ID，搜索支持该展示编号，原始 ID 搜索仍可用。
- Outbox 列表显示 UUID 的首尾片段；悬停和详情页保留完整 UUID，row-key 和请求仍使用原 ID。
- CSV 继续由原真实 API 导出，保留原始 ID 和接口契约，不把展示编号写回业务字段。
- 没有修改锁、事务、时间冲突、幂等、取消 CAS、缓存协议、消息投递或消费逻辑。

## 初始化及配置文件清单

| 文件 | 变更 |
| --- | --- |
| `backend/booking-service/src/main/resources/data.sql` | utf8mb4 导入会话、四间中文会议室 |
| `deploy/upgrade/seed-demo.mjs` | 新增：经真实 API 创建近期中文演示预约，凭据从环境读取 |
| `.editorconfig` | SQL 文件编码约定 |
| `backend/booking-service/src/main/resources/application.yml` | JDBC UTF-8/utf8mb4 排序规则 |
| `backend/user-service/src/main/resources/application.yml` | 同上 |
| `deploy/docker-compose.upgrade.yml` | 两个 MySQL server 字符集、服务连接 URL |
| `deploy/docker-compose.dev.yml` | MySQL server 字符集 |

前端改动为 `BookingTable.vue`、`AdminBookings.vue`、`MyBookings.vue`、`Outbox.vue`、`utils/console.ts` 和新增 `utils/display.ts`；新增 `display-identifiers.test.mjs`。本次没有修改 Java 文件，也没有扩写或覆盖原有 Java 注释。新增源码不含教程式或重复注释。

只对全新独立演示环境使用预约初始化脚本；它不会创建数据库、清表或重建卷。密码通过隐藏输入读取，不写进脚本：

```powershell
Set-Location 'D:\A-project\smartoffice-rebuilt'
$env:SMARTOFFICE_DEMO_USERNAME = 'user'
$env:SMARTOFFICE_DEMO_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '演示账号密码' -AsSecureString)).Password
try { node deploy/upgrade/seed-demo.mjs http://127.0.0.1:25173 }
finally { Remove-Item Env:SMARTOFFICE_DEMO_PASSWORD -ErrorAction SilentlyContinue }
```

SQL 中的 INSERT IGNORE 不会自动修复已有乱码记录；本次已有数据的修复走了正常管理 API。没有对原业务 Compose 项目执行 up 或初始化脚本。

## 验证结果及环境

- `npm test`：34/34；类型检查及 Vite 生产构建通过，保留原有非致命构建提示。
- Java 17 `mvn -o verify`：119 项，0 失败、0 错误、0 跳过。使用提交源码副本叠加本次三个资源文件，不覆盖原工作区 13 个未提交 Java 文件。
- 六组浏览器检查通过：四间中文会议室及设备、近期中文预约及编号搜索、原始 ID 提示、CSV、真实 SENT Outbox/完整 UUID、手机显示与真实中文通知；控制台错误/警告均为 0。
- 新演示库两条 Outbox 为 SENT，通知、审计、消费回执各两条；不是手工插入的消息结果。
- JDBC 实际会话的 client/connection 为 utf8mb4，collation 为 utf8mb4_0900_ai_ci；results 为空表示按字段原字符集返回，并非 latin1 转码。
- 原 13 个 Java 文件哈希、原暂存项、11 个原有卷和 9 个原有容器的身份与挂载复核通过。

当前 Java/Vite 进程及私有配置在 Git 忽略目录 `logs/so-ui001-demo-20261121`，数据和浏览器证据为 `data-result.json`、`browser-result.json`，完整 Maven 日志为 `maven-verify-final.log`。这些配置、日志和凭据未加入 Git。

如需停止当前演示 Java/Vite，执行 `& '.\logs\so-ui001-demo-20261121\stop-apps.ps1'`；中间件仍属于独立项目 `so-ui001-final-20261121`。不使用此前历史报告中的旧进程停止脚本。保留环境供人工查看，PR 不合并。
