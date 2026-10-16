# 前端验证记录（2026-10-14）

现有 Vue 3 / TypeScript / Element Plus 页面保留。房间页增加「我的预约」，支持本人订单列表、已预约/已取消状态与取消；登录页增加普通用户注册入口。预约请求现在携带随机 requestId，成功后关闭弹窗。

预约提交锁在异步表单校验之前生效，校验和请求期间禁用表单与关闭操作。同一操作、相同 payload 的失败重试保留 requestId；字段改变、成功、再次打开弹窗都会清除旧操作标识。取消按钮按订单分别防重复提交，仅在服务端成功后标记已取消，失败保持已预约并可重试。API 错误继续由统一 Axios 拦截器显示。

## 接口与配置

- 默认浏览器 API baseURL：`/api`；可使用 `VITE_API_BASE_URL` 覆盖。
- Vite 开发代理：`/api/*` → `http://localhost:8080/*`，移除 `/api` 前缀；可使用 `VITE_GATEWAY_URL` 覆盖网关目标地址。
- 生产静态服务应同样代理 `/api` 并移除该前缀。Vite preview 不提供开发代理。
- 保持 `POST /users/login` 返回 JWT；新增 `POST /users/register` 返回用户 ID；预约 `POST /orders/book` 返回订单 ID；列表 `GET /orders/my`；取消 `POST /orders/{id}/cancel`。
- 没有新增依赖或修改 package-lock。新增 `npm test` 使用 Node 内置测试器直接运行 TypeScript 业务控制器，验证环境为 Node `v24.11.1`；需要支持默认 TypeScript 类型剥离的 Node（建议 Node 24）。

## 阶段初期实际执行结果

| 检查 | 结果 |
| --- | --- |
| TDD 红阶段 | 实现缺失期间，6 个测试按预期因重复提交、requestId 缺失、状态未变化等断言失败 |
| `npm test` 最终运行 | exit 0；6 tests，6 pass，0 fail，0 skipped，0 cancelled |
| `npx --no-install vue-tsc --noEmit` | exit 0 |
| `npm run build` 最终运行（标准提升权限路径） | exit 0；Vue 类型检查与 Vite 生产构建通过；1675 modules transformed；built in 7.42s |

6 个测试覆盖：异步校验时的双击；网络失败后相同 payload 重试复用标识；payload 改变/成功/新操作轮换标识；无效表单不请求且释放锁；取消双击与成功后状态；取消失败保持状态并可重试。测试调用实际业务控制器，传入确定性 ID 生成器与受控网络边界，没有安装测试框架，也没有用源码字符串断言代替行为。

## 构建权限排查

沙箱内基线 `npm run build`：vue-tsc 阶段通过，Vite 解析 `src/main.ts` 时 `EPERM`。同一路径单独对比发现 `fs.realpathSync` 成功，`fs.realpathSync.native` 失败；Vite 使用原生版本。标准工具提升权限运行相同构建后成功，确认问题属于此沙箱中的 Windows 原生路径访问。没有修改 ACL、node_modules、关闭 realpath 检查或添加 preserveSymlinks 绕过。沙箱限制本身仍存在，今后在该环境运行 Vite 需要同样的标准工具权限路径。

构建仍有依赖内 PURE 注释位置警告，以及现有 Element Plus 主 bundle 超过 500 kB 的警告（约 1,033 kB，gzip 340 kB）；它们不阻止构建。

## 阶段初期限制及后续补验

最初没有 Browser 插件，项目没有 Playwright 或 @playwright/test，当时未执行浏览器QA。后续使用项目外临时Playwright和已有Edge补验，未向项目package.json/package-lock加入测试依赖。真实注册、登录、预约双击、我的预约、取消、桌面与手机布局已验收，结果与截图见browser-qa.md。控制器单测和H2浏览器结果仍不能替代真实中间件。

建议后续在服务启动后手动或通过浏览器运行：登录/注册 → 房间预约 → 快速双击提交 → 我的预约 → 取消 → 查看已取消状态；同时模拟预约请求已被服务端处理但响应丢失，检查相同参数重试只生成一个订单。需要网关、后端、数据库可用后才能验证真实接口与并发结果。

## 主代理最终集成验证

独立审查发现原.env.development/.production仍直连8081；先备份到ignored .upgrade-backup，再改/api并同步示例。Vite实际mode解析回归先RED（两模式8081），后GREEN。恢复后npm test 8/8、vue-tsc+build通过（1675modules，6.54s）；frontend-resumed-final.log。本地Vite→Gateway→真实用户/预约JAR（H2）HTTP与浏览器交互已通过。浏览器发现并修复容量SVG没有尺寸、480px预约弹窗手机裁切，以及本地CORS来源漏项；生产端仍保持同源/api。

