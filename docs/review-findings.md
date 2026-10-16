# 独立审查与修复记录

独立只读审查发现3个 Important，没有Critical；已在一次修复阶段落实回归，不以审查者口头结论替代测试。

| 问题 | RED 证据 | 修复 | GREEN 证据 |
|---|---|---|---|
| 新MQ组默认LAST_OFFSET跳过已发事件 | 真实SDK配置实例 FIRST预期、LAST实际，review-red.log | 消费者显式FIRST_OFFSET；已有offset继续正常 | RocketMqConsumerConfigurationTest + 全套96/96 |
| 锁后MyBatis一级缓存仍保留首次幂等miss | 两个真实事务强制首次miss后争房间锁，一请求409，review-red.log | findByRequest @Options(flushCache=TRUE) | ConcurrentIdempotencyTest双响应同ID+一订单一Outbox；全套96/96 |
| 前端旧env直指booking服务，拆分后登录失败 | Vite实际development/production mode解析均8081，env-review-red.log | 先独立备份env，再改/api；示例同步 | 两mode回归+全部前端8/8+build；Vite→三JAR真实登录冒烟 |

额外定位并修复：非Web Feign测试误启Knife4j造成缺SpringDocConfigProperties；关闭该测试无关文档自动配置后2/2真实HTTP契约通过，最终完整verify通过。生产Web场景文档配置不因测试修复而改变。

## 审查边界与采取的判断

- 真实MySQL、Redis Lua/恢复、RocketMQ/Nacos及Docker启动：Docker daemon缺失，结果待验收；H2和静态配置不可替代。
- 浏览器视觉与交互：项目外Playwright + Edge已验收桌面/手机真实操作，修复容量SVG撑开卡片、手机480px弹窗裁切以及本地CORS来源不完整，详见browser-qa.md。尚未覆盖所有浏览器和故障注入场景。
- 单broker、保留期和DLQ恢复：配置已改为SYNC_FLUSH，但仍是单节点ASYNC_MASTER，无真实broker运行验证；本地演示不承诺生产级可靠性，当前没有自动DLQ回灌。
- JWT即时撤权：新预约在线检查用户；其余接口按有效期令牌，若此取舍不适合未来业务，需要单独增加撤销策略，不能声称已实现。
- 网关重复限流：Sentinel放在后端预约入口，保护经网关及直连请求；是单实例QPS，后续全局配额需另设计。
- 前端包体警告和依赖PURE注释警告：构建成功，保留现有组件库引用方式；首次下载性能可后续优化，不隐藏告警。

没有未修复的 Critical/Important 审查问题。以上未运行的外部验证和明确未实现功能继续在 upgrade-progress.md / tech-decisions.md 跟踪。

## 后续接口与恢复验证

非法订单路径参数原返回500，实际MVC测试复现后修复为400；同时补齐405/404/415统一错误响应，完整Java测试增至97项。浏览器回归覆盖紧凑图标、手机弹窗边界、真实双击仅一POST、注册/登录/取消。恢复后的消息测试出现时间舍入导致的未到期扫描：独立H2探针证实后，测试显式到期并通过5/5；不通过睡眠或忽略断言掩盖失败。最新整体结果与日志见upgrade-progress.md。
