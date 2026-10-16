# SmartOffice 云智办

在现有会议室预约系统上完成的渐进升级：Java17、Spring Boot3.5、Vue3，最小三服务、可靠预约、事务Outbox和真实RocketMQ SDK通知/审计。

| 模块 | 作用 |
| --- | --- |
| shared | JWT、Result、异常、安全与AOP基础 |
| user-service | 登录、注册、有效用户查询，独立用户库 |
| backend | 会议室、预约/取消、缓存、Outbox/通知/审计，独立预约库 |
| gateway | 原API与/api路由、身份头清理、Nacos发现 |
| src | Vue3 / TypeScript / Element Plus 前端 |

预约按半开时间区间判断。数据库房间行锁与READ_COMMITTED是最终互斥保障；Redisson辅助削峰、异常降级，用户/requestId唯一键保证重试幂等，取消CAS及管理员逻辑删除保护历史数据。缓存只影响展示，使用UUID版本Lua防旧回填。Outbox与订单同事务，MQ发送独立重试，消费幂等记录通知与审计。

Spring Cloud2025.0.0 / Alibaba2025.0.0.0 / Nacos3.0.3 / Sentinel1.8.9 / RocketMQ Java SDK5.3.1。OpenFeign只用于创建前有效用户检查；限流位于预约服务入口。订单没有待确认状态，因此未虚构超时取消。

## 本地启动

完整环境使用 `docker-compose.upgrade.yml`（独立项目/卷），首次私有环境生成、启动及迁移步骤见 [runbook](docs/runbook.md)。Docker运行后访问前端 `http://localhost:18080`，演示用户 admin/user 密码123456，仅用于本地隔离演示。浏览器同源 `/api` 经过Nginx/Vite和Gateway。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -B -ntp clean verify
npm test
npm run build
```

IDEA载入根 `pom.xml` 并使用Java17 Maven runner。原单体 `docker-compose.dev.yml`、`deploy/run-host.sh` 作为历史示例保留；新版部署按runbook。

无需Docker的真实三JAR+Vite/H2冒烟：`deploy/upgrade/smoke-local.ps1 -H2Jar <本机Maven中的H2驱动路径> -WithFrontend`。全部绑定loopback随机端口、关闭MQ/Nacos、模拟Redis故障；自动停止本次进程，详见runbook。

## 已验证与待验收

2026-10-14真实环境验收完成：Docker十容器healthy；真实MySQL/Redis/RocketMQ/Nacos、Gateway/Feign与Sentinel链路通过。API最终17/17、迁移7/7、MySQL行锁/回滚、Redis与用户服务故障恢复、MQ重复/发送失败/消费失败/重启均通过；当前镜像的Edge桌面/手机浏览器9项通过。工程回归为Java98测试、前端8测试、vue-tsc和生产构建通过。命令、日志与失败修复历史见[真实环境验收报告](docs/real-integration-report.md)。

最终本地预约库47订单/47条SENT Outbox、通知/审计/receipt各47，无活跃重叠或遗留故障trigger。验收fixture和八个持久卷保留。未做生产部署、HA/断电验证、长期容量压测或DLQ耗尽回灌；不能把本机集成结果写成生产性能。前端仍有包体/PURE注释告警。

## 中文工程文档

- [升级进度与实际测试](docs/upgrade-progress.md)
- [真实环境验收报告](docs/real-integration-report.md)
- [架构与服务边界](docs/architecture.md)
- [核心流程与一致性](docs/core-flows.md)
- [技术取舍和未实现事项](docs/tech-decisions.md)
- [启动、迁移与故障排查](docs/runbook.md)
- [面试介绍与关键代码](docs/interview-notes.md)
- [独立审查修复](docs/review-findings.md)
- [浏览器验收与截图](docs/browser-qa.md)

原源码、环境和IDEA Maven入口均有ignored本地备份；没有推送、生产连接或删除数据卷，当前升级分支未提交。各阶段日志和Surefire报告在本地ignored目录，最终摘要与命令在进度文档。
