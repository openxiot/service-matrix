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
| 5 | 采集链路接线 `ModbusAlarmService.evaluate` | 设计已定，待落地 |
| 6 | 定义变更对账 / 删除级联 | 设计已定，待落地 |
| 7 | 查询接口 `ModbusAlarmResource` | 设计已定，待落地（见第九节） |
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

**级联删除**：`ModbusServiceService.purgeHistory(...)` 里加一句，删服务 / 设备时一并删告警。

## 九、接口（待落地）

`api/modbus/alarm/ModbusAlarmResource`，`@Path("/matrix/v1/modbus/alarm")`：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/many/{spaceId}` | 空间成员 | `serviceId`（不传 = **整个空间含子空间**）、`functionIndex`、`field`、`level`、`open`、`handled`、`from`/`to`、`limit` |
| POST | `/handle/{spaceId}/{id}` | **空间成员** | 无 body，处理人取 `currentUser()`；返回更新后的那条 |

* `DEFAULT_ALARM_LIMIT = 200` / `MAX_ALARM_LIMIT = 1000` —— **自己的常量**，不复用故障那两个
  （跨用途复用比两行重复更糟）。
* `items` 按 `at` 倒序，**每条带自己的 `id`** —— 这是相对 `ModbusServiceFailureCodec`（不发 `_id`）
  的**有意偏离**：告警行有逐行动作。
* `summary` = `{total, open, unhandled, byLevel, byText}`；`byText` 是「告警类型分布」。
* `handle` 要校验该告警确实落在传入空间的子树内，否则拿别人的 id 就能点。
* Codec **只出不进**（告警由采集侧写、不由请求体写），`openKey` **绝不下发**，时刻一律 epoch 毫秒。
* **不要往 `/history/current` 上加告警**：那个接口项目页已经在 N 次扇出调用它，加一次逐 function
  的告警查询会把两个页面依赖的接口成本翻倍；告警页要的「这个项目现在哪儿在告警」正是
  「不传 `serviceId`」那一个请求。
* `text` 是**用户数据**，前后端一律原样显示、**永不翻译**（前端 `AGENTS.md` 的 i18n 一节）。

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

* `ModbusAlarmPolicyTest`（14 例）：五种比较与边界；`scale` 生效后比缩放值；
  `value-list` 命中走 `=`（命中 / 不命中 / 值是数）；位 0/1；**四种边沿格**；
  **不做防抖**（阈值附近来回穿越产生 开/关/开/关）；重启后遇到已存在的开告警不产生第二条；
  键缺失 / `NaN` / 值为 null ⇒ `SKIP` **且不关闭**已开的告警；字段已不在定义里 ⇒ `DEFINITION` 关闭；
  一次采样里两个字段一开一关；`hasEnabledAlarm` 闸门（含位上的告警也算）。
* `ModbusServiceValidatorTest` 补 9 例：逐条拒绝（未知 `compare`、未知 `level`、空 `text`、
  超长 `text`、`string` 字段配告警、`value-list` 字段配 `>`、`=` 缺 `state`、`state` 不在描述里、
  两个都给、都不给、位上配 `>` 或 `0.5`、写方法上配告警）+ `enabled != true` 时全部放行。
* `ModbusServiceCodecTest`：示例体收进 `alarm`（encode∘decode 稳定性）+ 「没配就不出键」+
  空对象读作「没配」。
* `ModbusAlarmCodecTest`（9 例）：全字段行；**`openKey` 绝不出现**（行内带着它、出去没有它）；
  `null 不出键`；`=` 的取值表状态（有 `state` 无 `threshold`）；字符串样本原样出去；
  汇总的计数（`open` 按 `recoveredAt` 数）与两个 tally 的排序；`level`/`text` 缺失归 `UNKNOWN`
  桶而不替用户编文本；空输入全零；`openKeyOf` 的拼法稳定（改了它，库里已有的开行就再也关不掉）。
* `ModbusServiceAlarmRepositoryTest`（8 例，纯静态不连库）：三种异常形状都认、非 11000 的
  写错误照常外传、cause 链包装能穿透、自引用 cause 会立刻停下、超长链有界。
  **它值得单独测**：整套并发推理压在「重复键被认出来、然后吞掉」上，判漏就是静默地产生
  两条开着的告警 —— 正是这个特性要避免的失效模式。

## 十二、配置项

沿用既有两个开关，不新增：

| 配置项 | 缺省 | 作用 |
|---|---|---|
| `modbus.index.enabled` | `true` | 启动时建索引（含告警那三个）；幂等、失败不阻断启动 |
| `modbus.poll.enabled` | — | 自动轮询总开关；告警判定挂在采集链路上，跟着它走 |

## 十三、变更记录

### 2026-09-14

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
