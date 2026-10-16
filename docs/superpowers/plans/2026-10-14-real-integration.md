# SmartOffice 真实环境验收执行清单

> 执行方式：根代理顺序启动/故障注入，独立脚本按并行任务技能准备；以实际命令与日志为准，不增加技术组件。

**目标**：对已有MySQL/Redis/RocketMQ/Nacos三服务架构执行真实本地验收。
**范围**：用户2026-10-14真实环境验收要求；架构与安全约束见docs/runbook.md、architecture.md。

- [x] 读取Git、runbook、Compose，确认现有升级分支与备份。
- [x] 正常启动已有Docker Desktop，核对Linux Engine及已有项目资源。
- [x] 私有.env.upgrade只在不存在时生成，不输出凭据。
- [x] 复现Docker Hub鉴权连接失败，使用现有系统代理的当前进程变量后Redis pull成功，不改全局代理。
- [x] 构建五类应用/派生镜像，拉取MySQL/Redis/Nacos；记录失败和修复路径。
- [x] Compose up --wait，十服务健康；读取版本、镜像digest和服务注册。
- [x] MySQL真实HTTP并发、幂等、取消/替换竞争、CAS和rollback；SQL索引/时间精度；迁移只在新建验收schema中执行，保留原行。
- [x] Redis应用Lua真实执行、正常回填、空值TTL、失效后旧token拒绝、加载锁；停止仅本项目Redis后检查预约/详情MySQL降级，再启动恢复。
- [x] RocketMQ真实发送/消费和通知/审计/receipt；重发原eventId；停止Broker保留预约与PENDING重试，再启动恢复；只对验收数据注入消费失败，记录重试后恢复。
- [x] Nacos实例列表、Gateway路由、Feign发现调用与用户服务不可用503；Sentinel burst实际429；不以代码配置替代运行。
- [x] 浏览器真实MySQL后端注册/登录/预约/取消/权限与双击，保留截图和console错误。
- [x] 最终记录docs/real-integration-report.md与upgrade-progress.md，清楚区分PASS/FAIL/NOT_EXECUTED及简历表述。

最终真实API17项、迁移7项、四个MySQL/Redis/用户服务场景各5阶段、MQ故障20/18/23断言、当前镜像浏览器9项通过。最终只读状态为十容器healthy、47订单/47SENT/三表各47、无活跃重叠或遗留trigger。正常stop/start未扩大解释为断电/HA/性能压测，保留边界见真实验收报告。

独立文件责任：real-api-check.mjs负责真实API/SQL验收；RealMiddlewareProbe.java与run-middleware-probe.ps1负责实际Redis/MQ应用依赖探针；根代理负责环境、运行、故障注入和最终报告。任何全局设置更改、删除卷/已有数据、生产访问或推送均不属于执行步骤。
