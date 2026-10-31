# SmartOffice 本地 Docker 开发恢复计划

> 使用 systematic-debugging 定位环境问题，按用户授权直接执行；服务配置由独立只读任务核对。

**目标：** 复用现有升级 Compose、私有环境和 SQL，恢复 Docker 中间件 + IDEA 宿主服务 + Knife4j。

**约束：** 不重置 Docker，不删卷/数据，不覆盖原配置，不操作 PetTravel，不推送；保留用户 DTO、缓存接口、POM 格式和 ZIP 修改。用户已选择方案 1：旧盘不动，创建独立全新开发库；使用 smartoffice-host-dev 项目、deploy/.env.host-dev 新凭据，不恢复历史数据。

- [x] 只读核对 Docker、资源清单、Git、部署与备份；记录 Engine 的沙箱访问限制。
- [x] 旧盘 Get-DiskImage Attached=false，Docker CustomWslDistroDir 指向 C 盘；只读完整 SHA256 基线保存。确认泄露的三个凭据名和 jsonl 记录位置，不复述值，新环境八个凭据全部独立。
- [x] 增加独立宿主开发 Compose 覆盖，loopback 映射两个 MySQL、Redis、Nacos HTTP/gRPC、MQ；Broker 发布宿主可达地址，静态 Compose 校验通过。
- [x] 原版本官方镜像完整校验导入，构建原 MQ Dockerfile；六个中间件 healthy / 重启 0，新建八个独立卷。没有改全局代理或覆盖旧盘。
- [x] Windows 官方 blob 通路验证通过，原 MySQL8.0.46 全部 blob digest/RootFS 校验后导入。独立 MySQL 两容器及 Redis healthy/0 重启；原 SQL 初始化 7 张业务表和索引/精度核对通过，Redis 实读写通过。
- [x] 生成新的 ignored 宿主启动配置，重复执行验证已有配置不覆盖；基线115项，发现并最小修复真实OpenAPI兼容性错误后，最终 Maven verify 118/118，0失败错误跳过，三JAR打包成功。
- [x] 三个 JAR 用 IDEA 等价私有配置启动；真实 Nacos/Gateway/Feign/MQ 工作。验收后只停止本任务 JAR，22:50确认三个端口空闲，中间件保留运行。
- [x] 本轮真实HTTP/SQL探针90/90：登录、查询、权限、容量31拒绝且无INSERT、容量30创建、requestId、重复取消、Outbox发送和通知审计消费。专用fixture保留，不删除已有业务数据。
- [x] Knife4j页面/静态资源/OpenAPI已验证；浏览器文档显示、调试面板、授权输入已验证，但发送后未观察到GET，整体浏览器验收未通过，明确记录，未冒称成功。
- [x] 旧VHD收尾完整SHA256/长度/修改时间均不变；最终217文本与18个新旧秘密值比对无匹配，私有env/properties/logs/用户ZIP均忽略，无新提交或push。
- [x] 写最终恢复报告、实际失败与成功证据、每日PowerShell及IDEA命令。未完成范围：Knife4j浏览器发送、旧数据恢复、HA/故障注入、Vue端到端、Nacos控制台登录。

具体证据写入 `logs/docker-recovery-20261020/`，最终结论写入 `docs/docker-development-recovery.md`；不使用旧 H2/Mock 结果冒称本轮真实中间件通过。

## 2026-10-21 续验（不重做已完成迁移/初始化）

- [x] Git状态及昨天证据核对，保留原修改和用户新目录清单；没有提交/push。
- [x] 旧VHD Attached=false，Desktop配置C盘，完整SHA256/长度/修改时间与昨日一致。
- [x] 当前真实Maven verify 118项通过，0失败/错误/跳过，三JAR打包；日志独立存20261021。
- [x] Compose config退出0；重复生成配置不覆盖，五个私有文件前后哈希不变；八新秘密与旧不同、旧工具记录三项类别及Git忽略复核。
- [x] 限定SQL/迁移检查：当前新库已含requestId，不重放非幂等V1或初始化；准备host-dev专属Lua和扩展API探针，尚未执行。
- [ ] 本日Engine恢复：启动已有Desktop后被sailor-ingest.sock/Windows1920阻塞，已请求用户开始菜单手工启动；不改全局runtime/配置、不reset。
- [ ] 本日容器、MySQL/Redis Lua/Nacos/MQ、三服务和真实API验收：受Engine阻塞，不能沿用昨天通过数。
- [ ] Knife4j真实浏览器发送：离线假设已准备，尚未在运行服务上确认。

本日实际状态与每日命令见 `docs/docker-development-recovery-20261021.md`，本日证据 `logs/docker-recovery-20261021/`，昨日证据原样保留。

## 2026-10-21 D 盘项目迁移后续接

- [x] 工作目录和 Git 根均为 `D:\A-project\smartoffice-rebuilt`；未覆盖既有未提交代码，无提交或推送。
- [x] 用户确认 Desktop 新数据目录为 `D:\DockerData\DockerDesktopWSL\DockerDesktopWSL`；旧 D 盘独立未挂载，启动前完整 SHA/长度/修改时间与原基线一致。本轮没有迁移数据。
- [x] 复用既有六中间件和八命名卷；只读确认原数据库已初始化，将两个 MySQL 和 Broker 的旧 C 盘绑定改为 D 盘，原数据存在，六服务 healthy。
- [x] 既有三 JAR 使用原私有配置从 D 盘启动；本轮真实 API/SQL 89/89，Redis 原应用 Lua 21/21。
- [x] Knife4j 真实浏览器补验已执行：发送未通过，具体阻塞为 `validateCommonHeaders` 提示“请求头 bearerAuth 不能为空”；原媒体类型标志假设被实测推翻，浏览器已关闭。
- [x] 本轮收尾：旧盘完整 SHA/长度/修改时间不变且未挂载；原八卷创建时间/挂载点未变，业务代码及五个私有配置未变，仅停止本次三 JAR，六中间件保留运行。
- [ ] 后续限定任务：定位 Authorize 保存/传播到 bearer 请求头的机制并补完真实 UI GET 200，暂未修改业务代码或宣称发送通过。

本轮报告 `docs/docker-development-recovery-d-drive-20261021.md`，新证据 `logs/docker-recovery-20261021-d-drive-resume/`；早期失败和既有通过记录均保留。
