# SO-UI-001：前端与真实接口审计

审计日期：2026-11-21。范围：README.md、docs/architecture.md、docs/core-flows.md、frontend/src、后端 Controller 及其直接关联 DTO/安全配置。本文是改造前审计，不代表新后台已实现或已完成真实环境验收。

需求基线与最终边界见 [后台设计](design/admin-console.md)。以下保留改造前的接口与界面现状，后续实现结果另见验收记录。

## 现有界面与可复用实现

- 已使用 Vue 3、TypeScript、Element Plus、Vue Router、Axios，无需替换框架或增加 UI 库。
- 路由只有登录与 `/dashboard`；工作台当前实际渲染会议室列表。没有后台布局、独立预约页或管理员页面。
- `BookModal` 已接真实预约接口，使用 `createBookingSubmission`：校验前互斥、相同失败重试复用 requestId、修改内容/重开/成功后更换 requestId。
- `MyOrders` 当前是弹窗。`createOrderCancellation` 按订单防重复，收到成功后才把状态改为取消；失败保留可重试状态。
- 请求封装保持同源 `/api`、Bearer 头、Result 解包、登录 401 与会话过期的区别。新 API 继续使用这套封装。
- 当前路由只检查本地 token 是否存在，没有查询真实用户、管理员路由守卫或菜单权限。

## 已存在的真实接口

下列路径省略浏览器统一的 `/api` 前缀。Controller 可直接支持两阶段需求，无须修改交易服务。

| 功能 | 方法与路径 | 权限与数据约束 |
|---|---|---|
| 登录、注册 | POST `/users/login`、`/users/register` | 注册只生成普通用户；登录返回 JWT |
| 当前身份 | GET `/users/me` | 返回 userId、username、role，不含密码；回查数据库当前资料 |
| 会议室列表、详情 | GET `/rooms/list`、`/rooms/{id}` | 需认证；列表返回 id、roomName、capacity、equipment、status |
| 创建预约 | POST `/orders/book` | roomId、topic、startTime、endTime、requestId；成功返回订单 ID |
| 我的预约 | GET `/orders/my` | 服务端按认证用户取范围；返回订单列表 |
| 取消预约 | POST `/orders/{id}/cancel` | 本人或 ADMIN；实际归属与 CAS 在后端检查 |
| 我的通知 | GET `/notifications/my` | 仅本人，最近 100 条；id、booking_id、content、created_at |
| 会议室新增 | POST `/rooms` | ADMIN；返回新房间 ID |
| 会议室修改、删除 | PUT/DELETE `/rooms/{id}` | ADMIN；删除可能因未结束预约返回 409 |
| 全部预约 | GET `/orders` | ADMIN；字段仍是 BookingVO，没有用户姓名或房间名快照 |
| CSV 导出 | GET `/orders/export` | ADMIN；Accept 为 text/csv，成功为 CSV 文本而非 Result JSON |
| Outbox 监控 | GET `/admin/messages/outbox` | ADMIN；最近 100 条事件，含状态、attempts、租约、错误类型与 Broker ID |

## 不得伪造的能力与统计口径

- 没有修改个人资料/密码、上传头像、用户管理或角色分配接口。个人中心展示真实身份、刷新和退出登录，不显示虚假的保存操作。
- 没有通知已读状态/标记已读/删除通知接口。只展示已收到通知，不制造未读数字或已读操作。
- Outbox 没有手动重投/暂停消费接口；监控只读，不能把最近 100 条计数称为全库积压量，更不能据此宣称 Broker 健康。
- 会议室 status=1 表示允许发起预约，不表示任意时间段空闲；具体可预约性由后端冲突检查决定。
- 工作台统计来自实际列表，并明确“我的预约”“今日预约”“当前会议室列表”的范围；接口失败显示错误及重试，不用假数据或把失败当作 0。
- BookingVO 只有 userId/roomId；缺少名称时显示真实 ID，不编造姓名，也不隐藏已无会议室名称的历史预约。
- 会议室列表为完整列表、通知/Outbox 为最近 100 条。搜索、状态筛选和分页若在前端处理，明确只针对已加载数据。

## 校验与兼容性

- RoomSaveDTO：名称非空且不超过 100 字符；容量实际注解上限为 30（错误提示文字写 100，不能据提示放宽）；status 仅 0/1。
- equipment 为字符串数组，后端限制序列化后 JSON 的 Unicode 码点数不超过 500；前端校验与其一致，后端仍作最终裁决。
- 预约使用秒精度本地时间，不改变 `[start,end)` 冲突规则或 requestId 语义。新界面复用既有提交与取消协调器。
- CSV 通过真实导出接口下载，保留后端公式防护；鉴权/媒体类型错误不得保存为 CSV 文件。
- `/users/me` 回查不会更新 JWT 内角色。若当前资料与令牌身份/角色不一致，要求重新登录；前端菜单不能替代后端授权。

## 当前未提交后端工作

开始时 main 工作区已有 13 个后端变更路径，包括已暂存新增的 `mapper/UserMapper.java`，其余为 Controller、Service、实现及测试修改。现已创建 feat/admin-console 分支，原文件及暂存内容保留。

其中新增 `/rooms/search`、`/users/test`，以及 `/orders/my` 的可选 status 参数尚未进入 HEAD。后台实现以 HEAD 和工作区共有接口为契约：使用 `/rooms/list` 和无 status 参数的 `/orders/my`，在已加载结果上筛选，不让 PR 依赖这些未提交后端工作。

本任务不覆盖、撤销、提交或推送上述后端修改。提交使用明确文件路径；不使用 `git add .`、`git commit -a` 或把整个暂存区直接提交。

## 不变的一致性边界

MySQL 房间行锁与 READ_COMMITTED、Redisson watchdog 锁及降级、半开区间冲突检查、requestId 幂等、取消 CAS、提交后缓存失效与 Lua 版本回填、RocketMQ/Outbox 至少一次投递均保持后端现状。界面不以缓存状态判定交易成功，不在响应失败时乐观伪造已成功状态。

## 改造前基线验证

2026-11-21 在现有前端执行 `npm test`，15/15 通过；执行 `npm run build`，TypeScript 检查和 Vite 构建成功。已有 PURE 注释及大于 500 KB chunk 提示未阻断构建。本轮尚未修改产品代码，这些结果只作为改造前基线；没有据此宣称新页面或真实 API 浏览器验收已通过。
