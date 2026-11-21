# SO-UI-001 两阶段实施与验收

日期：2026-11-21。项目：`D:\A-project\smartoffice-rebuilt`。分支：`feat/admin-console`，基于 `541e034`；不合并 main。

## 已实现功能

| 阶段 | 页面/能力 | 真实数据及操作 |
| --- | --- | --- |
| 一 | 后台布局、侧栏、移动导航 | Element Plus，折叠/窄屏抽屉、当前身份、退出；键盘焦点隔离及循环 |
| 一 | 工作台 `/dashboard` | 房间数量、本人待开始/今日预约、最近通知，由真实查询派生；失败显示错误及未知值 |
| 一 | 会议室预约 `/rooms` | 名称/容量/状态筛选，原 BookModal 提交；状态不代表时段一定空闲 |
| 一 | 我的预约 `/bookings` | 独立列表、搜索/状态筛选、分页、确认取消；取消后筛选计数即时更新 |
| 一 | 通知中心 `/notifications` | `/notifications/my` 返回最近 100 条；没有伪造未读数或已读操作 |
| 一 | 个人中心 `/profile` | `/users/me` 真实身份、刷新、退出；不展示缺少 API 的资料写入功能 |
| 一 | 身份与权限 | `/users/me` 与 JWT uid/用户名/角色一致性检查；ADMIN 菜单和路由保护；401 清理及返回路径、身份查询失败可重试 |
| 二 | 会议室管理 `/admin/rooms` | 真实增改删；容量按实际约束 1～30，设备 JSON 长度校验；409 删除拒绝保留数据 |
| 二 | 全部预约 `/admin/bookings` | 全用户列表、主题/订单 ID/用户 ID/状态筛选、分页、管理员确认取消 |
| 二 | CSV 导出 | `/orders/export` 全量下载，显式 Accept/Authorization；Blob JSON 错误按原认证规则处理，非 CSV 不下载 |
| 二 | Outbox `/admin/outbox` | 最近 100 条、状态统计与筛选、详情、刷新；SENT 不等于消费成功，无伪重试/暂停按钮 |

所有产品数据均来自现有 API。前端读取 JWT 只做身份一致性检查，签名和权限由原后端校验；未放宽 Spring Security 或 CORS。

## 两阶段顺序与提交

1. `3f51d23`：指定文档、前端及 Controller API 审计和设计。
2. `7cf0015`：第一阶段；21 项测试、构建、10 组真实浏览器场景通过后提交，再进入第二阶段。
3. `79ad642`：第二阶段；28 项测试、构建、11 组真实浏览器场景通过。
4. 后续修正提交：独立只读检查发现两处 P2 问题，均通过先失败再通过的回归验证：取消后筛选计数未失效、收起的手机侧栏获得焦点。最终 31 项测试及构建通过。

## 最终验证

| 检查 | 结果 |
| --- | --- |
| `npm test` | 31/31，0 失败、0 跳过 |
| `npm run build` | vue-tsc 类型检查与 Vite 生产构建通过 |
| 本任务 `git diff --check` | 前端和文档通过；提交范围检查通过 |
| 第一阶段浏览器 | 10 组通过，第二阶段及修正后再次执行通过 |
| 第二阶段浏览器 | 11 组通过，修正后再次执行通过 |
| 认证浏览器 | 5 组通过：错误密码、真实注册/登录、身份服务断网重试、无效 JWT、缺失 JWT |
| 键盘回归浏览器 | 1 组通过：手机关闭导航不可聚焦、打开后焦点进入、Tab/Shift+Tab 循环、Escape 返回、导航后关闭 |
| 浏览器合计 | 27 组；1440×1000 与 390×844，无页面级横向溢出，检查到的浏览器脚本异常为 0 |
| 原有后端改动保护 | 13/13 文件 SHA256 与实施前一致，原已暂存 UserMapper 仍保留且未进入本分支提交 |
| Java/Maven | 本任务没有 Java/配置/依赖变更，未执行 mvn verify；不将旧后端测试结果当作本次新结果 |

浏览器中的 API 请求经过 Vite → Gateway → 现有 Java 服务。已实际完成预约、本人取消、管理员取消、房间创建/修改、409 删除拒绝、取消后删除、CSV 文件下载与内容检查、通知空态、Outbox PENDING 详情和筛选。Network 记录仅保存请求路径、方法、HTTP 状态码及 Authorization 请求头是否存在，不记录凭证。

纯函数/API 契约测试中的 adapter 仅是隔离测试工具，产品不使用 Mock 数据。断网错误场景通过浏览器中止指定请求复现，没有用模拟成功响应替代集成验证。

## 验证环境及边界

- 内置浏览器桥接报错 `privileged native pipe bridge is not available; browser-client is not trusted`，改用本机已有 Playwright/Chromium，没有增加前端依赖。
- 使用现有 Java 17 服务 JAR 启动独立回环端口、H2 内存库和单独 Vite。未重编译或改动用户未提交的后端源码，未连接现有业务库。
- Redis 不可用时使用项目原有数据库降级逻辑；MQ 和 Nacos 在隔离测试实例中关闭。已观察到真实 Outbox PENDING，未验证真实 Broker 投递、SENDING/SENT 状态迁移及新通知消费。
- 本次未重新执行 Docker 恢复、MySQL/Redisson 高并发、中间件/多副本验收。Docker 卷、历史 VHDX、PetTravel 没有操作。
- 沿用当前功能分支工作区以保护已有用户改动，通过明确路径 `git commit --only` 隔离提交；代价是必须持续核对暂存区，本次已完成核对。
- 构建仍有原有 PURE 注释和 chunk 大于 500 KB 的非致命提示，未扩大范围做依赖或分包调整。
- 全工作区空白检查还能看到用户原有后端测试文件两处尾随空白；本任务保留，没有修改后端消除该提示。

## 本地运行与复核

先用现有开发流程启动后端及其依赖；此处只启动前端，不初始化数据库或中间件。Gateway 若使用其他端口，应将下方变量设为实际地址；全容器演示模式原映射为 18000。

```powershell
Set-Location 'D:\A-project\smartoffice-rebuilt\frontend'
# 首次安装或锁文件变化后执行 npm ci；本次未改 package.json/package-lock.json。
npm ci
npm test
npm run build
$env:VITE_GATEWAY_URL = 'http://127.0.0.1:8080'
npm run dev
```

浏览器默认 `http://localhost:5173`，若端口已占用以 Vite 输出为准。用自己的真实账号登录；ADMIN 角色来自已有账户与服务端授权，普通用户不能通过前端自行提权。

键盘回归脚本保留在 `frontend/browser-tests/navigation-keyboard.cjs`，需要外部已安装的 Playwright 及隔离测试实例。通过 `PLAYWRIGHT_MODULE` 指向外部模块、`SO_UI_TEST_USERNAME`/`SO_UI_TEST_PASSWORD` 提供测试凭据，执行 `node browser-tests/navigation-keyboard.cjs <隔离前端地址>`。脚本不打印凭据、不启动或操作 Docker；产品依赖不包含测试浏览器工具。

## 改动文件清单

仅前端、README 和本任务文档。原有 `BookModal.vue`、`bookingSubmission.ts`、`orderCancellation.ts` 及全部后端文件保持原样。

- 文档：`README.md`、`docs/so-ui-001-api-audit.md`、`docs/so-ui-001-acceptance.md`、`docs/superpowers/specs/2026-11-21-so-ui-001-admin-console-design.md`、`docs/superpowers/plans/2026-11-21-admin-console.md`。
- 页面：`frontend/src/views/AdminBookings.vue`、`AdminRooms.vue`、`Dashboard.vue`、`Login.vue`、`MyBookings.vue`、`Notifications.vue`、`Outbox.vue`、`Profile.vue`、`RoomList.vue`、`RouteError.vue`、`SessionError.vue`。
- 布局与通用组件：`frontend/src/layouts/AdminLayout.vue`、`frontend/src/components/BookingTable.vue`、`LoadError.vue`、`PageHeading.vue`、`frontend/src/styles/global.css`。
- API：`frontend/src/api/auth.ts`、`notifications.ts`、`orders.ts`、`outbox.ts`、`rooms.ts`。
- 路由/状态/工具：`frontend/src/main.ts`、`frontend/src/router/index.ts`、`frontend/src/state/session.ts`、`frontend/src/types/console.ts`、`frontend/src/composables/useQuery.ts`、`frontend/src/utils/admin.ts`、`console.ts`、`identitySession.ts`、`request.ts`。
- 测试：`frontend/frontend-tests/admin-console.test.mjs`、`console-state.test.mjs`、`login-feedback.test.mjs`、`query-state.test.mjs`、`frontend/browser-tests/navigation-keyboard.cjs`。

## 遗留项

没有尚未处理的独立检查问题。未验证项是上述真实中间件、消息投递/消费和生产部署链路；本次不是这些环境的验收。通知已读、资料修改、角色分配、Outbox 重投不在现有 API 能力内，本次按批准设计保持只读，不提供空壳操作。
