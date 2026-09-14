# Modbus 阈值告警

读方法的出值可以配阈值：越限产生一条告警，前端列出来、点「处理」回执。
与 `Modbus-History.md`（采集历史）是两件事：那边记的是**采到什么**，这边记的是**值不对**。

## 落地状态

| 步骤 | 内容 | 状态 |
|---|---|---|
| 1 | 配置模型（`ModbusServiceFieldAlarm` + 两处 `alarm` + Codec） | **已落地**（见第二、三节） |
| 2 | 判定口径 `ModbusAlarmPolicy`（纯函数） | **已落地**（见第四、五节） |
| 3 | 定义校验 `ModbusServiceValidator.validateAlarm` | **已落地**（见第六节） |
| 0 | Azure DocumentDB 上验「唯一部分索引」 | **待做，且门控第 5 步**（见第七节） |
| 4 | 告警文档 `ModbusServiceAlarm` + 仓储 + Codec | **已落地**（见第八节；唯一部分索引按第七节刻意未建） |
| 5 | 采集链路接线 `ModbusAlarmService.evaluate` | **已落地**（见第四、五节；一处边界见第五节末） |
| 6 | 定义变更对账 / 删除级联 | **已落地**（见第五节「定义变更对账与删除级联」） |
| 7 | 查询接口 `ModbusAlarmResource` | **已落地**（见第九节） |
| 8 | 前端：编辑器逐字段配置 + `/main/alarm` 列表页 | 设计已定，待落地 |

没落地的部分不是「以后再说」，是**设计已定、按顺序落**；写在这里是为了让判定口径与落库口径
分开评审 —— 前者零 IO、可独立验证，后者压在第七节那个冒烟结论上。

## 一、要解决的问题与口径

在此之前只知道**采到没采到**：`ModbusServiceFailure` 记帧错 / 设备无应答 / CRC 不过。
**采到了但值不对看不见** —— 温度已经 12℃ 了，曲线画出来了，没人盯着就不会有人发现。

| 项 | 决定 | 为什么 |
|---|---|---|
| 越限口径 | **一次越限一条**（边沿触发） | 持续越限不重复记；回正常后再越限才是新的一次。否则一个整夜越限的点位会写出上万条 |
| 比较方式 | `>` `>=` `<` `<=` `=` 五种 | 用**符号**不用 `GT/GTE` 枚举：语言中立、日志回显无歧义；「超过/达到」那些词是页面文案，进词典 |
| 防抖动 | **都不做**（不回差、不持续时长） | 明确的选择，代价见第十节 |
| 级别 | 三级 `INFO` / `WARN` / `CRITICAL` | 列表可按级别筛、汇总按级别分布 |
| 处理权限 | **空间成员** | 回执不是改配置，与 `invoke` 同口径 |
| 保留 | **永久保留**，不挂 TTL | 与故障记录的取舍一致：告警是给人看的历史 |

## 二、配置模型

```
ModbusServiceFieldAlarm  enabled / compare / threshold / state / level / text
```

挂在两处：`ModbusServiceField.alarm`（`response[].alarm`）与 `ModbusServiceFieldBit.alarm`
（`response[].bit-list[].alarm`）。

**为什么必须挂两处**：`ModbusResponseParser` 把每个具名位**单独**放进结果对象
（`values.put(bit.field, (data[offset/8] >> (offset%8)) & 1)`），**位是独立的结果键**。
只挂字段上的话「进水阀 = 1 告警」这类你明确要的能力根本够不着 —— 父字段那份是整个位掩码，不是一回事。

**`=` 为什么要 `threshold` 与 `state` 两个字段**：`=` 的比较目标有两种形态 ——
带 `value-list` 的字段出的是**描述字符串**（比 `state`），数值字段与位字段出的是**数**（比 `threshold`）。
备选方案是单个 `String` 在运行期解析，被否掉的理由：那是把协议语义压成字符串匹配
（`ModbusServiceFailure` 的注释已明确反对），而且它落在**每次采集的热路径**上，
`""→0`、`"1e3"`、前后空格全是坑。两个字段互斥，由校验器按 `compare` 与字段形态判定。

**Codec** 守全仓的「null 不出键」，`ModbusServiceFieldAlarmCodec.decode` 对空对象返回 `null`
（而不是一个字段全空的实例）—— 这样 `encode` 才能把「没配」与「配了个空」分开。

## 三、能比什么：由字段的取值形态决定

| 情形 | 运行时值 | 口径 |
|---|---|---|
| 普通数值（`scale` 已生效） | `Integer` / `Long` / `Float` / `Double` | 五种比较都可用，比 `threshold` |
| 命中 `value-list` | `String`（description） | **只有 `=` 合法**，比 `state` |
| `bit-list` 的位 | `Integer` 0/1 | **只有 `=` 合法**，比 0 或 1 |
| 位区父字段（整段掩码） | `Integer` | 数值比较（冷门但定义明确） |
| `format: string` | `String` | **不允许配任何告警** |

带 `value-list` 的字段配 `>` 会被**校验器直接拒**，而不是留给运行期碰运气：那种字段的值
有时是字符串（命中取值表）、有时是数（没命中），`>` 会**时灵时不灵** —— 静默失效比报错难查得多。

## 四、比较语义：比的是解析后的值

**比的是解析后、也就是历史里存下来的那个值**，不是原始寄存器值。理由：点表已经声明了
`scale` / `unit`，用户填的阈值是**工程单位**；曲线画的是解析值，将来在图上画阈值线才对得齐。
（`ModbusAlarmPolicyTest.comparesTheParsedValueNotTheRawRegister` 专门钉住这条：
寄存器 750、`scale` 0.1 ⇒ 解析值 75.0 ⇒ 没越限，比原始值的话 750 > 80 会误报。）

判定是**三态**，不是两态：

| 结果 | 含义 | 对状态的影响 |
|---|---|---|
| `HIT` | 越限 | 见第五节 |
| `MISS` | 没越限 | 见第五节 |
| `SKIP` | **判不了**：值缺失 / `NaN` / `Infinity` / 类型对不上 / 未知比较方式 | **什么都不做** |

**`SKIP` 必须区别于 `MISS`**：一次截断或畸形的应答不能把一条开着的告警**关掉** —— 那是凭空恢复。
同时记一条 WARN 日志（`alarm not comparable, service…function…field…value…`），不静默；
新存的定义被校验器挡住了，这条日志是给库里可能已有的老数据留的。

**这条日志每（服务, 方法, 出值）只说一次**（进程内去重，上限 256，记满清空重来）。
理由：`SKIP` 的常见形态之一是**定义与运行期的值长期对不上**，那是**持续状态**而不是事件 ——
5 秒一个周期就是一天一万七千行 WARN，刷到这个程度只会训练人忽略它，
而这条日志存在的全部意义就是**被看见**。说一次、说清楚，比每周期重复一遍有用；
清空重来是给「坏定义比上限还多」留的退化路径，那种情况下多报几轮是应该的。

去重只影响日志，**不影响判定**：`SKIP` 仍然不写库（见第五节 `Decision.isEmpty()` 有意忽略 skips），
所以「稳态零写」这条纪律不被日志需求破坏。

`=` 对带 `value-list` 字段有个不对称：值是**数**（原始值没命中取值表）时判 `MISS` 而不是 `SKIP`
—— 我们明确**知道**它的值，且知道它不等于 `state`。反过来，配置比数、值却是描述字符串 ⇒ `SKIP`
（定义对不上，老数据）。

## 五、状态与边沿：开着的行就是状态

不另存一份告警态。`(serviceId, functionIndex, field)` 上有**未恢复**的告警就是「开着」，
判定只需要这一张集合，它由告警表自己查出来。

| 值 | 有无未恢复告警 | 动作 |
|---|---|---|
| `HIT` | 无 | 插一条（`openKey` 有值） |
| `HIT` | 有 | **什么都不做**（稳态零写） |
| `MISS` | 有 | **关闭**：按 key `updateMany` 置 `recoveredAt` / `closeType=VALUE`、`$unset openKey` |
| `MISS` / `SKIP` | 无 | 什么都不做 |
| 采样里有、但新定义已不再配告警 | 有 | **关闭**：`closeType=DEFINITION` |

最后一行是**定义变更的自愈主路径**：字段被删、或告警被关掉时，那条未恢复的告警再也判定不到、
会永远挂着。`decide` 只看**新定义**，所以补了这条。注意它**只对「本次采样里确实存在的键」生效**
—— 采样里没有的键分不清是「定义删了它」还是「这一帧截断了」，宁可留着也不能凭空恢复。

**这条自愈路径有边界，是有意为之**：第一层闸门 `hasEnabledAlarm` 在**整个方法**上一个启用的告警
都没有时直接返回 —— 于是「某个方法上**最后一个**告警也被关掉」的那一刻，那条未恢复的告警
**在这里不会被关**。不为此松开闸门：那会让「绝大多数采集一个数据库操作都没有」这条纪律失效，
而这个残局的数量与「定义刚被改过」完全同阶（一次编辑最多留下几条），不该用**每次采样**的代价去兜。
它归**定义变更对账**（`ModbusAlarmReconciler`，见下）扫掉。
`ModbusAlarmServiceTest.switchingOffTheLastAlarmLeavesTheGateClosedOnPurpose` 钉住的是
**「闸门在前、对账在后」这个分工**，别让人以为是漏判。

**为什么不放进 `ModbusServiceShadow`** —— 是**正确性**理由，不是成本理由：影子是**全量替换**写的
（`ModbusHistoryService` 里 `shadowRepo.replace(next)`），而这一带**没有任何事务**。影子按
`(serviceId, functionIndex)` 一条，告警态是按字段的；让告警判定成为该文档的**第二个写者**，
一次并发就会把 `fields` / `recordedAt` / `lastError` 或告警态悄悄覆盖掉 —— 而「先明细后影子」
那条顺序不变量正是靠单写者成立的。另外 poller 在热路径上**故意跳过**影子读
（`ModbusPollBaselines` 用内存基准），告警无论如何都得自己读一次，扩影子在那里一分钱都省不到。

**也不放 poller 的内存基准**：那是单副本才成立的优化，`Modbus-History-TODO.md` 写明多副本会让基准漂移、
漏记变化。漏一条采样是省成本，**漏一次告警是事故**。

### 为什么这个特性不需要「先明细后影子」那套

两个边沿各自对库**幂等**：插入失败则没有开行，下一条仍越限的样本会重试插入；
关闭失败则行还开着，下一条仍正常的样本会重试关闭。故障记录当初要那套顺序不变量，
是因为它一条记录同时改了明细与影子两个地方。

**成本**：每个「配了告警的方法」每轮一次带索引的读（`{serviceId, functionIndex, openKey: {$exists: true}}`，
只投影 `field`），一个方法配 3 个字段也只读一次。**没配告警的服务零库操作** ——
第一层闸门 `hasEnabledAlarm` 是纯内存判断，与 `ModbusHistoryPolicy`「绝大多数采集一个数据库操作都没有」
同一条纪律。

### 定义变更对账与删除级联

上面那条自愈路径有两处够不着，都由这里兜：

| 残局 | 为什么自愈够不着 |
|---|---|
| 一个方法上**最后一个**告警也被关掉 | 第一层闸门 `hasEnabledAlarm` 直接返回，连「哪些开着」都不查 |
| 改了定义后这个方法**再也不采**（方法被删、`polling` 关掉） | 这个方法根本不会再产生采样，`decide` 没机会跑 |

判据是纯函数 `api/modbus/alarm/ModbusAlarmPlan.java`（`ModbusAlarmPlanTest` 15 例）：
「按新定义，这个 `(方法序号, 出值名)` 还配着启用的告警吗」，判不到的按 `DEFINITION` 关掉。
三样口径值得记：

* **按新定义重新算一遍覆盖集，而不是拿新旧定义做差分**。差分要存旧定义，而写入路径手上只有
  `merged`；重算只需要 `modbusService.functions` 一份输入，启动那趟也复用同一个函数。
* **不认识的服务一律不动**。写入路径只传一个服务、只取那个服务的开行，所以这条在写入路径上
  永远不会触发；它的价值是**拦住将来某个调用方传半份服务列表**时的误关（判宽了会把别的服务的
  告警一次关一片）。代价见下面「不做的事」。
* **一个方法一条语句**：关闭是按整个开行标识 `updateMany` 的，同一方法上的多个陈旧出值合成
  一条 `closeByKeys`，不是一条语句一个出值。

执行者是 `api/modbus/alarm/ModbusAlarmReconciler.java`，两个时机**同一个实现**：

| 时机 | 挂在哪 | 读什么 |
|---|---|---|
| 定义变更 | `ModbusServiceService.update` 尾部，与轮询任务、影子并排（`reconcileDerived`） | 该服务的开行（带索引） |
| 启动 | `ModbusAlarmReconciler.init(@Observes StartupEvent)` | 全部服务的定义 + 全部开行 |

* **不进周期对账**：与影子同一条理由 —— 陈旧只可能来自定义变更，而定义变更必经写入路径；
  剩下能绕过去的只有**直接改库**（运维脚本、迁移），启动那次兜住。
* **不挂在调度器的 `StartupEvent` 上**（这是实现时对 TODO 第四步措辞的一处有意偏离）：轮询任务的
  对账挂在 `ModbusPollScheduler` 的启动事件上，而那个事件只在 `modbus.poll.enabled=true` 时才起。
  告警判定挂在**采集链路**上、手动 invoke 也会评 —— 关掉轮询开关不该让残留从此没人扫。
* **只写库、不动内存**：所以没有 `ModbusPollReconciler` 那种「跨进程要等下一次周期」的延迟 ——
  在哪个副本上改的定义，关就在哪个副本上落库。
* 失败口径与轮询任务、影子同壳：写入路径那边只记日志、不让一次定义维护跟着失败；启动那次自己兜住。

**删除走的是另一条路**：服务或依赖设备被删除时，`ModbusHistoryService.purge` 把影子、历史、故障
**与告警**一起 `deleteByServiceIds` 真删，而不是按 `DEFINITION` 关掉。关掉的语义是「这条告警的原因
是定义不再覆盖它」，那是一条给人看的历史，得留着；而服务整个没了的时候，那条告警再也没人能处理
（页面按空间的子树查，查不到它）、也没人需要「当时为什么报」—— 它就是存储垃圾。
真正该保留的「这个点位历史上报过什么」在历史与故障记录里另有其份。

**对账不做的事（诚实记一笔）**：`ModbusAlarmPlan` 对不认识的服务一律不动，所以启动那趟
**不会**清理「服务已被删除、级联删除又崩在中途」留下的开行。不为此松开那条保守口径 ——
它的正面价值（拦住半份服务列表的误关）比这里兜住的局面严重得多。这与影子 / 历史 / 故障记录是
**同一个失败模式**：级联失败只记日志、不重试，留下的是存储垃圾，且页面看不见。
`ModbusAlarmReconcilerTest.startupSweepLeavesRowsOfAServiceThatNoLongerExistsAlone` 钉住这个分工。

## 六、校验

实现在 `ModbusServiceValidator.validateAlarm`（规则清单见 `MODBUS.md` 第七节）。几条口径：

* **只在 `enabled == true` 时校验**：半配置合法，与 `validatePolling` 同一条规矩
  （`false` 的用法就是「把配置留着、先不报」）。
* **只能配在读方法（fc 01/02/03/04）上** —— 写方法的应答是请求回显，没有「读到的值」。
  与 `interval` 共用同一个 `isReadFunction` 判断（那次抽取的副产物：两处各写一份功能码解析迟早漂移）。
* `OPERATORS` / `LEVELS` 两张白名单**只有一份**（`ModbusAlarmPolicy`），校验器与判定器共用。
  错误文案里逐字列出而非用 `Set.of` 拼串 —— 它的迭代顺序不保证，文案会随机。

## 七、并发：竞态必须修，不能像故障去重那样忍

poller（1 副本）与 API 副本（N 个）可能同时判定同一 `(service, function, field)`：
两边都读到「没有未恢复告警」→ 都插入 → **同一段越限两条开着的告警**。

这与故障去重的竞态**不是同一个后果等级**：那个是追加型日志上多一行信息；这里两条重复行
**不会作为一个单位一起恢复** —— 用户处理了 A，B 还挂着未处理，未处理计数永远不对，
页面上同一件事出现两次。

**解法**：插入时带 `openKey`，靠**唯一部分索引**（`{openKey: 1}` unique +
`partialFilterExpression: {openKey: {$exists: true}}`）挡住第二条；捕获重复键（11000）当作
「别的副本先开了」**吞掉**（吞掉之后不变式「该键有一条开着的告警」恰好成立，所以这不是妥协）。
**关闭用 `updateMany` 按整个 key**（不是定位到某条 `_id`）—— 这消除了「幽灵告警永远关不掉」
这个失效模式，也让热路径只需要「存在性」而不需要身份。

> **第 0 步（待做，门控这一节）**：部署是 Postgres 底的 Azure DocumentDB，**部分索引支持未实证**。
> 仓库已有先例（本文件的姊妹篇 `Modbus-History.md` 第六节：TTL「既没列为不支持、也没写明支持，
> 所以结论是只能实测」，然后真的冒烟并记账）。冒烟步骤：在 staging 临时集合建该索引 →
> 插两条同 `openKey` 的应第二条报 11000 → **插两条都没有 `openKey` 的应都成功**
> （这一步才证明「部分」；普通唯一索引会把第二条 null 当重复拒掉）→ drop → 结果记进本文件。
>
> **在这个结论出来之前，`ensureIndexes()` 里刻意没有建这个索引**（只建第八节那 2、3 两号）。
> 两条理由，方向相反但都指向「先别建」：
> * **建了但被静默吞掉**：`ensureIndexes()` 的失败处理是 `recoverWithNull`（不阻断启动，
>   与其它仓储同口径）。服务端若不支持部分索引而抛错，那一行会被吞掉 —— 于是「以为有护栏、
>   其实没有」，**比明说没有更糟**（读到 `listIndexes` 也证明不了语义，只能靠行为冒烟）。
> * **建错了更危险**：万一服务端忽略 `partialFilterExpression` 当成普通唯一索引，
>   所有**已恢复**的行 `openKey` 都是缺失（等同 null），同一出值的第二条已恢复告警会被拒 ——
>   **告警开始丢**。这是把「多一条重复」换成「少一条真告警」，两害相权，后者重得多。
>
> 目前没有这个索引也**不产生脏数据**：告警还没有任何写入者（评估尚未接进采集链路，第四步才接）。
> 第二步落地的只是「文档 + 仓储 + Codec」，`insertOpen` 在重复键上是**吞掉并返回 `false`**
> 而不是失败 —— 那一支的判定（`isDuplicateKey`）已有单测钉住，等索引到位即生效。
>
> **回退档**（冒烟失败时用，**不静默降级**）：
> * **F1**：普通唯一复合索引 `{serviceId, functionIndex, field, recoveredAt}` —— 不用任何冷门特性。
>   开着的行 `recoveredAt` 都是 null，唯一索引把 null 当一个值，所以第二条开行会撞；
>   关闭的行时间戳各异可以共存。唯一失效模式是「同一毫秒的两次关闭」，而那会让**关闭**失败 —— 自愈。
> * **F2**：不建唯一索引，改用 `updateOne({openKey}, {$setOnInsert: …}, upsert: true)` 读
>   `getUpsertedId()` 判断是否新建。只缩小窗口、**不关闭**竞态（MongoDB 明确说 upsert 无唯一索引时
>   仍可能重复），只能与 `updateMany` 兜底一起用，并且必须把这条竞态**写进文档**。

**另两个小竞态都无害**：两个评估者同时关闭同一 key —— `updateMany` + `$unset` 幂等，第二个匹配 0 条；
用户处理与告警关闭同时发生 —— 两者是互不相交字段的 `$set`，用 `updateOne`（**绝不 `replaceOne`**），
过滤条件带 `{handled: {$ne: true}}`，`matchedCount == 0` 时再 `findById` 一次以区分「已处理」与「不存在」，
好给出精确文案。

## 八、数据模型

`db/modbus/alarm/ModbusServiceAlarm.java`，`@MongoEntity(database="modbus", collection="service-alarms")`：

```java
public String  serviceId;
public Integer functionIndex;
public String  field;        // 出值名（字段名，或 bit-list 的位名）
// —— 触发那一刻的定义快照 ——
public String  compare;      public Double threshold;   public String state;
public String  level;        public String text;        public String unit;
// —— 边沿 ——
public Date    at;           // 越限首次出现的时刻
public Date    recoveredAt;  // null = 仍在越限（这就是状态本身）
public String  closeType;    // VALUE | DEFINITION
// —— 处理 ——
public Boolean handled;      public Person handledBy;
// —— 触发时的样本 ——
public Object  sample;       // 那一刻的值（Number 或 description），开着期间不刷新
// —— 内部，不下发 ——
public String  openKey;      // 未恢复时才有：serviceId#functionIndex#field
```

**为什么配置要快照进行**：定义是活的，行是不可变的历史。只存引用的话，把阈值从 80 改成 60
会**悄悄改写每一条历史告警的含义** —— 「当时为什么报」这件事被一次无关的修改编辑掉了。
与 `ModbusServiceFailure` 存 message 文本而不是错误码同一个道理；也让告警行在服务被删掉之后
仍然自解释。`closeType` 区分「值回来了」与「定义不再覆盖这个键」—— 否则 UI 无法解释一条
没有恢复样本的关闭，与 `ModbusFailureType` 那句「宁可说不知道，也不猜一个会把人带偏的分类」同一精神。

**索引**（`ensureIndexes()`：幂等、失败不阻断启动、沿用 `modbus.index.enabled`）：

1. `{openKey: 1}` unique + `partialFilterExpression` —— **并发护栏，见第七节的取舍：代码里还没建**
   （文档里仍列为第 1 号，冒烟一过就补一行）
2. `{serviceId: 1, functionIndex: 1, openKey: 1}` —— 采集侧查未恢复（**已建**）
3. `{serviceId: 1, functionIndex: 1, at: -1}` —— 列表查询（**已建**）

**仓储**（`ModbusServiceAlarmRepository`）里没有一条「整文档替换」：行落下之后只有两种改动 ——
关闭（`recoveredAt` / `closeType`、`$unset openKey`）与处理（`handled` / `handledBy`），
是**互不相交的字段**，各写各的 `$set`，谁也盖不掉谁。三条要点：

* **关闭按整个开行标识 `updateMany`，不定位到某条 `_id`** —— 这消除了「幽灵告警永远关不掉」，
  也让采集热路径只需要「存在性」而不需要身份。过滤条件里的 `exists("openKey")` 不是多余的：
  已恢复的行 `openKey` 已被 `$unset`，不该被再关一次而覆盖掉它第一次的 `recoveredAt`。
* **`handle` 用 `updateOne`，绝不 `replaceOne`**（理由同上：替换会抹掉另一方刚写的字段），
  过滤条件带 `{handled: {$ne: true}}` 让重复点击只生效一次。处理人写成显式 `Document`
  而不是塞 `Person` —— 更新语句里没有实体字段的声明类型可用，走 POJO 编码是多余的猜测
  （与 `SpaceRepository.pushAccess` 同口径）；处理时刻取 `Person.timestamp`，不另开字段。
* **`isDuplicateKey` 认三种异常形状**（`MongoWriteException` / `MongoCommandException` /
  `WriteConcernException`），都以**错误码 11000** 判定而不按类型名猜。第三支容易漏：
  驱动那个「这就是重复键」的 `com.mongodb.DuplicateKeyException` 继承的是
  `WriteConcernException`，**不是** `MongoWriteException` 的子类。判漏的后果是静默的（两条开着的告警），
  所以它单独有单测（含 cause 链包装与自引用终止）。

**级联删除**（**已落地**）：`ModbusHistoryService.purge(...)` 里的第四个 `Uni` ——
`alarmRepo.deleteByServiceIds(serviceIds)`，与影子 / 历史 / 故障并列。为什么这里走**真删**而不是
像定义变更那样按 `DEFINITION` 关掉，见第五节「定义变更对账与删除级联」末。

## 九、接口

`api/modbus/alarm/ModbusAlarmResource`，`@Path("/matrix/v1/modbus/alarm")`：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/many/{spaceId}` | 空间成员 | `serviceId`（不传 = **整个空间含子空间**）、`functionIndex`、`field`、`level`、`open`、`handled`、`from`/`to`、`limit` |
| POST | `/handle/{spaceId}/{id}` | **空间成员** | 无 body，处理人取 `currentUser()`；返回更新后的那条 |

路径另起一段（不挂在 `/modbus/history` 或 `/modbus/service` 之下）：告警是**第三件事** ——
既不是用户维护的配置，也不是设备跑出来的时间序列，而是判定出来的事件，且它有自己的权限粒度
（成员可处理、不需要管理员）与保留口径（永久保留，不挂 TTL）。与历史接口当初另起一段同理。

* `DEFAULT_ALARM_LIMIT = 200` / `MAX_ALARM_LIMIT = 1000` —— **自己的常量**，不复用故障那两个
  （跨用途复用比两行重复更糟）。
* `items` 按 `at` 倒序，**每条带自己的 `id`** —— 这是相对 `ModbusServiceFailureCodec`（不发 `_id`）
  的**有意偏离**：告警行有逐行动作。
* `summary` = `{total, open, unhandled, byLevel, byText}`；`byText` 是「告警类型分布」。
* Codec **只出不进**（告警由采集侧写、不由请求体写），`openKey` **绝不下发**，时刻一律 epoch 毫秒。
* **不要往 `/history/current` 上加告警**：那个接口项目页已经在 N 次扇出调用它，加一次逐 function
  的告警查询会把两个页面依赖的接口成本翻倍；告警页要的「这个项目现在哪儿在告警」正是
  「不传 `serviceId`」那一个请求。
* `text` 是**用户数据**，前后端一律原样显示、**永不翻译**（前端 `AGENTS.md` 的 i18n 一节）。

### 查询服务

`api/modbus/alarm/ModbusAlarmQueryService` 与 `ModbusAlarmService` **分开**：那边管写
（采集侧评估、用户点处理），判定口径与副作用纪律是它的核心约束，混进查询代码会被淹掉；
这边只管读，调用方只有 Resource。

* **单服务那条路只是批量那条路的一个元素**（`alarms(...)` → `alarmsOfServices(List.of(id), …)`）：
  排序与截断的口径只有一份，日后不会改漏一边。
* **参数校验放在服务里而不是 Resource**（区间必填、`from < to`、`level` 走 `ModbusAlarmPolicy.LEVELS`
  白名单）：Resource 只做鉴权与编码，而校验是能被单测覆盖的部分。
  拼错的级别**报错而不是返回空列表** —— 它与「这段时间没这个级别的告警」在界面上长得一样。
* 空间级是**逐服务各查一次再内存归并**（理由与故障清单同：告警集合的索引以 `serviceId` 打头，
  跨服务查等于丢掉索引前缀做全表扫，而一个空间下的服务只有十几条）；每服务取 `limit + 1` 条，
  所以「先按服务截一刀再合并」得到的仍是真正的全局前 `limit` 条，`truncated` 也据此判。

### 处理（回执）

* **`handle` 写之前先判归属**：路径上的 `spaceId` 只证明「你是这个空间的成员」，不证明
  「这条告警属于这个空间」。判法是「先按 id 读出那行 → 它的 `serviceId` 在
  `ModbusServiceService.subtreeServiceIds(spaceId)` 里吗 → 才写」。用的是与列表查询**同一个**函数，
  于是「列表里看得到的就能点」恰好成立。
* **非法 id 必须在进库之前先挡**（`ObjectId.isValid(id)`）：Panache 的 `findById(String)` 在构造
  `ObjectId` 时就抛，而那是个普通 `RuntimeException`，落不到 Resource 那个
  `.onFailure(IllegalArgumentException.class)` 的兜底里。
* **「已经处理过了」算成功，不算错**（见 `ModbusAlarmService.handle`）：用户要的结果已经成立，
  报错只会为一件恰好如愿的事弹一条红字。CAS 改动为 0 时**再读一次**，行在就照常返回它
  （并记一条日志说明重复点击）、行不在了才 not found —— 那一次读就是为了分辨这两种解释。
  这与 `SpaceService` 里「删除 0 条按 not found 失败」不同：那里 0 条只有一种解释。
* `handledBy` 记的是**第一个**处理的人（仓储那条 `updateOne` 的过滤条件带 `{handled: {$ne: true}}`），
  后点的不会把前一个顶掉。
* **不整页刷新**：返回更新后的那条，前端就地替换该行。

### 空间 → 服务清单

`subtreeServiceIds` 从 `ModbusHistoryResource` **下沉**到 `ModbusServiceService`（它本就是
「空间 → 服务」），故障清单与告警清单共用。选下沉而不是复制那 15 行：两处的口径必须一致，
复制之后「一处改了另一处忘了」不会报错，只会让两个页面的可见范围悄悄不同
（`SpaceService` 只依赖仓储与账号查询，注入进 `ModbusServiceService` 不成环）。

## 十、不做防抖的代价（触发线）

「**不做防抖**」+「**永久保留**」两条叠加：一个在阈值附近来回抖的模拟量，按 5 秒周期
**每天可以产生上千条永不删除的告警**（每次穿越一条）。故障记录没这个问题，是因为它按 message 去重、
且它的 `at` 是「仍在持续的故障」的首次时刻。

先就这么做（这是明确的选择，`ModbusAlarmPolicyTest.noDebounceSoEveryCrossingIsItsOwnAlarm`
把它钉住，将来加回差必须先改掉那条测试）。但留一条**触发线**，照仓库「换存储的触发线」那个写法：

> **实测若某个服务日均告警 > 100 条，回来加回差**（`compare` 之外补一个 `deadband`，
> 判定改为 `HIT` 需要越过阈值、回到正常需要退回 `deadband` 之外）。

这样将来要不要回头加防抖，是个**有据可依的决定**，而不是凭感觉。

## 十一、测试

**纯函数单测**（`@QuarkusTest` 要真连库，故逻辑都抽成无库静态函数）：

* `ModbusAlarmPolicyTest`（16 例）：五种比较与边界；`scale` 生效后比缩放值；
  `value-list` 命中走 `=`（命中 / 不命中 / 值是数）；位 0/1；**四种边沿格**；
  **不做防抖**（阈值附近来回穿越产生 开/关/开/关）；重启后遇到已存在的开告警不产生第二条；
  键缺失 / `NaN` / 值为 null ⇒ `SKIP` **且不关闭**已开的告警；字段已不在定义里 ⇒ `DEFINITION` 关闭；
  一次采样里两个字段一开一关；`hasEnabledAlarm` 闸门（含位上的告警也算）；
  `Open` 带着单位快照、位没有单位。
* `ModbusAlarmServiceTest`（19 例，Mockito 不连库）：没配告警 ⇒ `verifyNoInteractions(alarmRepo)`；
  稳态**零写**（读一次、一个写操作都没有）；开出来的行带着定义快照（含 `unit`、位不带）；
  按值关 / 按定义关各自的 `closeType`；一次采样里既开又关；判不了**只警告一次**（含跨帧的
  截断样本）且不写库；警告里带上当时的原始值与完整定义；去重上限会清空重来；
  读失败与写失败都**照常外传**（记日志、咽下去是调用方的决定）；
  **关掉最后一个告警时闸门照常跳过**（钉住「闸门在前、对账在后」的分工）。
  后 6 例是 `handle`：写回执后返回**读回来的那一条**（不是拿入参拼的，页面要就地替换）；
  CAS 改动 0 且行还在 ⇒ **成功**并记一条日志；CAS 改动 0 且行不在 ⇒ not found；
  两个入参缺失各自报错且**不碰库**；写失败与**读失败都照常外传**
  （后者尤其不能咽 —— 咽下去就得凭空拼一条返回给页面）。
* `ModbusAlarmQueryServiceTest`（7 例，纯函数不连库，对齐 `ModbusHistoryQueryServiceTest`）：
  跨服务按 `at` 倒序（不是「服务 1 的全部在前」）；多于 `limit` 时截掉更早的并置 `truncated`；
  **正好等于 `limit` 不算截断**；同一时刻保持传入顺序（`sort` 稳定 ⇒ 两次查询之间不跳）；
  空清单给空结果而不是报错；`at` 缺失按 0 算落最后；**合并不按 `open` / `handled` 分堆**
  （那是筛选参数的职责，不是排序的 —— 列表要的是时间线）。
  只测 `merge` 这一个静态函数：它是那个「逐服务截一刀再合并仍等于全局前 `limit` 条」论证的落点。
* `ModbusServiceServiceTest` 补 2 例：`evaluate` 在 `record` **之后**且拿到**同一份**采样（`InOrder`）；
  告警评估失败**不改写**一次已成功采集的响应，也不变成一条故障记录。
* `ModbusServiceValidatorTest` 补 9 例：逐条拒绝（未知 `compare`、未知 `level`、空 `text`、
  超长 `text`、`string` 字段配告警、`value-list` 字段配 `>`、`=` 缺 `state`、`state` 不在描述里、
  两个都给、都不给、位上配 `>` 或 `0.5`、写方法上配告警）+ `enabled != true` 时全部放行。
* `ModbusServiceCodecTest`：示例体收进 `alarm`（encode∘decode 稳定性）+ 「没配就不出键」+
  空对象读作「没配」。
* `ModbusAlarmCodecTest`（9 例）：全字段行；**`openKey` 绝不出现**（行内带着它、出去没有它）；
  `null 不出键`；`=` 的取值表状态（有 `state` 无 `threshold`）；字符串样本原样出去；
  汇总的计数（`open` 按 `recoveredAt` 数）与两个 tally 的排序；`level`/`text` 缺失归 `UNKNOWN`
  桶而不替用户编文本；空输入全零；`openKeyOf` 的拼法稳定（改了它，库里已有的开行就再也关不掉）。
* `ModbusServiceAlarmRepositoryTest`（11 例，纯静态不连库）：三种异常形状都认、非 11000 的
  写错误照常外传、cause 链包装能穿透、自引用 cause 会立刻停下、超长链有界。
  **它值得单独测**：整套并发推理压在「重复键被认出来、然后吞掉」上，判漏就是静默地产生
  两条开着的告警 —— 正是这个特性要避免的失效模式。
  后 3 例钉住**写语句的形状**（这些错了不会报错、只会悄悄不对）：关闭就是 `$unset openKey`；
  关闭按整个开行标识 `$in` 一批出值、**不是**定位某条 `_id`；处理是 `{handled: {$ne: true}}` 上的
  CAS + 只 `$set` 两个字段（整文档替换会抹掉并行的关闭刚写的 `recoveredAt`）。
* `ModbusAlarmPlanTest`（15 例，纯函数不连库）：还配着 ⇒ 不动（字段 / 位各自）；告警被关掉 ⇒ 关、
  告警配置整个没了 ⇒ 关、字段被删 ⇒ 关、方法被整个删 ⇒ 关、**字段改名**（旧名那条）⇒ 关、
  写方法上的开行 ⇒ 关、`functionIndex` 为 null 的行 ⇒ 关；**不认识的服务一条都不动**
  （含服务列表为空）；坐标不全的行跳过；空 / null 输入 ⇒ 空；**一个方法一条 `Close` 且字段名有序**；
  排序确定（服务 → 方法序号，`null` 在前）。
  **它值得单独测**：这是**唯一**会去关告警行的地方，判宽了会把仍配着告警的开行误关
  （用户看到一条越限告警凭空「已恢复」），判窄了那条告警永远挂着 —— 两种都不报错。
* `ModbusAlarmReconcilerTest`（14 例，Mockito 不连库）：稳态零写；陈旧行按 `DEFINITION` 关且带上
  服务 / 方法序号 / 关闭时刻；同一方法多个出值合成**一条**语句；写入路径只查本服务的开行
  （`never()` 全表扫）；服务没有 `_id` ⇒ `verifyNoInteractions(alarmRepo)`；读失败与写失败都照常外传；
  启动那趟能关掉「服务还在、定义被直接改库改了」的行、稳态零写、**失败不抛**（`recoverWithNull`，
  且日志带上了底层原因）、关不掉也不抛；**服务已删除留下的行不动**（钉住「对账在后、级联在前」的分工）。

## 十二、配置项

沿用既有两个开关，不新增：

| 配置项 | 缺省 | 作用 |
|---|---|---|
| `modbus.index.enabled` | `true` | 启动时建索引（含告警那三个）；幂等、失败不阻断启动 |
| `modbus.poll.enabled` | — | 自动轮询总开关；告警判定挂在采集链路上，跟着它走 |

## 十三、变更记录

### 2026-09-14

* **第七步落地：查询服务与接口**（`api/modbus/alarm/ModbusAlarmQueryService` +
  `ModbusAlarmResource`；`ModbusAlarmService.handle`；`subtreeServiceIds` 下沉）。
  几处值得记下的口径：
  * **单服务那条路只是批量那条路的一个元素**：`alarms(...)` 直接转调
    `alarmsOfServices(List.of(serviceId), …)` —— 排序与截断的口径只有一份，日后不会改漏一边。
  * **参数校验放服务层不放 Resource**：那边只做鉴权与编码，而校验是能被单测覆盖的部分；
    拼错的 `level` **报错而不是返回空列表**（它与「这段时间没这个级别的告警」在界面上长得一样）。
  * **`handle` 的「已经处理过了」算成功**：CAS 改动 0 时再读一次，行在就照常返回并记一条日志、
    行不在了才 not found。那一次读就是为了分辨两种解释 —— 报错会为一件恰好如愿的事弹红字。
  * **`/handle` 的非法 id 在进库之前先挡**（`ObjectId.isValid`）：Panache 在构造 `ObjectId` 时就抛
    普通 `RuntimeException`，落不到 `IllegalArgumentException` 的兜底里。
  * **归属判据与列表查询共用 `subtreeServiceIds`**：于是「列表里看得到的就能点」恰好成立，
    而不是两处各写一份、日后慢慢分叉。
  * **`subtreeServiceIds` 选了下沉而不是复制**：两处口径必须一致，复制之后分叉不会报错，
    只会让两个页面的可见范围悄悄不同。
  * 单测：`ModbusAlarmQueryServiceTest`（7）+ `ModbusAlarmServiceTest` 补 6 例（`handle`）。
    全量 511 例中 510 绿；唯一红色的仍是 `SpaceResourceTest`（`@QuarkusTest`、要连 Mongo，本机读超时）。
* **第六步落地：定义变更对账与删除级联**（`api/modbus/alarm/ModbusAlarmPlan` +
  `ModbusAlarmReconciler`、`ModbusServiceAlarmRepository` 两个只投影坐标的查询、
  `ModbusServiceService` 的写入路径接线、`ModbusHistoryService.purge` 的第四个 `Uni`）。
  几处值得记下的口径：
  * **新建了一个 `ModbusAlarmReconciler`，没有把拾遗挂进 `ModbusPollReconciler`**
    （这是对 `Modbus-Alarm-TODO.md` 第四步措辞的**有意偏离**）：轮询任务的对账挂在
    `ModbusPollScheduler` 的启动事件上，而那个事件只在 `modbus.poll.enabled=true` 时才起；
    告警判定挂在采集链路上、手动 invoke 也会评 —— 关掉轮询开关不该让残留从此没人扫。
  * **判据按新定义重算覆盖集，不做新旧差分**：差分要存旧定义，而写入路径手上只有 `merged`；
    重算只需要 `modbusService.functions` 一份输入，启动那趟复用同一个函数。
  * **`plan` 对不认识的服务一律不动**：写入路径只传一个服务、只取那个服务的开行，这条在写入路径上
    永远不触发；它的价值是拦住将来某个调用方传半份服务列表时的误关。**代价是把「服务已删除、
    级联又崩在中途」留下的行排除在对账之外** —— 明说，不掩饰（第五节末）。
  * **写入路径的三样派生数据对账统一成 `reconcileDerived(String what, Uni<Void>)`**：
    轮询任务、影子、告警同壳（各自只记日志、不让一次定义维护跟着失败），日志里带上是哪一样。
  * **告警对账只写库、不动内存**，所以没有轮询任务那种「跨进程要等下一次周期」的延迟。
  * 单测：`ModbusAlarmPlanTest`（15）+ `ModbusAlarmReconcilerTest`（14）。全量 498 例中 497 绿；
    唯一红色的仍是 `SpaceResourceTest`（`@QuarkusTest`、要连 Mongo，本机读超时）。
* **第五步落地：评估接进采集链路**（`api/modbus/alarm/ModbusAlarmService` +
  `ModbusServiceService.execute` 里的那一处 `chain`）。几处值得记下的口径：
  * **两件并列的副作用，不是串在成功路径上**：`recordHistory(...).chain(ignored -> evaluateAlarms(...))`
    串起来只是为了让**顺序确定**（历史是「采到什么」、告警是「值对不对」，现场排查先看前者），
    两者互不依赖、也各有各的失败重试。所以这个特性比故障记录简单的地方在于**不需要**
    「先明细后影子」那套顺序不变量（第五节）。
  * **`evaluateAlarms` 与 `recordHistory` 同壳**：失败只记日志 + `recoverWithNull`，
    **既不改写 invoke 的成功响应、也不改写它的失败响应** —— 值已经拿到了，告警只是副作用。
  * **一次评估里的所有动作共用同一个 `Date`**：否则会写出「关了又开、开的时间还早于关的时间」
    这种读起来荒谬的历史。
  * **判不了的日志进程内去重**（上限 256，见第四节）：这是对「不静默」与「别刷屏」两个要求的
    折中，代价是**多副本下会各报一次** —— 与故障去重那条诚实的竞态记录同一个性质。
  * **第一层闸门的边界**（第五节末）：方法上最后一个告警被关掉后，残留的开行归第六步的对账
    （`ModbusAlarmReconciler`）扫，
    **不**为它松开闸门。
  * 单测：`ModbusAlarmServiceTest`（13）+ `ModbusAlarmPolicyTest` 扩到 16 +
    `ModbusServiceServiceTest` 补 2。全量 469 例中 468 绿；唯一红色的仍是 `SpaceResourceTest`
    （`@QuarkusTest`、要连 Mongo，本机读超时）。
* **第二步落地：告警文档、仓储与 Codec**（`db/modbus/alarm/ModbusServiceAlarm`、
  `ModbusServiceAlarmRepository`、`codec/ModbusAlarmCodec`）。几处值得记下的口径：
  * **唯一部分索引刻意没建**（只建了采集侧与列表两个）—— 两条理由见第七节：
    建了被 `recoverWithNull` 静默吞掉是「以为有护栏、其实没有」；建错（服务端忽略
    `partialFilterExpression`）会让已恢复的行撞唯一索引、**告警开始丢**。目前没有它也不产生脏数据，
    因为告警还没有写入者（第四步才接线）。冒烟台账进第七节。
  * **`open` 按 `recoveredAt` 数，不按 `openKey`**（汇总处）：两者本该等价，但汇总不能和它汇总的
    那批行自相矛盾 —— `recoveredAt` 才是前端能看到、据以渲染状态的那个字段。
  * **`encode` 有意偏离故障 Codec：每条都发 `id`**。故障记录是纯日志、只能看；告警行有**逐行动作**
    （点「处理」），前端得知道点的是哪一条。
  * **`isDuplicateKey` 认三种异常形状**，其中 `com.mongodb.DuplicateKeyException` 继承的是
    `WriteConcernException` 而非 `MongoWriteException`（容易想当然漏掉）。
  * 单测：`ModbusAlarmCodecTest`（9）+ `ModbusServiceAlarmRepositoryTest`（8，纯静态）。
    全量 449 例中 448 绿；唯一红色的仍是 `SpaceResourceTest`（`@QuarkusTest`、要连 Mongo，本机读超时）。
* **第一步落地：配置模型与校验**（`ModbusServiceFieldAlarm` + 两处 `alarm` 字段 +
  `ModbusServiceFieldAlarmCodec` + `ModbusAlarmPolicy` + `ModbusServiceValidator.validateAlarm`）。
  零 IO、可独立验证；没配告警的服务在采集热路径上一分钱都不多花。同时写下判定口径与落库口径
  （本文件第二至六节、第八至十节），后者待第七节那个冒烟结论门控。
* 从 `validateInterval` 抽出共用的 `isReadFunction`：周期与告警都只允许配在读方法上，
  两处各写一份功能码解析迟早漂移。
