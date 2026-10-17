# 浏览器验收记录

> **目录迁移说明（2026-10-17）**：本报告的历史命令、行号及旧路径按原验收版本保留。当前前端在 frontend，后端聚合工程在 backend，原 backend 模块现为 backend/booking-service；Compose/私有部署 env 在 deploy。新启动命令见 [runbook](runbook.md)，完整路径映射与当前验证见 [迁移记录](directory-layout-verification.md)。

## 最新：完整Docker真实后端（2026-10-14 22:03）

Vue/Nginx → Gateway → 用户/预约服务，真实MySQL、Redis、RocketMQ、Nacos均启用。执行项目外`real-browser-check.mjs http://127.0.0.1:18080`退出0，9项通过：页面、长名称换行、注册登录/查询、同步双击仅1POST且requestId存在、本人取消、真实409提示/保留表单/同payload同key、手机布局、无pageerror或非预期console错误、真实401清Token回登录。普通用户管理权限/导出403另由真实API验收覆盖，不声称存在尚未实现的前端管理员页面。

日志`logs/real-integration/real-browser-final-command.log`；桌面1366×900、手机390×844，Edge155/Playwright1.56.1、独立context，网络仅loopback。无新浏览器/项目依赖。当前报告与五截图在项目外`real-browser-evidence/5930f9ff/`，桌面卡片与手机弹窗已人工查看。此前8项真实后端结果b1dd43c3同样保留；截图暴露长无空格名称裁切，只加`overflow-wrap:anywhere`，新增实际标题宽度断言并使用更新后的前端镜像复验。

- [最新桌面卡片](C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/real-browser-evidence/5930f9ff/desktop-rooms.png)
- [最新手机弹窗](C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/real-browser-evidence/5930f9ff/mobile-booking.png)
- [最新机器报告](C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/real-browser-evidence/5930f9ff/report.json)

以下保留早期H2验收，不能与上面的真实中间件结果混用。其他浏览器、更多设备和浏览器响应丢失场景仍未执行。

## 历史：H2本地联调

日期：2026-10-14。真实 Vue 页面 → Vite `/api` → Gateway → 独立用户/预约服务 JAR，未模拟成功响应；三个服务使用新 H2 内存库，Redis端口1模拟不可用，MQ/Nacos关闭。不连接已有数据库或真实云服务。

## 环境与方式

本会话没有 Browser 插件，因此按前端验证技能使用常规 Playwright。Playwright 1.56.1安装在项目外临时目录，使用已安装的 Microsoft Edge 155.0.4283.45，无头、chromiumSandbox=true、独立浏览器上下文，网络只允许localhost/127.0.0.1。没有向项目 package.json/package-lock 加浏览器依赖，没有下载新浏览器或修改系统权限。

`deploy/upgrade/smoke-local.ps1 -WithFrontend -BrowserScript <临时脚本>` 启动本轮进程，健康后执行8项HTTP检查和浏览器检查，finally停止本轮Java/Node进程。完整启动命令见runbook。浏览器报告独立于HTTP报告，截图在成功提示自然消失后截取，没有隐藏界面元素伪造效果。

## 实际检查

| 检查 | 结果与证据 |
|---|---|
| 页面基础 | PASS：标题“云智办 SmartOffice”、非空、无Vite错误覆盖层 |
| 注册 | PASS：新USER注册成功，返回登录入口 |
| 登录与路由 | PASS：登录后进入会议室页，真实查询到本地测试房间，容量图标尺寸≤24px |
| 预约提交 | PASS：日期/时间表单提交，两次同步点击只产生一条预约POST |
| 本人订单与取消 | PASS：本人订单展示、取消成功、状态变为已取消、取消按钮消失 |
| 手机布局 | PASS：1366×900桌面和390×844手机；手机页面无横向溢出，预约弹窗完整落在视口内 |
| 浏览器错误 | PASS：console error/pageerror为0；本轮console warning为0 |

HTTP与浏览器组合冒烟共9项PASS，浏览器自身7项PASS。最新清晰截图复验于2026-10-14 16:48:23（北京时间）结束。报告 `logs/local-smoke/result.json` 包含实际startedAt/completedAt、passed=true、browser=passed。日志 `browser-release.log` 与清晰截图复验 `browser-release-evidence.log` 保存在本地ignored范围。

## 定位与修复

1. 首次脚本标题期待值与应用不一致，以及日期选择器也具有dialog角色：修正测试标题和具名弹窗定位，属于测试定位问题，不改业务。
2. 浏览器首次联调出现CORS 403：恢复两业务服务本地localhost/127.0.0.1的5173–5175与18080来源；冒烟随机端口只显式加入本轮准确来源，没有通配或跳过认证。
3. 原容量UserFilled为无尺寸裸SVG，撑开卡片：使用18px ElIcon封装，保留原页面结构；浏览器增加尺寸断言。
4. 原预约弹窗固定480px，手机裁切：改为`min(480px, calc(100vw - 32px))`，增加实际边界与横向溢出断言。

## 本机证据位置

临时脚本及依赖、报告和截图均位于下面的项目外目录，不提交到仓库。换机器后此目录可能不存在，需要另外配置浏览器验收工具。

`C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/`

- [桌面会议室](C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/browser-evidence/desktop-rooms.png)
- [桌面本人订单已取消](C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/browser-evidence/desktop-cancelled.png)
- [手机会议室](C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/browser-evidence/mobile-rooms.png)
- [手机预约弹窗](C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/browser-evidence/mobile-booking.png)
- [机器可读报告](C:/Users/11707/.codex/visualizations/2026/10/14/01a13869-82c6-7460-b7a2-9081378d5177/browser-evidence/report.json)

## 未覆盖范围

本轮没有运行真实MySQL、Redis Lua、RocketMQ broker或Nacos发现，也没有Docker容器证据；H2验证不替代这些项目。其他浏览器、更多手机尺寸、辅助技术可访问性和浏览器网络层“响应已丢失后重试”未执行；相同payload复用requestId、异参数换键、失败取消重试已有Node行为测试，越权等由实际MVC测试验证，不能把这些称为所有浏览器场景均覆盖。
