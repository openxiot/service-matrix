# Modbus 采集历史：实施计划

设计见 `Modbus-History.md`。图例：`[x]` 已完成，`[ ]` 待办。

## 第一步：落库规则与存储层（已完成）

- [x] `ModbusHistoryPolicy`：变位 / 死区 / 保活打点的纯判定（逐字段），无数据库依赖，可单测。
- [x] `ModbusServiceLastPolling`：当前值 + 比对基准 + 故障态（`lastError` / `errorAt`），按 function 一条。
- [x] `ModbusServiceHistory`：变化日志，按 field 一条。
- [x] `ModbusServiceFailure`：执行失败记录，按 function 一条，**同一故障只落一条**。
      失败原因拆成 `type` + `remoteCode` + `message`（前端按类型聚合，见下条）。
- [x] `ModbusFailureType`：10 类失败（设备侧 / 报文侧 / 定义侧 / 平台侧）+ `UNKNOWN` 兜底；
      `ModbusInvokeException` 把分类与远端原始码带在异常上（继承 `IllegalArgumentException`，
      保证 Resource 的错误响应契约不变）。
- [x] 在**每个失败点**打分类：`ModbusResponseParser`（异常码 / CRC / 帧长 / 帧成形 / 字段定义）、
      `ModbusServiceService`（坐标不全 / 链路失败 / 父设备报错 / 无帧）。
      记录端只做透传，拿不准的归 `UNKNOWN`。
- [x] 三个仓储 + 启动建索引（沿用 `modbus.index.enabled` 开关）。
- [x] `ModbusHistoryService.record`：读影子 → 判定 → **追加历史 → 更新影子**（顺序即正确性）。
- [x] `ModbusHistoryService.recordFailure`：同一条故障持续存在时零写操作；恢复后的第一次成功
      **强制落全字段**（否则曲线上分不清「已恢复」和「仍在故障」）。
- [x] 接进 `ModbusServiceService.invoke`：手动调用即开始记录；记录写失败既不影响 invoke 的成功响应，
      **也不改写失败响应**。顺带让 `execute` 不抛同步异常 —— 否则同步抛的异常绕过 `onFailure`，
      那次失败既不进故障记录、也不在影子上留故障态。
- [x] 删服务 / 删设备级联删该服务的影子、历史与失败记录。
- [x] 配置项 `modbus.history.deadband` / `modbus.history.keep-alive-seconds`。
- [x] 纯单测，不连库：`ModbusHistoryPolicyTest`（判定规则）、`ModbusHistoryServiceTest`
      （写入行为，钉住「不变则零写操作」「先明细后影子」「明细失败影子不动」「同故障不重复落」
      「恢复落全字段」五条约定）、`ModbusResponseParserTest.classifiesEveryFailureType`
      （每一类失败的分类与异常码）、`ModbusServiceServiceTest`（调用路径的分类 +
      「记录失败不改写原错误」）。共 52 个。
- [x] **补漏（2026-09-14）：`value` 声明成 `Object`，缺一条按运行时类型分发的 codec，历史一条都写不进去**
      （`db/mongo/ObjectPropertyCodecProvider`，见 `MODBUS.md` 2026-09-14 变更记录）。
      这一条提醒了两件事：**上面第 22 行那条「记录写失败不影响 invoke 的成功响应」是故意的，
      但也正因为它，写入全挂了一周都没人发现** —— 现网 `modbus.service-histories` 集合压根不存在；
      另外「纯单测全绿」证明不了落库链路，编解码这一格只有真跑一遍（或像
      `ObjectPropertyCodecProviderTest` 那样在内存里搭一张同形的注册表）才看得见。
      `ObjectPropertyCodecProviderTest`（7 例，不连库）就是补的这张网。

## 第二步：读取接口（已完成）

- [x] `ModbusServiceLastPollingCodec` / `ModbusServiceHistoryCodec` / `ModbusServiceFailureCodec`。
      最后一个是双边契约里少见的「聚合由服务端做」：`summary` 是**这批 `items`** 的
      `byType` / `byRemoteCode`（条数降序、条数相同按键升序），前端不必再排。
- [x] **当前值接口** `GET /matrix/v1/modbus/history/current/{spaceId}/{serviceId}`：按服务列出影子 ——
      带 `lastError` / `errorAt`，前端得能显示「最后已知值 + 它从哪一刻起不再更新」，
      而不是把陈旧值当最新值画。没有影子的方法**不出现**在结果里（「从没调用过」≠「有值但很旧」）。
- [x] **历史区间接口** `GET .../range/{spaceId}`：
      `serviceId` + `pollingIndex` + `field` + `from` + `to` + `maxPoints`。
  - [x] **carry-in**：区间起点之前最近的一条单独查一条带回（`carryIn` 键，可能没有），
        不混进 `points` —— 它不是区间的数据，是区间起点的状态。
  - [x] **服务端降采样**：按 `maxPoints`（缺省 500 / 封顶 2000）分桶，
        桶内 `count` / `first` / `last` / `min` / `max` / `avg`，且**空桶不出点**。
        桶的所有量都按**阶梯函数**算（`first` / `last` 是桶起止时刻的**状态**，不是桶内第一条/最后一条样本），
        `avg` 按持续时间加权 —— 落在纯函数 `ModbusHistoryWindow` 里，10 个单测钉住。
        没做成聚合管道：算子的线上支持没法验证，而正确性全在折算规则上。
  - [x] **一次最多 20000 条原始样本，超了报错而不是静默截断**
        （取 `上限 + 1` 条判溢出；截断会把曲线尾巴悄悄抹掉，前端看起来一切正常）。
- [x] **故障列表接口** `GET .../failures/{spaceId}`：按服务 + 时间段列失败记录（前端在曲线上打阴影区）。
      注意一次失败覆盖的是整个 function，不是单个字段（`pollingIndex` 不传 = 整个服务）。
      支持**按 `type` 过滤 + 聚合**（故障类型分布）与**按 `remoteCode` 下钻**
      （哪个从站、哪个异常码最多）—— 分类落成字段就是为了这两个查询。
      `type` 拼错**报错而不是返回空列表**；`limit` 缺省 200 / 封顶 1000，多取一条置 `truncated`。
      索引仍是 `(serviceId, pollingIndex, at)`：带 `pollingIndex` 的查询吃满索引，
      不带时只有第一段用得上、排序靠内存 —— 故障是去重过的，行数很少，先这样。
      真要「整个服务按类型聚合」也快了，再补 `(serviceId, at)`（写侧索引已经不少，先量了再说）。
- [x] 鉴权口径与现有接口一致：路径上带 `spaceId`，查历史要**空间成员**（与 `invoke` 同口径）。
- [x] 路由另开 `/matrix/v1/modbus/history`（历史与定义是两回事：将来给只读的历史看板单独授权时，
      不用把服务定义的写权限一起放开）。时间参数一律 epoch 毫秒。
- [x] 读侧单测（不连库）：`ModbusHistoryWindowTest`（carry-in 参与阶梯函数、空桶、
      `min ≤ avg ≤ max`、闭区间右端的零时长样本、非数值点位）、`ModbusHistoryCodecTest`
      （聚合顺序与 `lastAt`、null 键的取舍）。含第一步共 231 个单测。
- [ ] 读侧还需要一个**端到端的 `@QuarkusTest`**（这一层真的要连 Mongo，本机跑不了；
      只 mock 仓储的接口测试倒是能跑，见第三步末尾）：
      写一批样本再读回来，覆盖「三接口 + 鉴权 + 参数校验报错」这一层。
      参数校验本身是纯函数，等有了连接串可以先把 `ModbusHistoryQueryService` 的校验分支补上。

## 第三步：轮询器接入

设计见 **`Modbus-Service-Poller.md`**（形态、串行、任务表、调度循环、内存基准、未决问题）。
下面按那份文档的落地顺序列，顺序即依赖：

- [x] ~~**先定两件事**（文档第九节）~~：轮询调用传什么归属 —— **已定（2026-09-13）**：
      传的既不是 `X-Org-Id` 也不是 `ModbusService.orgId`（该字段已删），而是 **`ownerId` = 设备所在的
      根空间 id**（`SpaceEntity.rootSpaceId()`，轮询器由 `device.space.spaceId` 反推）。
      见 `Modbus-Service-Poller.md` 第九节第 1 条、`MODBUS.md` 变更记录 2026-09-13 首条。
- [x] ~~**还剩一件**（文档第九节第 2 条）~~：租约 —— **已定（2026-09-13）：现在就做**。
      单副本下它等于空跑，但扩容就从「事故」变成「安全操作」，而事后再补要先清一遍历史数据。
- [x] `modbus.polls` 任务表 + 对账（写入路径 + 启动 + 周期兜底）：
      只把**读方法、有 `interval`、`polling != false`** 的 function 入表（口径见 `MODBUS.md` 第七节），
      并复查 `ModbusFrameCodec.isRead`。删服务 / 删设备时一并清理。
- [x] poller 本体（同代码双部署：api N 副本 + poller 1 副本，`modbus.poll.enabled` 开关，缺省关）：
      内存时间轮 + **按 `did` 串行**（同一条 RS485 不能并发）+ 并发上限 + 重入保护（上次没跑完就跳过）。
      `nextRunAt = max(now, lastRunAt + interval)`（**错过不补**）、±10% 抖动、启动从库里恢复。
      **不合并帧**（数据模型做不到，文档第二节写死了）。
- [x] **单副本内存基准**：poller 在内存里维护每个 function 的最后样本，省掉每次采集的一次影子读。
      **只在 poller 单副本时成立**；写这一层时要在代码里写明这个前提（多副本会让各副本基准漂移、漏记变化）。
      手动 invoke 走的是 API 副本（N 个），所以它**不用**内存基准，每次读库 —— 见 `Modbus-History.md` 第四节。
      **故障态下不吃内存基准**（每次读库）：否则会把一次新的同类故障并进上一次的账上。
- [x] 失败与边界：轮询失败（DTU 离线 / CRC / 异常码）不落历史、不更新影子，只记状态；
      只动 `lastStatus`，**不删任务、不动周期**；调用必须带超时（小于租约时长）；优雅停机。
- [x] 观测：`lastRunAt` / `lastStatus` / 连续失败计数 + 单条心跳（不是每次采集都写）。
- [x] 服务定义变更（改了 `response`、删了方法）后影子的对账：
      按当前定义清掉不再存在的 `(pollingIndex, field)`，否则影子留下永远收不到更新的键。

落实位置（2026-09-13）：`db/modbus/poll/`（`ModbusPoll` + 仓储 + Codec）与 `api/modbus/poll/`
（纯函数件 `ModbusPollPlan` / `ModbusPollSchedule` / `ModbusLastPollingPlan`、`ModbusPollWheel` 时间轮、
`ModbusPollBaselines` 内存基准、`ModbusPollReconciler` 对账、`ModbusPollScheduler` 调度循环、
`ModbusPollingResource` 查询接口）；`ModbusHistoryService` 加了一对「基准由调用方给」的入口。
纯单测 66 个（schedule / plan / wheel / baselines / shadowPlan 五个新文件 + 两个既有文件扩充）全绿；
`@QuarkusTest` 的 `ModbusPollingResourceTest` 5 个也**本机跑绿**（把连库的 bean 全 `@InjectMock` 掉即可，
见 `MODBUS.md` 变更记录）—— 早先「本机跑不了 `@QuarkusTest`」的说法只对**真的要连 Mongo** 的那几个成立。

## 第四步：运维与容量

- [x] ~~**建集合时指定分区键**：`service-histories` 用 `/serviceId`（查询恒带它）。
      Cosmos 分区键建集合后**不可改**…~~ —— **结论：本部署没有这件事，无需动作**
      （2026-09-13 查证）：staging / prod 跑的是 **Azure DocumentDB**
      （原 Cosmos DB for MongoDB vCore），资源类型 `Microsoft.DocumentDB/mongoClusters`，
      连接串主机 `*.global.mongocluster.cosmos.azure.com`；它是建在 **PostgreSQL** 上的
      开源 DocumentDB 引擎，**分片由服务托管**（分片类命令基本被屏蔽，只留 `getShardMap`
      与 balancer 三个），应用侧不能也不该指定分区键。
      **保留的口径**：查询与写入**恒带 `serviceId`** —— 现有复合索引全部以它打头，
      将来换存储或自建分片时它决定迁移成本。若是哪天换成 RU 版 Mongo API 或自建 MongoDB，
      「数据进去之前定分区键」会重新变成硬约束。
- [x] ~~同上，`service-last-polling` 与 `polls` 也按 `/serviceId`~~ —— 同上，随上一条一并结论化
      （`polls` 是纯派生数据、清空重来无害，真要动口径重建即可，同样不存在「不可改」的约束）。
- [x] **保留策略**（2026-09-13 落地，见 `Modbus-History.md` 第六节）：
      `service-histories` 在 `at` 上挂集合级 TTL 索引，保留 **90 天**
      （`modbus.history.retention-days`，缺省即生效，`<= 0` = 关）。
      故障记录与当前值不设（理由见文档）。判定在纯函数 `ModbusHistoryRetention.decide`，
      仓储 `ensureIndexes(long retentionSeconds)` 照着执行、失败留告警。
      纯单测 15 例（`ModbusHistoryRetentionTest` 8 + `ModbusServiceHistoryRepositoryTest` 7，
      后者喂 `listIndexes` 的真实返回形态）；测试进程 `modbus.index.enabled=false`，不跑这段。
- [x] **TTL 在 Azure DocumentDB 上到底删不删文档 —— 已冒烟，可用（2026-09-13）**：
      staging（serverVersion 8.0.0）上建 `modbus/ttl-smoke` → `createIndex({at:1},{expireAfterSeconds:60})`
      **建成功** → 插一条 `at` 是 2 分钟前的文档 → `t+15s` 还在、**`t+30s` 已被服务端删掉** →
      临时集合已 drop。**批删任务不需要做**，第六节的保留策略就是终态。
      顺带确认 `modbus.service-histories` 目前**还不存在**（库里只有 `configs` / `services`），
      所以既没有「既有 `{at:1}` 普通索引撞 key」的风险，90 天保留也**不会删掉任何现有数据**。
      台账（索引形态、时间点）记在 `Modbus-History.md` 第六节。
- [ ] **实测变化率**：加按字段的「采集次数 / 落库次数」计数器，跑一两周。
      `Modbus-History.md` 第二节那个「95% 不变」是经验值，而整套成本模型只依赖它一个数 ——
      模拟量点位多的话它不成立（死区能救一部分）。拿到真实分布再谈容量与换存储。
- [ ] 换存储的触发线：落库后 > 500 条/s 时，把原始明细换 Azure Table Storage
      （`SampleSink` 式抽象，业务代码不动）。

## 顺带的小事

- [x] `interval` 下限从 3 秒改 5 秒（2026-09-13 落地）。实际落在 **5 个文件**
      （原估 4 处，漏了前端类型定义那处 JSDoc）：`ModbusServiceValidator`（常量 + 类注释）、
      `ModbusServiceValidatorTest`（`acceptsIntervalBounds` 的 3 → 5、
      `rejectsIntervalOutOfRange` 的期望消息，另补「4 秒被拒」一例）、
      `MODBUS.md` **第七节**的 `5 ≤ interval ≤ 3600`（**第八节变更记录里的历史叙述不动** ——
      那是当天发生过的事）、前端 `device.service.editor.ts` 的常量与注释、
      前端 `ModbusService.ts` 的 JSDoc `5 ~ 3600`。
      前端模板与 i18n 是从常量绑定 / 通用词，自动跟随（`npm run build` 与 `ng test` 都过）。
- [x] `MODBUS.md` 第五节补了：`invoke` 会顺带落历史（成功写明细 + 更新影子、失败写故障记录），
      手动调用一次 = 记一次。
