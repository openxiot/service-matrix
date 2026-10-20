# Modbus 服务轮询：执行侧设计

`MODBUS.md` 定义了 `function.interval` / `function.polling` 的**语义与校验**，`Modbus-History.md` 定义了
采到的值**落到哪里**（影子 + 历史）。这份文档回答剩下的那个问题：**谁按时去跑，怎么保证不重复、不丢、不把总线打死。**

> 文档形态对齐 `Modbus-History.md`（设计）+ `Modbus-History-TODO.md`（计划）：本文只讲设计，落地清单在
> `Modbus-History-TODO.md` 第三步。设计讨论发生在实现 `ModbusServiceShadow` 之前那一轮，这里落成文字，
> 并补上当时没定的几处（见第九节）。

## 一、形态：同一份代码，两种部署

调度代码就写在 service-matrix 里，用一个开关控制，部署成两种形态：

| | 副本数 | 开关 | 说明 |
|---|---|---|---|
| api | N | 关 | 现在这个，照旧（手工 invoke、定义维护、查询） |
| poller | 1 | 开 | **同一个镜像**，只多打开一个开关 |

**不另起一个服务**，理由是绕不开**定义数据的归属**：`functions` / `request` / `response` 都在
service-matrix 管的 `modbus.services` 里。独立服务要么直接读同一个库（共享库耦合），要么走 API
（而 `invoke` 要求**空间成员**鉴权，得先给机器身份开个口子）。这两条都是真实成本，
而「独立扩缩容 + 故障隔离」的收益，用**独立部署单元**就已经拿到了。
真到了要多 poller、或轮询逻辑要独立演进的时候再拆 —— 那时是「从 service-matrix 搬走」，
而不是「一开始就隔着一条服务边界设计」。

配套的三个决定：

* **开关 `modbus.poll.enabled` 缺省 `false`**：默认关，意味着**任何一次正常发布都不会突然开始轮询**
  —— api 那份部署可能不止一个副本，这个默认值是防止「一次部署让多个副本一起开始采集」的最后一道闸。
  poller 那份部署显式设 `MODBUS_POLL_ENABLED=true`。
* **HTTP 端口照旧开着**：同一个镜像不再分叉出一套「无端口」配置 —— 健康检查、日志页、`/q` 都要它，
  而多监听一个端口对 poller 没有代价。开关只管住**调度循环**。
* **单副本不写死在代码里**：在代码里写死「只能单副本」，那天扩容就是事故；正确的做法是
  运行时状态**落库 + 租约**（第六节），单副本时这套逻辑照跑、只是没有竞争，扩容就变成安全操作。
  **当前前提**是部署侧把 poller 限成一个副本，而这件事不在本仓库里 ——
  `.github/workflows/build-push-deploy-to-azure-staing.yml` 只有一条
  `az containerapp update --name service-matrix`（单 app、无副本数配置），
  所以 poller 是 ops 侧新增的第二个 app（或 revision），代码只提供开关。

**为什么不用 cron / ACA Job / k8s CronJob**：最小粒度是 **1 分钟**，而 `interval` 下限是秒级
（**5 秒**，2026-09-13 从 3 秒提到 5 秒）。这条路的代价是把下限提到 60 秒，
或者接受「配 5 秒、实际 60 秒」的谎言 —— 两个都不划算，所以不走。

**为什么不用 `quarkus-scheduler`**：`interval` 是**每行数据**、不是编译期的表达式，
`@Scheduled(cron = ...)` 贴不上；而仓库里现在还没有这个依赖，Vert.x 是现成的（`vertx.setPeriodic`
或定时唤醒）。要的是一个「运行时注册任务」的循环，不是一套声明式定时器。

## 二、同一条总线必须串行 —— 这条决定调度的形状

一条 RS485 总线 = 一个父设备 `did`。同一个 `did` 下的方法并发发帧，应答会串扰 ——
这个症状在仓库里已经有防线了，`ModbusFrameCodec.decodeResponse` 里那句：

> `unexpected modbus response function code ... 不一致通常是被上一帧的迟到应答串了`

所以**不能给每个 function 各起一个定时器**：那是「按 function 并发」的模型，正好是错的那个。
正确的形状是：

* **按父设备（`did`）分组的串行队列**：队列里放该设备下所有到点的方法，跑完一个再跑下一个；
* **不同设备之间才并行**（不同 DTU、不同总线）。

**不合并帧，且写死**：把同一设备的多个方法合并成一帧 fc 03 在**当前数据模型下做不到** ——
`request` 是前端编好的整帧，而后端手里只有「怎么切分应答」的信息
（`ModbusPollingResponseField` 里的 `bytes` / `format` / `scale` / `value-list` / `bit-list`），
**没有寄存器地址与数量**。要合并只能把点表信息也存进服务，那是另一个量级的改动。
写在这里是为了省得以后有人往上撞。

容量账：不同设备并行、同设备串行，所以**单 poller 吞吐 ≈ 并发上限 ÷ 平均帧往返**。
真卡住的是 manipulation 和 DTU 的承载，不是 poller 本身；因此给一个并发上限加排队就够
（`modbus.poll.concurrency`，建议缺省 20）。真到瓶颈了，再按 `did` 哈希分片到多副本
（租约已经是现成的，见第六节）。

## 三、值落到哪（已定）

定时调用**没有调用者**，「字段 → 值」必须有去处，否则轮询没有意义。这一条已经由 `Modbus-History.md` 定下：
**走 `ModbusHistoryService`**（影子 + 历史），也就是和手动 `invoke` **同一个入口**。

poller 因此不承担「读写与保留」的额外职责：它只负责按时把 `invoke` 打出去，
落库规则、日志压缩、级联清理全在 `Modbus-History.md` 那套里。**手动 invoke 与轮询共用同一条写入路径**
是那里的硬约束（两边分开写，影子迟早和实际值脱节），poller 只是它的第二个调用方。

**阈值告警同样挂在采集链路上，poller 不为它做任何事**（`Modbus-Alarm.md`）：
判定在 `ModbusServiceService.execute` 里、与 `recordHistory` 并列，所以轮询与手动 invoke 都自动带上，
poller 既不需要扩影子、也不需要维护内存基准。三条理由：
* **不扩影子**是**正确性**理由 —— 影子是全量替换写的且**没有事务**，让告警判定成为该文档的第二个写者，
  一次并发就会把 `fields` / `recordedAt` / `lastError` 或告警态悄悄覆盖掉，而「先明细后影子」
  那条顺序不变量正是靠单写者成立的。
* **不用内存基准**：那是单副本才成立的优化，漏一条采样是省成本，**漏一次告警是事故**。
* 它**无论如何都得自己读一次库**（「这个方法的哪些出值开着」是告警表里的状态），
  所以就算扩了影子，在热路径上这里也一分钱都省不到。

## 四、内存基准 ⇒ poller 必须单副本

`ModbusHistoryService` 现在的做法是每次采集**读一次影子**当比较基准。采集的常态是「值没变」
（`Modbus-History.md` 的 95%），那条路径读一次、零写 —— 读本身就成了主要 RU 来源。

所以 poller 在内存里维护每个点位的最后样本（影子文档的内存副本），
**不变的那 95% 变成零数据库往返**：

```
poller: 内存基准 → 判定 → 变了才写明细 + 影子（并把新基准放回内存）
api:    每次 invoke 都读库（N 副本，各副本的内存基准会漂）
```

四条必须写明的边界：

* **只在 poller 单副本时成立**：多副本各自维护基准会漂，各判各的 —— 变化可能被漏记。
  多副本时这一条要退回「每次读库」，所以它与副本数是绑定的（第六节）。
* **手动 invoke 不吃内存基准**：它跑在 API 副本（N 个），每次读库。
* **基准只会比库里旧，不会比它新** —— 旧的基准最多让同一次变化**多落一条**，不会漏落一条
  （判定是「本次值 ≠ 基准」：基准越旧越容易判「变了」）。这是这套缓存可接受的原因。
* **故障态下不吃内存基准**：内存里的 `lastError` 一旦非空，就**每次读库**。
  理由是故障去重按 `message` 比（`Modbus-History.md` 第二节）：若期间有人在 API 侧手动 invoke 成功、
  把库里的 `lastError` 清掉了，而 poller 内存里还留着旧故障，那么**下一次同类故障会被并进上一次的账上**
  —— 少一条故障记录，且起止时间错。故障是少数派，故障期间每次都读库的代价可以忽略。

实现上就是给 `ModbusHistoryService` 加一对「基准由调用方给」的入口
（`record(..., baseline)` / `recordFailure(..., baseline)`，返回新基准供缓存），
现有的 3 参入口保持「读库」的语义给手动 invoke 用。**判定与写入顺序的逻辑一份都不复制。**

落地后的三条口径（与上文的差异，以这三条为准）：

* 4 参入口是**唯一实现**，3 参入口就是「读库取基准 → 委托 4 参」的一行委托 ——
  两个入口都**返回新基准**（`Uni<ModbusServiceShadow>`），null = 这次什么都没写、基准不变。
* 4 参入口的 `baseline` 是**权威的**：传 null 表示「库里确实没有影子」（真·首次采集，会 `insert`），
  而不是「我不知道」。拿不准就传库里读出来的那份 —— 轮询器侧的判定就是
  `ModbusPollBaselines.usable`：没缓存或缓存带故障 → 回退读库，于是上面第 4 条
  「故障态不吃内存基准」自然成立，不需要额外的失效逻辑。
* **缓存不预热**：启动时是空的，每个任务第一次采集读一次库、之后零往返。
  比启动时按任务数读一遍影子便宜，而且冷启动路径与「缓存未命中」共用同一段代码。
* 缓存**只被调度线程与它的工作线程填充**（api 副本上它永远是空的），所以手动 invoke
  自然就是「每次读库」，不需要判断「我在哪个副本上」。

## 五、任务表与对账

运行时状态（`lastRunAt` / `nextRunAt` / 租约）**不写进 `ModbusService` 定义文档** ——
那是接口直接返回的文档，空间图那套投影查询也在用它，混进运行时状态会让「读定义」变成「读实时状态」。
另起一个派生集合：

```
modbus.polls
  { _id, serviceId, pollingIndex, did, interval, nextRunAt, leaseOwner, leaseUntil,
    lastRunAt, lastStatus, lastError, failCount }
索引：(serviceId, pollingIndex) 唯一 + nextRunAt（+ leaseUntil）
```

`failCount`（连续失败计数，第八节要求）是落地时加的；`lastStatus` 只有 `OK` / `FAILED` 两个值 ——
**跳过不是状态**（设备忙、租约没抢到都重排但不写这一列，它记的是「采到了 / 没采到」，不是「轮没轮到」）。

* `did` 冗余一份：调度要按设备分组，不必每次回查服务定义；它同时是分片键的候选。
* `interval` 冗余一份：调度循环不必每次都持有定义文档。
* 只有**读方法、且 `interval != null`、且 `polling != false`** 才入表 —— 判定口径见
  `MODBUS.md` 第七节（`polling` 缺省 = 有周期即启用）。写方法的 `interval` 已被校验拒掉，
  但 poller **仍要复查一次 `ModbusFrameCodec.isRead`**：周期调用写方法等于周期性往寄存器写值，
  这是校验层专门在防的事，而「数据一定能过校验」不是运行期可以假设的前提（老数据、直接改库）。

对账（reconcile）把 `polls` 拉回与定义一致，三种时机都要有：

1. **写入路径**：create / update / delete 服务时同步对齐（`update` 是整体替换 `functions`，
   做一次按 `(pollingIndex)` 的增删改即可）；
2. **启动时全量对一次**；
3. **之后周期性再对一次**（建议 5 分钟）。定义文档**是可能被直接改库的**
   （这个项目的库里已经手工改过字段名），凡是靠写入路径维护的派生数据一定会漂 ——
   兜底对账不是保险，是必需品。

服务被删除时，`polls` 里对应任务一并删掉（与会话历史同级联，见 `Modbus-History.md` 第六节）。

## 六、调度循环

按「库是 Cosmos Mongo API」这个前提：跨分区扫 `nextRunAt <= now` 要扇出到所有分区，
持续跑代价不小。两条路：

* **A. 内存时间轮（推荐）**：启动时把任务全量读进内存，本地按 `nextRunAt` 排一个最小堆；
  到点 → 取该设备的队列 → 调 `invoke` → 写值 → 算下一个 `nextRunAt` 落库。
  稳态下**每秒零次查询**，只有运行时状态在写，任务集变化靠第五节的对账刷新。
* **B. 固定 tick 扫库**：每 T 秒 `find({nextRunAt: {$lte: now}})` 抢一批。实现最简单，
  但是持续的小查询 + 跨分区读，任务一多 RU 就上去。

任务量小（几十~几百）时 B 也够用，但倾向直接按 A 写 —— A 的额外复杂度主要就是那份内存表，
而**那份内存表是 A / B 都要有的**（B 也要靠它按 `did` 排队、判重入）。

三条必须做对的细节（**均已按 A 落地**，实现见 `ModbusPollSchedule` 与 `ModbusPollWheel`）：

* **错过不补**：基准取「上一次**开始**跑的时刻 + `interval`」，已经过期就返回 `now`
  （立刻到期跑**一次**，不按错过的个数连排）—— poller 停了 10 分钟，恢复后只跑一次，
  补跑不能把错过的 120 次补回来，那会把总线打死。`nextRunAt` 必须落库，否则一次滚动发布会
  把所有任务挤到同一秒爆发。
  落地时补了一条：**不漂移**。正常重排的基准是「开始跑的时刻」而不是「跑完的时刻」——
  否则一次 2 秒的慢应答会让周期变成 `interval + 采集耗时`，长期悄悄跑偏。
  采集耗时超过一个周期时从**当下**起算一个完整周期（不连跑）。
* **抖动**：一堆服务的 `interval` 都是 5、创建时间又接近，会天然同相。建任务时给 `nextRunAt`
  加 ±10% 随机偏移（`modbus.poll.jitter-percent`），成本极低。抖动**不会把时刻拉到当下或过去**
  （否则那一秒的唤醒会再取到它，变成原地空转）。
* **重入保护**：某个方法的上一次调用还没回来、下一次又到点了 —— **跳过这一次**（不排队叠加）。
  表现为该点位的采样间隔临时变长，比在总线上堆一队迟到的帧要好。
  落地时这条做成了**结构性**的：`pump` 把到点的任务从时间轮摘出来放进「在飞」，
  同一个任务要么在轮里、要么在飞，没有第三种状态 —— 于是「跳过」不需要任何标记判断，
  也就没有 `lastRunAt + interval` 仍过期导致的空转。同理，`did` 忙的任务**留在轮里等它空**
  （不重排、不丢弃）：周期到了就该采，只是得排队。
* **内存任务必须有 id**：认领（`claim`）与回写状态只认 `modbus.polls` 的 `_id`，而 `_id`
  是插入那一刻才有的 —— 库里**已经有这一行**时（先建服务、后起 poller，最常见的顺序），
  计划对象身上根本没有 id。所以进轮的任务，其 id 必须由对账显式定下来：库里已有就取**行上的**，
  还没有就取**计划预生成**的那个（插入与内存表用同一个，不等插入回写）。
  漏了这条**不会报错**：任务每周期进一次轮、每次都在 `claim` 前被跳过，日志里是每周期一条
  `modbus poll task has no id`，而设备一次都没被采到（2026-09-14 实测踩过，见 `MODBUS.md` 变更记录）。
  同族的一条：库里的行被删了又插会换 `_id`（关掉轮询再打开），内存任务要跟着换到新行上 ——
  停在旧 `_id` 上时认领永远抢不到，而且**同样是静默的**。换的是任务对象上的字段，
  不是把对象换掉（在飞的那一帧不能换对象）。

**租约**（`leaseOwner` / `leaseUntil`，用 `findOneAndUpdate` 单文档原子抢）：
单副本阶段它不是必需品，但它是「扩容是安全操作」的前提，且成本只有两个字段。
**已定：现在就做**（第九节第 2 条）。落地形状与本节初稿不同，以这条为准：

* 合并成**一次原子写** `claim`：抢租约 + 记 `lastRunAt` + 前推 `nextRunAt` 写在同一行 ——
  于是每次采集是 2 次写（认领 + 状态），而不是「抢租约 + 前推 + 释租约」的 4 次。
* **不做提前释放**：下次采集自己续租（`claim` 的过滤条件里「持有者是自己」也算可抢）。
  提前释放在被中断的那一帧上反而危险 —— 那一帧可能已经发到总线上，租约立刻放开会让另一个
  副本马上重发。代价是停机后最多 `lease-seconds` 的空窗（靠自然过期接管）。
* 认领不到（别的副本在跑，或库不可用）**当作这一周期没轮到**：重排、不计失败、不动 `lastStatus`。

## 七、失败与边界

* **单次失败**（DTU 离线 / CRC / 异常码）**只记状态、不动周期，更不能把任务从表里删掉** ——
  一次现场断电就把采集任务删了，是这套东西最难查的故障。
  失败记录本身由 `Modbus-History.md` 第二节去重（同一故障只落一条），poller 不额外落历史。
* **超时必须设**：调 manipulation 必须带超时，否则一个挂死的 DTU 会占住整条设备队列，
  该 `did` 下所有点位一起停摆。超时值要**小于**租约时长（否则租约会在帧还没回来时过期）。
  落地上还有一条：超时必须抛**带分类**的 `ModbusInvokeException`（`NO_RESPONSE`），
  **不要**让它落到裸的 `TimeoutException` —— `ModbusFailureType` 里没有 TIMEOUT 这一类，
  裸超时会被归成 `UNKNOWN`，一个挂死的 DTU 就被记成「不知道什么原因」，排查方向当场丢掉。
* **连续失败退避**（×2，成功复位）是可选优化，先不做 —— 因为间隔本身已经由 `interval` 定住了，
  退避只影响「多久试一次」，而拉长间隔会让恢复变慢。真要做，先量。
* **优雅停机**：正在跑的那一帧跑完再退（`ShutdownEvent` / `@PreDestroy`：停止取任务，
  等在飞的结束）。没跑完的靠 `leaseUntil` 过期被别人接管。
* **`polls` 写失败**（数据库不可用）不该让采集循环停摆：与 `Modbus-History.md` 第四节同口径 ——
  记日志、继续跑，下一个周期再写。

## 八、观测

没有观测这套东西没法运维。最小集合（**已落地**）：

* 每个任务的 `lastRunAt` / `lastStatus` / `lastError` / `failCount`（落 `polls`，查询接口暴露）；
  状态写回**只 `$set` 这几个字段**，不整文档替换 —— 整替换会和调度线程写的 `nextRunAt` 抢。
* 「采集是否还在跑」的心跳：**不做成逐次写库**（那会把省下来的写全还回去，见 `Modbus-History.md` 第三节），
  改为内存里记 `lastTickAt` / `inFlight` / `queueSize` / `busyDevices`，由查询接口读出去。
* 查询接口：`GET /matrix/v1/modbus/poll/{spaceId}`（只要**空间成员**，与 `invoke` / 历史读同口径）——
  返回该空间下服务的任务 + 运行时状态 + 上面那份内存指标，每条任务带上 `serviceName`。
  响应里的内存指标是**本进程**的读数：问到 api 副本时 `queueSize` 一定是 0（它不跑调度），
  看「谁在采」要问 poller 那个副本，或者看每条任务的 `leaseOwner`。
* 每次周期对账打一条 `info` 日志（任务数 + 在飞数）。进程死了 HTTP 就没了，监控本来就能发现。

## 九、未决问题（落地前要定）

1. ~~**`orgId` 传什么**~~ —— **已解决（2026-09-13）**：这个问题的根源是口径错了。
   `invoke` 往下给 manipulation 传的**不是**「谁在调」，而是**设备归属的隔离键** ——
   manipulation 按 `Ownership(did, appId, ownerId)` 三元组查归属，查不到就报 "device not owned"。
   现在口径统一为 **`ownerId` = 设备所在的根空间 id**，服务端由 `device.space.spaceId` 反推
   （`SpaceEntity.rootSpaceId()`）。轮询器没有请求头也不受影响：从任务里的 `device.space.spaceId`
   取根空间即可，`ModbusService.orgId`（已删）不用再管。见 `MODBUS.md` 变更记录 2026-09-13 首条。
   落地补充：归属**每次对账解析一次**（结果缓存在内存任务对象上），解析不出来（空间查不到、
   定义里也没有 `device.space.rootId`）时该任务不派发、报 `CONFIG_ERROR`，而不是猜一个归属去调设备。
2. ~~**租约现在做还是以后做**~~ —— **已定（2026-09-13）：现在就做**，按本节末的写法。
   实现上合并成一次原子写 `ModbusPollRepository.claim`（租约 + `lastRunAt` + `nextRunAt` 同一行写），
   所以每次采集是 2 次写而不是 4 次；并且**不做提前释放**（见第六节末）。
3. ~~**调度循环选 A 还是 B**~~ —— **已定（2026-09-13）：A（内存时间轮）**，
   唤醒用 JDK `ScheduledExecutorService` 单守护线程（B 的 `vertx.setPeriodic` 回调在 event loop 上，
   而调度步要读库，`await()` 会被 Quarkus 拒掉）。
4. **单副本的运维口径**：ACA 那边 `maxReplicas=1` 由谁保证、怎么记录 —— **仍未定（ops 侧）**。
   代码这边已落：启动日志一条 `modbus poll enabled, instance=..., concurrency=..., wakeup=..., timeout=..., lease=...`，
   查询接口也回 `instanceId` 与每条任务的 `leaseOwner`，「谁在采」可查。
5. **并发上限的缺省值**（建议 20）与 manipulation / DTU 的实际承载是否匹配 —— **需要一次实测**，
   缺省已按 20 落，改它只需改部署的 env。
6. ~~**`interval` 下限 3 → 5**~~：**已完成（2026-09-13）** —— 六处同步清单见
   `Modbus-History-TODO.md` 顺带的小事（后端校验 + 单测、`MODBUS.md` 第七节、
   前端常量与类型定义）。

## 十、配置项（拟）

> 七项**已全部落地**（都是代码内的 `@ConfigProperty` + `defaultValue`，`application.properties`
> 里只有一段说明、不设值）：`modbus.poll.enabled` 是唯一的部署开关，poller 那份设 env
> `MODBUS_POLL_ENABLED=true`。`invoke-timeout-seconds` 必须小于 `lease-seconds`，
> 不满足时**不阻断启动**（同一份代码也跑在 api 副本上），只把超时钳到 `lease-1` 并打一条 error 日志。

| 配置 | 缺省 | 说明 |
|---|---|---|
| `modbus.poll.enabled` | `false` | 本实例是否跑调度循环；只有 poller 那份部署设 `true` |
| `modbus.poll.concurrency` | `20` | 在飞请求数上限（不同 `did` 之间并行；同 `did` 串行） |
| `modbus.poll.wakeup-millis` | `1000` | 调度循环的唤醒粒度（`interval` 是秒级，1 秒足够） |
| `modbus.poll.reconcile-seconds` | `300` | 兜底对账周期 |
| `modbus.poll.jitter-percent` | `10` | 首次 `nextRunAt` 与每次重排的随机偏移 |
| `modbus.poll.invoke-timeout-seconds` | `10` | 单次调用的超时（必须小于租约时长） |
| `modbus.poll.lease-seconds` | `60` | 租约时长；单副本阶段无竞争，但字段照写 |

## 十一、落地顺序

对应 `Modbus-History-TODO.md` 第三步，顺序即依赖。**1–6 全部已实现（2026-09-13）**：

1. ~~**先定 `orgId`**（第九节第 1 条）~~ —— **已定**（`ownerId` = 设备所在根空间 id），
   不再是阻塞项。
2. `polls` 集合 + 任务对账（写入路径 + 启动 + 周期兜底），只把读方法入表。
   → `db/modbus/poll/ModbusPoll` + `ModbusPollRepository`，`api/modbus/poll/ModbusPollPlan`（入表口径）、
   `ModbusPollReconciler`（对账）。
3. poller 进程（同代码、开关控制、单副本）：内存时间轮 + 按 `did` 串行 + 租约。
   → `ModbusPollWheel` + `ModbusPollScheduler`（+ `ModbusPollSchedule` 时间算法、`claim` 抢租约）。
4. 内存基准接进 `ModbusHistoryService`（第四节）+ 失败状态 + 抖动 + 超时 + 优雅停机。
   → `ModbusPollBaselines` + `record` / `recordFailure` 的 4 参入口 + `ShutdownEvent`。
5. 观测（第八节）。→ `ModbusPollResource`（`GET /matrix/v1/modbus/poll/{spaceId}`）。
6. 影子对账：定义变更（改了 `response`、删了方法）后，按当前定义清掉影子里
   **不再存在**的 `(pollingIndex, field)`，否则影子留下永远收不到更新的键。
   → `ModbusShadowPlan` + 写入路径 / 启动全量扫。
7. 之后才谈扩容（租约已经是现成的）与合并帧（要改数据模型）。

两个实现时才定下来的口径（与上文正文的差异，以这两条为准）：

* **对账不追「立即生效」**：定义是在某个 api 副本上改的，poller 是另一个进程，
  它要到下一次周期对账（缺省 5 分钟）才知道。写入路径对账的意义是**让 `polls` 表始终是对的**
  （poller 重启即恢复正确），不是跨进程通知。**改动生效延迟 ≤ `reconcile-seconds`**。
* **对账与调度只在调度线程上跑**：写入路径的对账只写库 + 把内存表置脏，由下一次唤醒重建，
  于是时间轮不需要任何锁（类注释里写死了这条）。
