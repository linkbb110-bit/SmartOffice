# PR #4 发布前检查（2026-11-21）

本记录补充 [真实环境验收](so-ui-001-real-environment-acceptance-20261121.md) 和 [中文演示数据整理](demo-data-cleanup-20261121.md)。历史报告中的测试数量、环境和阶段结论保持原样；本轮结果如下。

## 文档整理

- 后台设计迁至 [docs/design/admin-console.md](design/admin-console.md)，实施计划迁至 [docs/plans/admin-console.md](plans/admin-console.md)，保留两阶段范围、接口依据和验证要求。
- 修复 README 和验收报告中的引用，删除公开文档中与工程无关的个人工具目录、内部运行错误及重复执行指令；保留有价值的测试结论和未验证边界。
- 本轮新增的只读诊断类和公共方法使用 Javadoc，说明启用条件、实际数据库查询及认证边界。

## 演示初始化脚本修复

`deploy/upgrade/seed-demo.mjs` 原先在账号已有任意预约时整体跳过，第二条预约失败后也无法补齐。现在分别识别两个目标预约：

1. 根据唯一会议室名称、会议主题和起止时间匹配当前账号已有记录；无关预约不阻止初始化，已取消的匹配记录也不会被重新创建。
2. 使用独立数据库名 `so_ui001_demo_YYYYMMDD` 中的日期固定计划，选择其后两个工作日。跨日重试仍使用原计划；缺失目标已过期则拒绝，不悄悄生成新日期。
3. 为每个目标生成稳定请求键，例如 `demo-v1-20261121-review-2`，满足后端只允许字母、数字、下划线或连字符且不超过 64 字符的约束。响应丢失、并发或重试继续使用相同意图键，由原有预约幂等逻辑保障唯一性。
4. 已有订单及其 requestId、Outbox 事件和消费记录不被修改；脚本只通过真实预约 API 补齐缺失目标，不直接写订单或消息表。

### 实际环境身份检查

仅判断 localhost 和端口不足以区分数据库。新增两个默认不启用的本地诊断组件：

- booking-service 的 `DemoEnvironmentInfo` 用当前 `JdbcTemplate` 执行只读 `SELECT DATABASE()`，将实际连接库名加入 `/actuator/info`。
- Gateway 的 `DemoEnvironmentRoute` 只将 GET `/api/demo/environment` 或 `/demo/environment` 代理到同一个预约上游的 `/actuator/info`，不代理其他 Actuator 路径。

两者都要求 `local` Profile 和 `SMARTOFFICE_DEMO_ENABLED=true`。原有 JWT 认证继续生效，未登录请求实测为 HTTP 401；没有关闭安全过滤器或放宽权限。普通环境不启用诊断路由和信息贡献器。

脚本登录后先查询该接口，要求标识为 demo 且实际库名与 `SMARTOFFICE_DEMO_DATABASE` 完全一致，再查询并创建预约。库名不符、诊断未启用、非本机地址、无效计划日期均在预约写入前拒绝；HTTP 重定向也不被跟随。

### 执行方法

只对已经准备好的独立演示库使用本脚本；它不会创建库、清表或重建卷。为独立实例的 booking-service 和 Gateway 都设置上述 Profile/开关，核对 JDBC 指向演示库，前端代理到该 Gateway。密码通过隐藏输入读取：

```powershell
Set-Location 'D:\A-project\smartoffice-rebuilt'
$env:SMARTOFFICE_DEMO_USERNAME = 'user'
$env:SMARTOFFICE_DEMO_DATABASE = 'so_ui001_demo_20261121'
$env:SMARTOFFICE_DEMO_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '演示账号密码' -AsSecureString)).Password
try { node deploy/upgrade/seed-demo.mjs http://127.0.0.1:25173 }
finally { Remove-Item Env:SMARTOFFICE_DEMO_PASSWORD -ErrorAction SilentlyContinue }
```

本例计划为 2026-11-23 和 2026-11-24；日期过去后，已存在的目标仍会跳过，缺失目标不再新建。准备新计划应另用新的独立演示库，不能重命名或清空旧库来重试。

## 自动化回归

| 检查 | 本轮结果 |
| --- | --- |
| `npm test` | 45 项通过，包括 11 项初始化脚本测试 |
| `npm run build` | TypeScript 类型检查和 Vite 生产构建通过 |
| Java 17 `mvn -o verify` | 124 项，0 失败、0 错误、0 跳过 |
| 脚本专项 | 正常执行、重复执行、无关订单、部分失败补齐、响应丢失、并发、取消记录、跨日重试、错误环境和过期计划 |
| Java 诊断专项 | 实际 JDBC 库名、Profile/开关、精确 GET 路由、认证头传递和默认路由关闭 |

Java 验证使用当前提交源码副本叠加本轮五个 Java 文件，未纳入原工作区另有的 13 个未提交 Java 文件。测试中先发现并修复诊断 Bean 名冲突；真实 API 验证又发现请求键冒号不符合后端校验，补充格式断言后改为连字符，再完成回归。没有绕过失败检查或修改原有业务规则。

## 真实数据库、消息及浏览器验证

沿用独立验收中间件项目 `so-ui001-final-20261121`，没有重建容器或数据卷。

- 当前页面使用 `so_ui001_demo_20261121`，两条正常中文会议预约保持不变；运行新脚本结果为提交 0、已有 2。
- 新建独立库 `so_ui001_demo_20261123` 验证故障重试，使用额外的两个本地测试服务、Redis DB 2 和独立消息主题/消费组。先通过 API 创建一条无关的“架构方案讨论”，再由测试代理仅对第二个目标请求注入一次 HTTP 503。
- 首次执行后共有两单、两个事件：无关预约及第一个目标。重试只补第二个目标，随后重复运行不再新增，共三单、三个事件。无关订单逐字段保持不变。
- 指定另一个预期演示库名时，接口返回的实际库名不符，脚本在发送任何预约 POST 前拒绝，订单/事件数量未变。
- 真实 RocketMQ 最终产生三个 SENT Outbox、三条通知和三条消费回执；没有手工插入消息。额外的故障测试 Java 进程在验证后停止，独立测试库保留。
- Chromium 浏览器六组检查通过：四间中文会议室及完整设备、近期中文会议及预约编号搜索、原始 ID 提示、真实 CSV、SENT Outbox 与完整 UUID 详情、手机布局和用户中文通知；控制台错误和警告均为 0。页面无乱码、SOUI001/2099 主题或时间。

演示页面地址 `http://127.0.0.1:25173`，Gateway/booking-service/user-service 分别为 `21800/21801/21802`，三个 Java 17 服务均为 local Profile。私有配置、进程记录和完整日志存放在被 Git 忽略的 `logs/pr4-closeout-20261121`；不公开密码、JWT 或本机工具证据路径。停止服务前必须核对对应进程身份，不使用历史 PID 随意终止进程。

## 数据和提交边界

- 原 13 个未提交 Java 文件的内容摘要以及暂存内容与本轮开始时一致，不纳入发布提交。
- 原 11 个 Docker 卷、9 个容器的身份与挂载复核通过；没有影响原业务环境、PetTravel 或历史 VHDX。
- 旧验收库原两单及关联事件、通知、回执和审计摘要一致，原库另有的一条正常新增预约也保留。
- 12 个已跟踪 SQL 文件严格按 UTF-8 解码且无 BOM；技术字段原有 ASCII/ascii_bin 约束继续保留。
- 本轮只改初始化脚本、专项测试、可选只读诊断和文档，没有更改预约锁、事务、冲突校验、取消 CAS、缓存或 Outbox/RocketMQ 业务机制。

生产构建原有 PURE 注释提示及超过 500 KB 的 chunk 提示仍为非阻断项。本轮不验证生产多副本、故障断电恢复或容量压测；历史报告中的这些边界继续有效。最终提交和合并状态以 [PR #4](https://github.com/linkbb110-bit/SmartOffice/pull/4) 的记录为准。
