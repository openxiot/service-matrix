# Modbus 阈值告警

读方法的出值可以配阈值：越限产生一条告警，前端列出来、点「处理」回执。
与 `Modbus-History.md`（采集历史）是两件事：那边记的是**采到什么**，这边记的是**值不对**。

## 落地状态

| 步骤 | 内容 | 状态 |
|---|---|---|
| 1 | 配置模型（`ModbusPollingResponseFieldAlarm` + 两处 `alarms` + Codec） | **已落地**（见第二、三节） |
| 2 | 判定口径 `ModbusAlarmPolicy`（纯函数） | **已落地**（见第四、五节） |
| 3 | 定义校验 `ModbusServiceValidator.validateAlarm` | **已落地**（见第六节） |
| 0 | Azure DocumentDB 上验「唯一部分索引」 | **已落地：成立**（2026-09-14 staging 冒烟，见第七节） |
| 4 | 告警文档 `ModbusServiceAlarm` + 仓储 + Codec | **已落地**（见第八节） |
| 5 | 采集链路接线 `ModbusAlarmService.evaluate` | **已落地**（见第四、五节；一处边界见第五节末） |
| 6 | 定义变更对账 / 删除级联 | **已落地**（见第五节「定义变更对账与删除级联」） |
| 7 | 查询接口 `ModbusAlarmResource` | **已落地**（见第九节） |
| 8 | 前端：编辑器逐字段配置 + `/main/alarm` 列表页 | **已落地**（见第十三节变更记录；代码在 `webapp-matrix`） |
| 9 | 模型返工：一个出值**一组**规则，同时只留最严重的一条 | **已落地**（见第十三节 2026-09-14 第一条；本节各表已按新模型改写） |
| 10 | 第四个边沿：`handled == true` 也关旧行、开新行（`CLOSE_HANDLED`） | **已落地**（见第五、七、八节；第十三节 2026-09-16） |

没落地的部分不是「以后再说」，是**设计已定、按顺序落**；写在这里是为了让判定口径与落库口径
分开评审 —— 前者零 IO、可独立验证，后者压在第七节那个冒烟结论上（**已出结论：成立**）。

**仍待做的只有一件**：端到端验收（配一组分级规则、验「升一级后仍只有一条开着」这条核心断言
不被唯一部分索引 + 重复键吞掉一起破掉；再加第 7.5 步那组「处理过之后再越线要重报」）——
见 `Modbus-Alarm-TODO.md` 第七、7.5 步。
**2026-09-16 补记：那个零成本窗口已经关上了。** 上面这段（2026-09-14）当时成立 ——
`modbus/service-alarms` 一行都没落过、采集侧没部署过、没有服务配过 `alarm` 键。
但现在 staging 上已有真实数据：24 条告警行（20 条 `VALUE` 关闭、3 条 `SUPERSEDED` 关闭、
1 条仍开着且 `handled=true`）、2 个 Modbus 服务、620 条采集历史。
**从此以后，任何改动都要按「有存量数据」来设计** —— 这也是第四个边沿（第 10 行）只加关行判据、
绝不改写既有行的直接原因。

## 一、要解决的问题与口径

在此之前只知道**采到没采到**：`ModbusServiceFailure` 记帧错 / 设备无应答 / CRC 不过。
**采到了但值不对看不见** —— 温度已经 12℃ 了，曲线画出来了，没人盯着就不会有人发现。

| 项 | 决定 | 为什么 |
|---|---|---|
| 越限口径 | **一次越限一条**（边沿触发） | 持续越限不重复记；回正常后再越限才是新的一次。否则一个整夜越限的点位会写出上万条 |
| 规则条数 | 一个出值**一组**规则，同时最多**只有一条**开着 | 分级的真实需求：温度「低于 20 告警 / 超过 26 提示 / 超过 28 警告 / 超过 30 严重」是一条规则表达不了的 |
| 重叠规则同时命中 | 只留**级别最高**的一条，其余按 `SUPERSEDED` 关掉 | 一个出值同时两条开着，用户处理了 A、B 还挂着，未处理计数永远不对（第四节末） |
| 被取代的行 | **仍然算「未处理」** | 只写 `recoveredAt` / `closeType`，**不碰 `handled`**：它确实报过、确实没人处理过 |
| 比较方式 | `>` `>=` `<` `<=` `=` 五种 | 用**符号**不用 `GT/GTE` 枚举：语言中立、日志回显无歧义；「超过/达到」那些词是页面文案，进词典 |
| 防抖动 | **都不做**（不回差、不持续时长） | 明确的选择，代价见第十节 |
| 级别 | 三级 `INFO` / `WARN` / `CRITICAL` | 列表可按级别筛、汇总按级别分布 |
| 处理权限 | **空间成员** | 回执不是改配置，与 `invoke` 同口径 |
| 保留 | **永久保留**，不挂 TTL | 与故障记录的取舍一致：告警是给人看的历史 |

## 二、配置模型

```
ModbusPollingResponseFieldAlarm  id / enabled / compare / threshold / state / level / text
ModbusPollingResponseField.alarms      : List<ModbusPollingResponseFieldAlarm>
ModbusPollingResponseFieldBit.alarms   : List<ModbusPollingResponseFieldAlarm>
```

挂在两处：`ModbusPollingResponseField.alarms`（`response.fields[].alarms`）与 `ModbusPollingResponseFieldBit.alarms`
（`response.fields[].bit-list[].alarms`），各是一组、**各自算**（含条数上限与 `id` 唯一性）。

**每条规则带一个 `id`**（前端生成、随配置落库、后端只校验）。理由：`openKey` 是**出值级**的
（第六节末：一个出值同时最多一条开着，唯一部分索引恰好就是这条不变式），所以「这条开着的行是
哪条规则开的」只能靠行上的 `ruleId` 与当前胜者的 `id` 比对。**绝不能用数组下标当身份**：
删掉第 0 条会让第 1 条正开着的告警**静默改嫁**到新第 0 条，关闭原因还会记成 `VALUE`（谎话）。
仓库里已有现成教训 —— 编辑器 poll key 特意加了 `configId` 前缀，就是「别拿位置当身份」。
`id` 还带来一个好处：**改阈值不会把开着的告警关掉重开**（id 不变 ⇒ 胜者不变 ⇒ 稳态零写），
与该行「定义快照是不可变历史」的既有口径一致。

**`id` 不下发**（`ruleId` 只留在库里的行上，是后端内部标识）。告警行已经带完整的定义快照
（`compare` / `threshold` / `state` / `level` / `text`），「触发条件」那一列据此就能说全；
任何时刻一个出值只有一条开着，也没有「两条行要区分谁是谁」的场景。先例就在旁边：
`openKey` 也是内部标识、也**绝不下发**（不把主键拼法泄漏成接口契约）。代价明说：日后想从一条
告警行反查「它是哪条规则开的」，只能拿快照去比对定义；真需要时再加这个键是**向后兼容**的。

**为什么必须挂两处**：`ModbusResponseParser` 把每个具名位**单独**放进结果对象
（`values.put(bit.field, (data[offset/8] >> (offset%8)) & 1)`），**位是独立的结果键**。
只挂字段上的话「进水阀 = 1 告警」这类你明确要的能力根本够不着 —— 父字段那份是整个位掩码，不是一回事。

**`=` 为什么要 `threshold` 与 `state` 两个字段**：`=` 的比较目标有两种形态 ——
带 `value-list` 的字段出的是**描述字符串**（比 `state`），数值字段与位字段出的是**数**（比 `threshold`）。
备选方案是单个 `String` 在运行期解析，被否掉的理由：那是把协议语义压成字符串匹配
（`ModbusServiceFailure` 的注释已明确反对），而且它落在**每次采集的热路径**上，
`""→0`、`"1e3"`、前后空格全是坑。两个字段互斥，由校验器按 `compare` 与字段形态判定。

**Codec** 守全仓的「null 不出键」，`ModbusPollingResponseFieldAlarmCodec.decode(JsonObject)` 对空对象返回 `null`
（而不是一个字段全空的实例），`decode(JsonArray)` 对**空数组**与「元素全是空对象」的数组也返回 `null`
且丢掉空壳元素 —— 这样 `encode` 才能把「没配」与「配了个空」分开，库里也不会留下一串空壳规则
或一个 `alarms: []`。

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

**这条日志每（服务, 方法, 出值, 规则）只说一次**（进程内去重，上限 256，记满清空重来）。
去重键**必须细到规则**：同一出值上的第二条坏规则否则永远报不出来（它开不了口，
而「配了却不工作、还没有声音」正是要消灭的失效模式）。
理由：`SKIP` 的常见形态之一是**定义与运行期的值长期对不上**，那是**持续状态**而不是事件 ——
5 秒一个周期就是一天一万七千行 WARN，刷到这个程度只会训练人忽略它，
而这条日志存在的全部意义就是**被看见**。说一次、说清楚，比每周期重复一遍有用；
清空重来是给「坏定义比上限还多」留的退化路径，那种情况下多报几轮是应该的。

去重只影响日志，**不影响判定**：`SKIP` 仍然不写库（见第五节 `Decision.isEmpty()` 有意忽略 skips），
所以「稳态零写」这条纪律不被日志需求破坏。

`=` 对带 `value-list` 字段有个不对称：值是**数**（原始值没命中取值表）时判 `MISS` 而不是 `SKIP`
—— 我们明确**知道**它的值，且知道它不等于 `state`。反过来，配置比数、值却是描述字符串 ⇒ `SKIP`
（定义对不上，老数据）。

### 同一出值上的多条规则：选举

一个出值可以配一组规则（如上），但**同时最多只有一条开着**。每个出值一个判定循环，取代原来的
单条判定：

| 情形 | 动作 |
|---|---|
| 命中 ≥1 条，胜者的 `id` == 开行的 `ruleId` | **什么都不做**（稳态零写，这是「一次越限一条」的落点） |
| 命中 ≥1 条，开行的 `ruleId` 不同（或没有开行） | 有开行 ⇒ 按 `SUPERSEDED` 关掉它；再开出胜者 |
| 命中 0 条、有 `MISS`、有开行 | 按 `VALUE` 关（值恢复正常） |
| **全部 `SKIP`** | 什么都不做（一次截断/畸形的应答不能把开着的告警关掉，第四节那条纪律不变） |
| 出值不在本次采样里、该出值有开行 | 什么都不做（分不清「定义删了它」还是「这一帧截断了」） |

三条裁决细则：

* **胜者 = 命中里级别最高的那条**，次序只有一份（`ModbusAlarmPolicy.LEVEL_ORDER` =
  `INFO < WARN < CRITICAL`，`rank()` 从它来）。不认识的级别（老数据、或后端将来加了新级别）
  **一律最低**：它仍然能开出告警来，只是选不赢任何认识的那条。
* **同级并列取声明顺序靠后的那条**（`>=` 而非 `>`）。需要一条确定的规则，且写在这里备查。
  **不做「同级去重」校验**：`低于 20 严重` + `超过 30 严重` 这类低侧/高侧配置永远不可能同时命中，
  拒掉它是错的；而真要判「两条规则能否同时命中」需要一套区间重叠推理，不值当。
* **命中优先于判不了**：同一出值的规则之间可能出现「一条 `HIT`、另一条 `SKIP`」（定义与值形态
  对不上，校验器挡新数据、老数据可能出现）。有胜者就按胜者走，`SKIP` 只进日志。

**降级方向也用 `SUPERSEDED`**：31℃ 降到 27℃ 时「超过 30 严重」那条关闭，此时的真实原因
**不是**值恢复正常（27℃ 仍然越限），记成 `VALUE`（值恢复）是谎话。`SUPERSEDED` 一个值盖两个
方向：「当前生效的是同一出值的另一条规则」。

**写序：先关、后插**。原来的实现把「开」「关」一起并发发出去，那时没问题 —— 一个出值要么 `HIT`
要么 `MISS`，两者永远落在不同出值上。`SUPERSEDED` 打破了这个前提：**同一个出值既关又开**。
并发发出去时插入会抢在关闭之前，撞上 `{openKey}` 唯一部分索引，被 `insertOpen` 当成「别的副本
先开了」**静默吞掉**（它本来就该吞 11000，看不出区别），随后关闭才执行 ⇒ **这个出值一条开着的
都没有**。下一轮采样会自愈（没有开行 ⇒ 重新开那个胜者），但那个窗口里告警是消失的，
**轮询一停就永久丢**。所以 `apply` 是**两段** `chain`：先并发关（三类原因一起），再并发插 ——
不能靠 `ops` 列表的先后，`Uni.combine().all()` 是**并发**的。
`ModbusAlarmServiceTest.supersededRowIsClosedBeforeTheWinnerIsInserted` 用 `InOrder` 钉住它，
与 `ModbusServiceServiceTest` 里那条 `InOrder` 同一性质：**顺序写错了不报错，只是悄悄丢告警**。

## 五、状态与边沿：开着的行就是状态

不另存一份告警态。`(serviceId, pollingIndex, field)` 上有**未恢复**的告警就是「开着」，
判定只需要这一张集合（**出值名 → 那条开着的行上的 `(ruleId, handled)`**，即 `OpenAlarmRule`），
它由告警表自己查出来。一个出值同时最多一条开着 —— 这既是选举的结果，也恰好是那条唯一部分索引
强制的**不变式**。

`handled` 要一起读进来，是因为它现在也是边沿之一（下表第二行）；`ruleId` 与 `handled` 分工明确：
前者答「这条开行还是不是对的那条」，后者答「它被人认领了没有」。

| 值 | 有无未恢复告警 | 动作 |
|---|---|---|
| `HIT`（胜者就是开行那条，**未处理**） | 有 | **什么都不做**（稳态零写） |
| `HIT`（胜者就是开行那条，**已处理**） | 有 | **关闭**（`closeType=HANDLED`），再**插**一条同出值的新行（**先关后插**，第四节末） |
| `HIT`（胜者换了人） | 有 | **关闭**（`closeType=SUPERSEDED`），再**插**胜者（**先关后插**） |
| `HIT`（还没开行） | 无 | **插**胜者 |
| `MISS` | 有 | **关闭**：按 key `updateMany` 置 `recoveredAt` / `closeType=VALUE`、`$unset openKey` |
| `MISS` / `SKIP` | 无 | 什么都不做 |
| **全 `SKIP`** | 有 | 什么都不做（凭空恢复是禁忌，也不重报） |
| 采样里有、但新定义已不再配任何启用规则 | 有 | **关闭**：`closeType=DEFINITION` |

最后一行是**定义变更的自愈主路径**：字段被删、或该出值所有规则都被停用时，那条未恢复的告警
再也判定不到、会永远挂着。`decide` 只看**新定义**，所以补了这条。注意它**只对「本次采样里确实
存在的键」生效** —— 采样里没有的键分不清是「定义删了它」还是「这一帧截断了」，宁可留着也不能
凭空恢复。

`covered` 仍然停在**出值**级（有 ≥1 条启用规则且值可比 ⇒ 归值判定管），胜者换人的情况交给
`SUPERSEDED`。于是关掉一条开行的三条原因互斥且覆盖完整：出值**走了值判定**而胜者换了人 ⇒ `SUPERSEDED`；
出值**走了值判定**、胜者没换、而那条行**已经被人处理过** ⇒ `HANDLED`；
出值**不再归值判定管**（规则全停用 / 出值被删）⇒ `DEFINITION`。
（第四条出路 `VALUE` 不在这里 —— 它属于 `MISS` 那一行，值真的回来了。）

**`HANDLED` 为什么必须有**：「处理」在语义上是「我知道了，**以后还有越线再提醒我**」，
而不是「这事到此为止」。没有这条边沿的时候，一条已处理而未恢复的行会一直挂在那儿挡着：
值继续越限，判定每轮都落在「同一条规则、未处理？不，已处理」以外的分支上什么都不做，
用户再也收不到提醒 —— 与他的本意正好相反。于是把 `handled == true` 也当成一个边沿：
关掉这条，同时另开一条 `handled=false` 的新行。新行的 `at` 与旧行的 `recoveredAt` 取**同一个 `now`**
（一次评估里的所有动作共用一个时刻，现有约定），读起来就是「这一刻旧的了结、新的开始」——
是实情，不是巧合。新行要**重新处理**：回执不能凭旧行继承。

三条原因是**顺序判定**的（`ModbusAlarmPolicy.judge` 里是一条 `else if` 链），顺序本身承担语义，
三处都不能调换：

- **换规则的检查排在处理检查之前**，所以「规则换了、那条又恰好处理过」只记 `SUPERSEDED`（更具体的原因），
  不会既按 `SUPERSEDED` 记一次又按 `HANDLED` 记一次；
- **值恢复正常时走 `VALUE`**：`winner == null` 直接落到 `MISS` 那一支，「处理过没有」在这件事上无所谓 ——
  值恢复才是实情，记 `HANDLED` 是谎话；
- **全 `SKIP` 时什么都不做**：一次截断 / 畸形的应答既不能把开着的告警关掉，也不能重报。

互斥性还有一道结构性保证：`judge` 每个出值**只调一次**、且命中时返回 `Set.of(field)`，
而 `definitionCloses` 只收「采样里有、却**不**在 `covered` 里」的键 —— 三者不可能同时收到同一个字段。
`Decision` 的四张关闭表因此天然互斥，调用方可以按 `closeType` 各发一条 `updateMany`，
不必担心同一行被两个原因先后改两次。

**这条自愈路径有边界，是有意为之**：第一层闸门 `hasEnabledAlarm` 在**整个方法**上一条启用的
规则都没有时直接返回 —— 于是「某个方法上**最后一条**规则也被停用」的那一刻，那条未恢复的告警
**在这里不会被关**。不为此松开闸门：那会让「绝大多数采集一个数据库操作都没有」这条纪律失效，
而这个残局的数量与「定义刚被改过」完全同阶（一次编辑最多留下几条），不该用**每次采样**的代价去兜。
它归**定义变更对账**（`ModbusAlarmReconciler`，见下）扫掉。
`ModbusAlarmServiceTest.switchingOffTheLastAlarmLeavesTheGateClosedOnPurpose` 钉住的是
**「闸门在前、对账在后」这个分工**，别让人以为是漏判。

**为什么不放进 `ModbusServiceLastPolling`** —— 是**正确性**理由，不是成本理由：影子是**全量替换**写的
（`ModbusHistoryService` 里 `shadowRepo.replace(next)`），而这一带**没有任何事务**。影子按
`(serviceId, pollingIndex)` 一条，告警态是按字段的；让告警判定成为该文档的**第二个写者**，
一次并发就会把 `fields` / `recordedAt` / `lastError` 或告警态悄悄覆盖掉 —— 而「先明细后影子」
那条顺序不变量正是靠单写者成立的。另外 poller 在热路径上**故意跳过**影子读
（`ModbusPollBaselines` 用内存基准），告警无论如何都得自己读一次，扩影子在那里一分钱都省不到。

**也不放 poller 的内存基准**：那是单副本才成立的优化，`Modbus-History-TODO.md` 写明多副本会让基准漂移、
漏记变化。漏一条采样是省成本，**漏一次告警是事故**。

### 为什么这个特性不需要「先明细后影子」那套

两个边沿各自对库**幂等**：插入失败则没有开行，下一条仍越限的样本会重试插入；
关闭失败则行还开着，下一条仍正常的样本会重试关闭。故障记录当初要那套顺序不变量，
是因为它一条记录同时改了明细与影子两个地方。

**成本**：每个「配了告警的方法」每轮一次带索引的读（`{serviceId, pollingIndex, openKey: {$exists: true}}`，
只投影 `field`、`ruleId` 与 `handled`），一个方法配 3 个字段也只读一次、一个出值配 8 条规则也还是读一次。
**没配告警的服务零库操作** ——
第一层闸门 `hasEnabledAlarm` 是纯内存判断，与 `ModbusHistoryPolicy`「绝大多数采集一个数据库操作都没有」
同一条纪律。

### 定义变更对账与删除级联

上面那条自愈路径有两处够不着，都由这里兜：

| 残局 | 为什么自愈够不着 |
|---|---|
| 一个方法上**最后一条**规则也被停用 | 第一层闸门 `hasEnabledAlarm` 直接返回，连「哪些开着」都不查 |
| 改了定义后这个方法**再也不采**（方法被删、`polling` 关掉） | 这个方法根本不会再产生采样，`decide` 没机会跑 |

判据是纯函数 `api/modbus/alarm/ModbusAlarmPlan.java`：**「按新定义，这个 `(方法序号, 出值名)`
上还有没有一条 `id` 等于这行 `ruleId`、且启用着的规则」**，找不到的按 `DEFINITION` 关掉。
**判据细到规则，不是停在出值**：一个出值能配一组规则，停在出值的话，用户删掉「超过 30 严重」
这条规则后，那条按它开出来的行会被判成「还覆盖着」而永远留着 —— 新定义里已经没有能判定它的
规则了（`ModbusAlarmPlanTest.deletedRuleClosesEvenWhenASiblingRuleRemains` 钉住）。
四样口径值得记：

* **按新定义重新算一遍覆盖集，而不是拿新旧定义做差分**。差分要存旧定义，而写入路径手上只有
  `merged`；重算只需要 `modbusService.functions` 一份输入，启动那趟也复用同一个函数。
  覆盖集因此是 `方法序号 → {出值名 → 该出值上启用的规则标识集合}`。
* **行上没有 `ruleId` 的一律不动**（只可能来自更早的数据）：判据是拿它去比对的，没有它就说不出
  「这条规则还在不在」。而关闭才是**不可逆**的那个动作，宁可留着 —— 采集侧的胜者比对会把它按
  `SUPERSEDED` 接手，那才是能说清原因的那条路径。
* **不认识的服务一律不动**。写入路径只传一个服务、只取那个服务的开行，所以这条在写入路径上
  永远不会触发；它的价值是**拦住将来某个调用方传半份服务列表**时的误关（判宽了会把别的服务的
  告警一次关一片）。代价见下面「不做的事」。
* **一个方法一条语句**：关闭是按整个开行标识 `updateMany` 的，同一方法上的多个陈旧出值合成
  一条 `closeByKeys`，不是一条语句一个出值（否则一轮对账的写语句会放大成出值数倍）。

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

实现拆成两层：`ModbusServiceValidator.validateAlarms`（一组规则的共性）逐条委托
`validateAlarm`（单条规则，规则清单见 `MODBUS.md` 第七节）。几条口径：

* **只在 `enabled == true` 时校验**：半配置合法，与 `validatePolling` 同一条规矩
  （`false` 的用法就是「把配置留着、先不报」）。`id` 的检查也在这一层之内 —— 停用的规则不参与
  `decide`，它的 `id` 不影响任何判定，所以既不要求存在、也不占用组内的唯一名额。
* **只有「一组」才有意义的两条**：条数上限 **8 条/出值**（`alarm` 与 `bit` 各自算），
  以及启用规则的 `id` 非空、不超长（64）、**同一出值内唯一**。
  条数上限**不是安全边界**（多几条只是多几次纯内存 `hit`），是给「界面上误加了一堆规则」一个
  明确的上限；这个校验器里此前**没有「集合最多 N 条」的先例**（既有的上限都是长度或区间），
  所以这是新加的一类防呆。
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

**`HANDLED` 那一支让「先关后插」从顺序偏好升级成硬约束**（`ModbusAlarmService.apply`）。
一次评估里的动作分两段串起来：`all(closes).chain(() -> all(opens))` ——
`Uni.combine().all()` 是**并发**的，顺序只能靠 `chain` 给。反过来（先插后关）时新行会撞上
那条唯一部分索引，被 `insertOpen` 的 `recoverWithItem(false)` **静默吞掉**：用户刚点完「处理」，
旧行关了、新行没了，**列表上那件事整个消失** —— 比别处撞索引的后果重得多。
万一还是撞上了（关的同时另一轮采集重开了行），**下一轮采集就会补开**：那时状态表里已经没有
这条开行，判定走「还没开行 ⇒ 插」那一支。这条自愈路径是**有意的**，看到一轮的空窗别当成 bug ——
它最多漏一拍，不会把告警永久弄丢（`Modbus-Alarm-TODO.md` 里同样记了这一条）。

### 第 0 步的冒烟台账：**已做完，唯一部分索引成立**（2026-09-14，staging）

建之前不能确定：部署是 Postgres 底的 Azure DocumentDB，**部分索引支持未实证**。仓库已有先例
（本文件的姊妹篇 `Modbus-History.md` 第六节：TTL「既没列为不支持、也没写明支持，所以结论是只能
实测」，然后真的冒烟并记账）。

**为什么不能只看 `listIndexes` 就收工** —— 两条方向相反但都指向「先别建」的理由：

* **建了但被静默吞掉**：`ensureIndexes()` 的失败处理是 `recoverWithNull`（不阻断启动，与其它仓储
  同口径）。服务端若不支持部分索引而抛错，那一行会被吞掉 —— 于是「以为有护栏、其实没有」，
  **比明说没有更糟**（读到索引文档也证明不了语义，只能靠行为冒烟）。
* **建错了更危险**：万一服务端忽略 `partialFilterExpression` 当成普通唯一索引，所有**已恢复**的
  行 `openKey` 都是缺失（等同 null），同一出值的第二条已恢复告警会被拒 —— **告警开始丢**。
  这是把「多一条重复」换成「少一条真告警」，两害相权，后者重得多。

**实测**（staging 临时集合 `modbus/alarm-smoke`，建索引后跑四条行为探针，跑完 `drop`；
`modbus` 库前后集合清单一致、没有任何真实集合被碰）：

| # | 探针 | 结果 |
|---|---|---|
| 0 | `createIndex` 会不会抛错 | **没抛错**，也没被 `recoverWithNull` 吞掉 |
| 1 | 插两条**同** `openKey` | 第二条 `11000 Duplicate key violation on the requested collection: Index 'openKey_unique_partial'`；连插两次都挡住，该 key 行数 1 |
| 2 | 插两条**都没有** `openKey` | **两条都成功**（无 `openKey` 的行数 2）—— 这一条才证明「部分」真的部分，普通唯一索引会把第二条的 null 当重复拒掉 |
| 3 | 不同 `openKey` 之间 | 不互挡（`k2` 在 `k1` 开着时照样插入） |
| 4 | `$unset` 关闭后再开同 key | 成功，「一条关着的 + 一条新开的」共存 |

实际落成的索引文档（`storageEngine.enableOrderedIndex` 是这套实现自己的扩展，不影响语义）：

```json
{"v":2,"key":{"openKey":1},"name":"openKey_unique_partial",
 "partialFilterExpression":{"openKey":{"$exists":true}},"unique":true,
 "storageEngine":{"enableOrderedIndex":true}}
```

**结论：1 号索引已补进 `ensureIndexes()`，回退档 F1 / F2 不需要启用**（留在下面备查，日后换存储
时是同一条判定路径）。

**顺带捞到的一条纪律（第 4 条探针的副产品）**：显式写 `openKey: null` 的行**也算「存在」**
（`$exists` 对 null 成立），索引键是 null，于是**第二条这样的行会被拒**。所以关闭只能写成
`$unset`（`ModbusServiceAlarmRepository.closeUpdate`），**绝不能用 `$set openKey: null` 代替** ——
那会让「关闭」从第二条起失败，而失败的样子是静默的（关闭的调用方只记日志）。这条已写进
`closeUpdate` 的注释，因为那正是日后有人想「简化」它的地方。

**回退档**（本次没用上，留档；**不静默降级**）：

* **F1**：普通唯一复合索引 `{serviceId, pollingIndex, field, recoveredAt}` —— 不用任何冷门特性。
  开着的行 `recoveredAt` 都是 null，唯一索引把 null 当一个值，所以第二条开行会撞；
  关闭的行时间戳各异可以共存。唯一失效模式是「同一毫秒的两次关闭」，而那会让**关闭**失败 —— 自愈。
* **F2**：不建唯一索引，改用 `updateOne({openKey}, {$setOnInsert: …}, upsert: true)` 读
  `getUpsertedId()` 判断是否新建。只缩小窗口、**不关闭**竞态（MongoDB 明确说 upsert 无唯一索引时
  仍可能重复），只能与 `updateMany` 兜底一起用，并且必须把这条竞态**写进文档**。

**另两个小竞态都无害**：两个评估者同时关闭同一 key —— `updateMany` + `$unset` 幂等，第二个匹配 0 条；
用户处理与告警关闭同时发生 —— 两者是互不相交字段的 `$set`，用 `updateOne`（**绝不 `replaceOne`**），
过滤条件带 `{handled: {$ne: true}}`，`matchedCount == 0` 时再 `findById` 一次以区分「已处理」与「不存在」，
好给出精确文案。

## 八、数据模型

`db/modbus/alarm/ModbusServiceAlarm.java`，`@MongoEntity(database="modbus", collection="service-alarms")`：

```java
public String  serviceId;
public Integer pollingIndex;
public String  field;        // 出值名（字段名，或 bit-list 的位名）
// —— 触发那一刻的定义快照 ——
public String  compare;      public Double threshold;   public String state;
public String  level;        public String text;        public String unit;
// —— 边沿 ——
public Date    at;           // 越限首次出现的时刻
public Date    recoveredAt;  // null = 仍在越限（这就是状态本身）
public String  closeType;    // VALUE | DEFINITION | SUPERSEDED | HANDLED
// —— 处理 ——
public Boolean handled;      public Person handledBy;
// —— 触发时的样本 ——
public Object  sample;       // 那一刻的值（Number 或 description），开着期间不刷新
// —— 内部，不下发 ——
public String  openKey;      // 未恢复时才有：serviceId#pollingIndex#field
public String  ruleId;       // 触发这条的是哪条规则（配置里那条规则的 id）
```

**关闭原因四个值，覆盖全部路径**：

| 值 | 何时 | 页面文案 |
|---|---|---|
| `VALUE` | 值回到不命中任何规则 | 值恢复 |
| `DEFINITION` | 定义不再覆盖这个键：出值被删、规则被删、或该出值所有规则都被停用 | 定义变更 |
| `SUPERSEDED` | 当前生效的是同一出值的**另一条**规则（升级或降级） | 被取代 |
| `HANDLED` | 这条已经被人**处理过**、而那条规则**还在命中**：关掉它，同时另开一条未处理的新行 | 处理后再报 |

**为什么配置要快照进行**：定义是活的，行是不可变的历史。只存引用的话，把阈值从 80 改成 60
会**悄悄改写每一条历史告警的含义** —— 「当时为什么报」这件事被一次无关的修改编辑掉了。
与 `ModbusServiceFailure` 存 message 文本而不是错误码同一个道理；也让告警行在服务被删掉之后
仍然自解释。`closeType` 区分四种关闭：否则 UI 无法解释一条没有恢复样本的关闭，
与 `ModbusFailureType` 那句「宁可说不知道，也不猜一个会把人带偏的分类」同一精神。

`HANDLED` 那一行要连**新开的那条**一起读：旧行的 `recoveredAt` = 新行的 `at`（同一个 `now`），
两条行合起来才是「这一刻旧的了结、新的开始」。旧行保留 `handled=true` 与它的 `handledBy`
（那是**当时**处理的记录，不改写），新行的 `handled` 是 `false`、**不带** `handledBy` ——
回执不继承，新的一条要重新处理。
`ruleId` 存的是**配置里那条规则的 `id`**（不是数组下标，理由见第二节），它只在库里用于两处比对
——对账判断「这条规则还在不在」（第五节末）与采集侧判断「胜者是不是还是它」——**绝不下发**。

**索引**（`ensureIndexes()`：幂等、失败不阻断启动、沿用 `modbus.index.enabled`）：

1. `{openKey: 1}` unique + `partialFilterExpression` —— **并发护栏**，2026-09-14 在 staging 上
   冒烟通过后已建（索引文档形态与四条探针见第七节）。一组规则落地后它又**升级成一条更强的
   不变式**：「一个出值同时最多一条开着」不再只是选举的结果，而是索引强制的东西 ——
   这也正是 `openKey` 至今仍是出值级、不必带上规则标识的原因
2. `{serviceId: 1, pollingIndex: 1, openKey: 1}` —— 采集侧查未恢复
3. `{serviceId: 1, pollingIndex: 1, at: -1}` —— 列表查询

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
| GET | `/many/{spaceId}` | 空间成员 | `serviceId`（不传 = **整个空间含子空间**）、`pollingIndex`、`field`、`level`、`open`、`handled`、`from`/`to`、`limit` |
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

### 运维口径：要看的是「只看未恢复」那张视图

一组规则落地后，**`open = true` 这个筛选恰好等于「当前生效的告警」** ——
一个出值同时最多一条开着（第五节的选举 + 唯一部分索引），所以那一张视图就是「现在哪儿在报警」，
一条不多一条不少。日常盯它。

而 `未处理` 计数**可以大于它**，这不是 bug：被取代的行仍然算未处理（第一节那张表里明确选的）。
一个出值从「提示」升到「警告」再升到「严重」，会产生两条 `SUPERSEDED` 的已恢复行，
它们都还挂着未处理 —— 那是**如实**的：确实报过三次，确实没人处理过。
要清掉它们，用「未处理」这个筛选逐条点「处理」；**不要**指望 `open` 能替你把它们筛掉。

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

一组规则**不改变**这条算术，只改变它的形状：反过来，**分级配置会放大它** —— 值在两级之间来回
摆动时，每次摆动是「一条 `SUPERSEDED` 关闭 + 一条新开」，于是一个服务一天的上限变成
「穿越次数 × 级别数」。跨级抖动**也各产生一条**（这是有意为之：那一级确实生效过，
记下来才知道当时到了哪一级）。所以触发线**按告警条数算，不按「越限次数」**。

**`HANDLED` 那条边沿不改变量级，只是把「谁决定重报」从阈值挪到了人手上。**
每次重报都要有人真的按过一次「处理」（判定条件精确到 `handled == true`，见第五节），
所以它的上限是「越限次数 + **人工处理次数**」，不会自激：没人处理那就一直停在稳态零写，
处理一次最多换来一条新行。**它不构成加回差的理由** —— 但它确实让「一天多少条」这件事
多了一半由人来决定，实测时要把这一项单独看一眼（一个反复越限的出值，如果每轮都有人点处理，
条数就与人的动作次数成正比，而不是与阈值抖动成正比）。

先就这么做（这是明确的选择，`ModbusAlarmPolicyTest.noDebounceSoEveryCrossingIsItsOwnAlarm`
把它钉住，将来加回差必须先改掉那条测试）。但留一条**触发线**，照仓库「换存储的触发线」那个写法：

> **实测若某个服务日均告警 > 100 条，回来加回差**（`compare` 之外补一个 `deadband`，
> 判定改为 `HIT` 需要越过阈值、回到正常需要退回 `deadband` 之外）。

这样将来要不要回头加防抖，是个**有据可依的决定**，而不是凭感觉。

## 十一、测试

**纯函数单测**（`@QuarkusTest` 要真连库，故逻辑都抽成无库静态函数）：

* `ModbusAlarmPolicyTest`（30 例）：五种比较与边界；`scale` 生效后比缩放值；
  `value-list` 命中走 `=`（命中 / 不命中 / 值是数）；位 0/1；**四种边沿格**；
  **不做防抖**（阈值附近来回穿越产生 开/关/开/关）；重启后遇到已存在的开告警不产生第二条；
  键缺失 / `NaN` / 值为 null ⇒ `SKIP` **且不关闭**已开的告警；字段已不在定义里 ⇒ `DEFINITION` 关闭；
  一次采样里两个字段一开一关；`hasEnabledAlarm` 闸门（含位上的告警也算）；
  `Open` 带着单位快照、位没有单位。选举那一组：三级同时命中 ⇒ **只有最严重那条开出**；
  升级 ⇒ 旧行 `SUPERSEDED` 关 + 新开；**降级也算被取代、不算值恢复**；同级并列取声明顺序靠后的；
  **改阈值（同 `id`）不 churn**（稳态零写）；同出值「一条 `HIT`、一条 `SKIP`」⇒ 命中优先；
  `rank` 低到高且不认识的（含 `null`）最低；**每条坏规则各自报一次 `SKIP`**；
  规则全停用 ⇒ `DEFINITION` 而不是 `SUPERSEDED`；位有自己的一组规则（与父字段互不干扰）。
  已处理那一组（4 例）：同一条规则仍在命中 + `handled=true` ⇒ `HANDLED` 关 + 重开一条**未处理**的
  （而 `handled=false` 走稳态零写，与「改阈值不 churn」同一条断言守着）；
  **`handled=true` 但值回到正常 ⇒ 仍是 `VALUE` 不是 `HANDLED`**；
  **规则换了又恰好处理过 ⇒ 只有 `SUPERSEDED` 一个关闭**（顺序检查，不重复关）；
  **全 `SKIP` + `handled=true` ⇒ 什么都不做**（不关也不重报）。
* `ModbusAlarmServiceTest`（23 例，Mockito 不连库）：没配告警 ⇒ `verifyNoInteractions(alarmRepo)`；
  稳态**零写**（读一次、一个写操作都没有）；开出来的行带着定义快照（含 `unit`、位不带）、
  `ruleId` 就是胜者的 `id`；按值关 / 按定义关各自的 `closeType`；一次采样里既开又关；
  **被取代的行是按 `SUPERSEDED` 关、不是按值恢复**；**先关后插**（`InOrder`：关闭语句在前、
  插入在后 —— 顺序反了会撞唯一索引、被当成重复静默吞掉，那个出值就一条开着的都没有。
  `HANDLED` 那一支同样有一条 `InOrder`：`closeByKeys(..., CLOSE_HANDLED, ...)` 先于 `insertOpen`，
  而且新插入的那条是 `handled=false`、`ruleId` 仍是同一条规则 —— 吞掉的后果比别处更重，
  用户刚点完「处理」就什么都看不到了）；
  判不了**只警告一次**（含跨帧的截断样本）且不写库、**同一出值的两条坏规则各报一次**；
  警告里带上当时的原始值与完整定义；去重上限会清空重来；
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
* `ModbusServiceValidatorTest`（告警那一段 15 例）：逐条拒绝（未知 `compare`、未知 `level`、空 `text`、
  超长 `text`、`string` 字段配告警、`value-list` 字段配 `>`、`=` 缺 `state`、`state` 不在描述里、
  两个都给、都不给、位上配 `>` 或 `0.5`、写方法上配告警）+ `enabled != true` 时全部放行；
  一组规则才有的：合法的一组（含方向相反的低侧/高侧）、`id` 缺失 / 空白 / 超长 / 组内重复、
  **`id` 的唯一性只在同一位内要求**（位与父字段各是一组）、条数上限 8（8 条过、9 条拒）、
  数组里的空元素照拒、停用的规则既不查 `id` 也不占名额。
* `ModbusServiceCodecTest`：示例体收进 `alarms` 数组（encode∘decode 稳定性、数组原序保持、
  `id` 保真）+ 「没配就不出键」+ 空对象 / **空数组** / 「元素全是空对象」都读作「没配」。
* `ModbusAlarmCodecTest`（9 例）：全字段行；**`openKey` 绝不出现**（行内带着它、出去没有它）；
  `null 不出键`；`=` 的取值表状态（有 `state` 无 `threshold`）；字符串样本原样出去；
  汇总的计数（`open` 按 `recoveredAt` 数）与两个 tally 的排序；`level`/`text` 缺失归 `UNKNOWN`
  桶而不替用户编文本；空输入全零；`openKeyOf` 的拼法稳定（改了它，库里已有的开行就再也关不掉）。
* `ModbusServiceAlarmRepositoryTest`（12 例，纯静态不连库）：三种异常形状都认、非 11000 的
  写错误照常外传、cause 链包装能穿透、自引用 cause 会立刻停下、超长链有界。
  **它值得单独测**：整套并发推理压在「重复键被认出来、然后吞掉」上，判漏就是静默地产生
  两条开着的告警 —— 正是这个特性要避免的失效模式。
  后 4 例钉住**写语句的形状与折算**（这些错了不会报错、只会悄悄不对）：关闭就是 `$unset openKey`；
  关闭按整个开行标识 `$in` 一批出值、**不是**定位某条 `_id`；处理是 `{handled: {$ne: true}}` 上的
  CAS + 只 `$set` 两个字段（整文档替换会抹掉并行的关闭刚写的 `recoveredAt`）；
  **投影里缺失的 `handled` 折算成「未处理」** —— 这一条写反了，**全部老数据**（没有 `handled` 键）
  都会被当成已处理，于是每轮采集都给它们关旧开新：稳态零写当场失效、列表每采一次翻一倍。
* `ModbusAlarmPlanTest`（17 例，纯函数不连库）：还配着 ⇒ 不动（字段 / 位各自）；告警被关掉 ⇒ 关、
  告警配置整个没了 ⇒ 关、字段被删 ⇒ 关、方法被整个删 ⇒ 关、**字段改名**（旧名那条）⇒ 关、
  写方法上的开行 ⇒ 关、`pollingIndex` 为 null 的行 ⇒ 关；**不认识的服务一条都不动**
  （含服务列表为空）；坐标不全的行跳过；空 / null 输入 ⇒ 空；**一个方法一条 `Close` 且字段名有序**；
  排序确定（服务 → 方法序号，`null` 在前）。规则级判据那一组：**删掉那条规则、同出值别的规则还在
  ⇒ 照样关**（判据停在出值级会漏掉，而那条行会一直挂着 —— 没有任何规则能再改变它）；
  **行上没有 `ruleId` 的留着不动**。
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

### 2026-09-16

* **第四个边沿：`handled == true` 也要关旧行、开新行**（第五、七、八、十、十一节随之改写）。
  起因是一条**语义反了**的现场事实：「处理」的意思是「我知道了，**以后还有越线再提醒我**」，
  而不是「这事到此为止」；可在那之前，一条已处理而未恢复的行会一直挂在那儿挡住后续越限，
  判定每轮都落在稳态零写那一支，用户再也收不到提醒。改动落在 `ModbusServiceAlarm`
  （加 `CLOSE_HANDLED`）、`ModbusAlarmPolicy`（`judge` 多一支 + `Decision` 多一张 `handledCloses`）、
  `ModbusServiceAlarmRepository`（投影加 `handled`，值型从 `String` 变成 `OpenAlarmRule`）、
  `ModbusAlarmService.apply`（第四段关闭）。几处值得记下的口径：
  * **不动任何已落库的行**（这条规矩本轮一个字没破）：只加了**一个新的关行判据**，
    行上的定义快照、`handledBy`、`ruleId` 都不改写。旧行留着 `handled=true` 与它的处理人
    （那是**当时**的记录），新行是 `handled=false` 且**不带** `handledBy` —— 回执不继承。
  * **顺序即语义，三处都不能调换**：换规则的检查排在处理检查**之前**（规则换了又恰好处理过 ⇒
    只记 `SUPERSEDED`，那是更具体的原因）；`winner == null` 走 `MISS` 那一支 ⇒ 值恢复时记 `VALUE`
    而不是 `HANDLED`（**值恢复才是实情**，处理过没有在这件事上无所谓）；全 `SKIP` 时什么都不做
    （一次截断 / 畸形的应答既不能关掉开着的行，也不能重报）。
  * **新类型 `OpenAlarmRule` 放在 `db` 包，不放在 `ModbusAlarmPolicy` 里**：判定要的资料从
    「出值名 → ruleId」变成「出值名 → (ruleId, handled)」。放 `api` 会新造一条 `db → api` 的
    反向依赖（`ModbusServiceAlarmRepository` 目前一个 `api` 包都不 import，而现有箭头是
    `ModbusAlarmPolicy` → `db.modbus.service.*`）。生产方与消费方都在 `db` 的同侧，签名两边都读得通。
  * **`Boolean.TRUE.equals(handled)`，不能写成 `row.handled`**：`handled` 可空（老数据、以及
    插入时的写法），空必须当**未处理** —— 与仓储自己的 CAS 过滤 `Filters.ne("handled", true)`
    同一个口径。**写反了不会报错**：全部老数据都会被当成已处理，每轮采集给它们关旧开新，
    稳态零写当场失效、列表每采一次翻一倍。为它单出了一条仓储测试。
  * **`handledCloses` 必须算进 `Decision.isEmpty()` 与 `Acc.isEmpty()`**：它是**写操作**，
    漏了会让「只剩一次 `HANDLED` 关闭」的判定被当成无事发生而直接返回（`NOTHING`），
    重报整个不发生 —— 而且是静默的。
  * **「先关后插」在这里从顺序偏好升级成硬约束**：顺序反了，新行会撞唯一部分索引、被
    `insertOpen` 的 `recoverWithItem(false)` 静默吞掉 —— 用户刚点完「处理」，旧行关了、新行没了，
    **列表上那件事整个消失**，比别处撞索引重得多。自愈路径仍在（下一轮看到「还没开行」会补开），
    最多漏一拍，已在第七节写明，免得以后有人看到空窗当成 bug。
  * **重报是无限的，但由人驱动**（第十节）：触发条件精确到 `handled == true`，所以上限是
    「越限次数 + **人工处理次数**」，不会自激。**不做防抖、不加冷却期、不按处理次数限流** ——
    这是明确的选择，不是漏掉。
  * **不用动的地方**（已核对）：`ModbusAlarmCodec` 原样透传 `closeType`，且它的 `unhandled`
    用 `Boolean.TRUE.equals(handled)` 判 —— 被 `HANDLED` 关掉的行本来是 `handled=true`，
    仍计为已处理，正确；`open` 按 `recoveredAt` 数，关行写了它，正确。
    `ModbusAlarmPlan` / `ModbusAlarmReconciler` 按定义对账，`HANDLED` 关掉的行没有 `openKey`，
    不在开行集合里。`rowOf` 本来就写 `row.handled = false`，重报出的新行天然未处理。
  * **测试**：Policy 26 → 30、Service 22 → 23、Repository 11 → 12。全量 **579 例全绿**
    （本机 40 个类，含上次那条 `getGraph_succeeds`）。前端 `MODBUS_ALARM_CLOSE_LABELS` 加
    `HANDLED: '处理后再报'`（没收录的枚举名仍原样回退，`title` 上留原始枚举名供搜日志），
    词典 66 份各加 1 键（468 → 469，追加在末尾、键序一致、逐语种真译文）。
* **前端落地：`/main/alarm` 与 3D 页的关闭原因多一种是「处理后再报」**（`webapp-matrix`，
  1.0.1 → 1.0.2）。渲染路径一行没改 —— `modbusAlarmCloseLabel` 本来就是「查表 + 兜底原样」，
  加一个键就够了；这正是当初把它做成闭集映射的收益。

### 2026-09-14

* **模型返工：一个出值一组规则，同时只留最严重的一条**（第一 ~六节、第八~十一节随之改写）。
  起因是「一个出值只能配一条规则」表达不了分级的真实需求（温度：低于 20 告警 / 超过 26 提示 /
  超过 28 警告 / 超过 30 严重）。改动落在 `ModbusPollingResponseFieldAlarm`（加 `id`）、两处 `alarm` →
  `alarms` 数组、`ModbusAlarmPolicy`（选举 + `LEVEL_ORDER`）、`ModbusAlarmService`（`ruleId` +
  写序）、`ModbusAlarmPlan`（判据细到规则）、`ModbusServiceAlarm`（`ruleId` + `CLOSE_SUPERSEDED`）。
  几处值得记下的口径：
  * **为什么选「只留最严重的一条」反而动得少**：一个出值同时最多一条开着 ⇒ `openKey` **仍然是
    出值级**，唯一部分索引、仓储的 `closeByKeys` / `handle` / `ensureIndexes` **一个字没改**，
    而且那条索引从「防重复开」升级成「一个出值最多一条开着」这条更强的不变式。
    若选「每条规则各开一行」，`openKey` 必须带上规则标识、索引要重建、关闭要按规则定位 ——
    代价全在存储层。先例：这一带**没有任何事务**，「先关后插」是唯一能保证顺序的地方。
  * **`SUPERSEDED` 一个值盖两个方向**（升级与降级）：31℃ 降到 27℃ 时真实原因**不是**值恢复
    （27℃ 仍然越限），记成 `VALUE` 是谎话。而两个方向都发生在**同一个出值**上，
    这正是写序那个坑的来源。
  * **写序：先关后插，且只能靠两段 `chain`** —— `Uni.combine().all()` 是并发的，`ops` 列表的
    先后不算数。顺序反了插入会撞唯一索引、被 `insertOpen` 当成「别的副本先开了」静默吞掉
    （它本来就该吞 11000，看不出区别），那个出值就一条开着的都没有：下一轮会自愈，
    但那个窗口里告警是消失的，**轮询一停就永久丢**。这是本次唯一「写错了不报错」的地方，
    故用 `InOrder` 钉住。
  * **规则身份用前端生成的 `id`，绝不用数组下标**：删掉第 0 条会让第 1 条正开着的告警
    **静默改嫁**到新第 0 条，关闭原因还会记成 `VALUE`。仓库里已有现成教训（poll key 的
    `configId` 前缀）。附带的收益是**改阈值不再 churn**（id 不变 ⇒ 胜者不变 ⇒ 稳态零写）。
  * **`ruleId` 不下发**（照 `openKey` 的先例：内部标识不泄漏成接口契约）。少动三处 ——
    `ModbusAlarmCodec`、前端 `ModbusAlarm.ts` 与它的 Codec 都不用改。
  * **对账判据细到规则，且「行上没有 `ruleId` 的一律不动」**：前者让「删掉那条规则、同出值别的
    规则还在」的行也能被关掉（判据停在出值级会漏掉，且没有任何规则能再改变它）；后者是因为
    关闭是**不可逆**的那个动作，而采集侧的胜者比对会把它按 `SUPERSEDED` 接手。
  * **空批次必须显式挡掉**：从前一次评估里至少有一个动作，两段写序之后「只关不开」「只开不关」
    都是常态，而 `Uni.combine().all().unis(空列表)` **不是**「什么都不做」。
  * **`rank(null)` 要单独挡**：`List.of(...).indexOf(null)` 直接 NPE，而 null 只是「不认识」
    的又一种（老数据）。
  * **测试**：Policy 16 → 26、Service 19 → 22、Plan 15 → 17、Reconciler 14（夹具跟着模型改）、
    Validator 告警段 10 → 15、Codec 数组往返与「空数组 / 全空元素」的对称性。
    全量 532 例中 531 绿；唯一红色的仍是 `SpaceResourceTest.getGraph_succeeds`
    （`@QuarkusTest`、本机读超时）—— 已用 `git stash` 在**未改动**的 HEAD 上复现过，与本次无关。
* **第 0 步落地：Azure DocumentDB 上「唯一部分索引」成立**（staging 冒烟，四条探针全过；
  `ModbusServiceAlarmRepository.ensureIndexes()` 补上 1 号索引）。台账进第七节。两条值得单说：
  * **必须靠行为冒烟，不能只看索引文档**：`ensureIndexes()` 的失败处理是 `recoverWithNull`，
    不支持时那一行会被静默吞掉 —— 「以为有护栏、其实没有」比明说没有更糟。
  * **捞到一条新纪律**：显式 `openKey: null` 的行**也算存在**（`$exists` 对 null 成立），
    第二条这样的行会被唯一索引拒掉。所以关闭只能写 `$unset`，**绝不能改成 `$set openKey: null`**
    —— 而失败的样子是静默的（关闭的调用方只记日志）。已写进 `closeUpdate` 的注释，
    因为那正是日后有人想「简化」它的地方。
  * 回退档 F1 / F2 本次没用上，留在第七节备查。
* **前端落地：编辑器逐字段配置 + `/main/alarm` 列表页**（`webapp-matrix`，0.9.2 → 0.9.6，
  提交 `7973e80` `5602cea` `813f65e` `66809e3` `6d7307f`）。几处值得记下的口径：
  * **先把词统一了再动功能**：界面与词典里既有的词是「报警」（菜单、面包屑、看板图注），
    需求方写的一律是「告警」。同一件事两种语境两个词正是最容易翻岔的地方，故以一次纯改名的
    独立提交打头（6 个键 × 66 份原地改名，65 种语言的译文一个字没动 —— 它们在那些语言里
    本来就是同一个词）。
  * **编辑器里告警存的是「侧表」**，键 `` `${configId}#${pollingIndex}#${fieldKey}` ``：
    页面上的 `functions` 是按源点表**重建**的，与点表无关的东西存不住（轮询当初就是这么办的）。
    `configId` 前缀不能省 —— 换了源点表不能把告警带到同序号的方法/字段上。
  * **`ServiceBaseline` 必须加 `alarms` 签名**，否则**只改告警会让「保存」按钮不亮**，
    用户以为改动被无声丢掉了。这是这套「侧表 + 签名」最容易漏的一处。
  * **`describeServiceField` 拆成两半**：带告警尾巴的那份给「应答字段」列（那一行是
    「这个方法返回什么、越限会不会报」的摘要），不带的 `describeFieldType` 给展开行的
    「类型」列 —— 那一列右边紧挨着就是告警控件，再说一遍是同一句话说两遍，而且会随用户敲字实时变。
  * **ng-zorro 的下拉做不出「值为 null 的全部选项」**：`writeValue` 里 `covertModelToList`
    把 `null` 变 `[]`，于是「全部」会显示成占位符、看着像「未选择」而语义是「全部」，是在骗人。
    改用 `nzAllowClear` + `nzPlaceHolder` 表达「不限」；`handled` / `open` 的「不传」与
    `false` 也必须是两件事（后者是「只看未处理」）。
  * **列表页取数只要一条请求**：`/many/{spaceId}` 不传 `serviceId` 就是「整个空间（含子空间）
    现在哪儿在告警」，不必像当前值快照那样逐服务扇出。
  * **「处理」就地替换那一行**，不整页刷新；替换与计数修正抽成纯函数 `applyHandledAlarm`
    并单测钉住 —— 里面有个真会踩的坑：清单里没有这一条时（筛选刚换过）
    `before?.handled !== true` 是**成立**的，会把「未处理」白减一次。
  * **服务端/用户文本一律原样显示**：告警文本是用户**自己敲进去**的，翻译它等于改用户的输入；
    字段名、取值表描述、当时的样本值、服务名、`closeType` 枚举名同理（已写进该仓库的 AGENTS.md）。
  * 词典：66 份各追加 29 个新键（388 → 417），另**改了一条会变成假话的旧提示**的字面
    （「除自动轮询与调用周期外均只读」→「除自动轮询、调用周期与逐字段告警外均只读」）。
    两处对计划措辞的偏离：**译文尾巴也要改写**（不只是「替换 key 字符串、保留各语言译文」——
    原话不成立正是因为多了一件事，译文不提这件事就还是假话），以及方案列过的 `汇总` /
    `告警配置` 实测没被任何页面用到，故未加。
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
* **第一步落地：配置模型与校验**（`ModbusPollingResponseFieldAlarm` + 两处 `alarm` 字段 +
  `ModbusPollingResponseFieldAlarmCodec` + `ModbusAlarmPolicy` + `ModbusServiceValidator.validateAlarm`）。
  零 IO、可独立验证；没配告警的服务在采集热路径上一分钱都不多花。同时写下判定口径与落库口径
  （本文件第二至六节、第八至十节），后者待第七节那个冒烟结论门控。
* 从 `validateInterval` 抽出共用的 `isReadFunction`：周期与告警都只允许配在读方法上，
  两处各写一份功能码解析迟早漂移。
