# Modbus 采集数据的历史存储

本文档只解决一件事：`ModbusDevice.polls[]` 里的轮询被调出来后，
**成功了**解出来的「字段 → 值」往哪存、怎么取，**失败了**的故障又往哪存、怎么取。

* **数据来源**：手动 `invoke` 与服务端自动轮询（`poll.interval` / `poll.polling`）。
  两者走**同一条写入路径** —— 手动调出来的值一样进历史。
* **消费者**：前端画曲线、报表。就这两个。
* **不涉及**：报警 / 规则 / 设备影子。那几条线各自独立，不共用这份数据 ——
  所以「存哪、怎么存」只由「画曲线 + 报表」这两个用途决定。
* **归属**：service-matrix 自己的库（`modbus`），与 manipulation 的设备影子无关。

## 一、成本模型：钱花在「写」上，不在「存」上

> **口径更正（2026-09-13）**：本节按「Cosmos Mongo **RU** 版」写的，而本部署实际是
> **Azure DocumentDB**（原 Cosmos DB for MongoDB vCore）：按**集群规格**计费，不按操作计 RU，
> 所以本文档里一切按 RU 估的数字与金额（本节与第七节）**都不适用**。
> 结论性的两条（写 > 存、`interval` 是最大杠杆）方向不变，
> 但具体数字要按 DocumentDB 的定价重算 —— 留着作为当时的推算，不重写。

在 Cosmos（Mongo API）里，这份数据的成本几乎全在**写入的 RU**上，存储费可以忽略。
而写入量 = **点数 ÷ 周期** —— 保留策略、降采样、压缩，**都救不了它**（原始样本照旧要写一遍）。

量级感受（按小文档写入约 5 RU 估，实际以报价为准）：

| 采集写入量 | 月写入 RU 费 | 落库规则生效后 | 月存储（保留 30 天） |
|---|---|---|---|
| 10 条/s | ~$5 | <$1 | <$1 |
| 100 条/s | ~$30–60 | ~$2–3 | ~$3 |
| 1000 条/s | ~$300–600 | ~$15–30 | ~$30 |

两个推论：

1. **降采样救的是存储和查询，救不了写入 RU**。所以本文档的重点是**落库规则**（第二节），
   它是唯一能直接砍掉写入量的手段。
2. **最大的成本杠杆是 `interval` 本身**，不是选哪家存储。5 秒与 60 秒差 12 倍。
   周期应当按「曲线需要的最细粒度」定，而不是「能跑多快就跑多快」——
   曲线从来只显示到分钟级的话，秒级轮询产生的大部分数据没有任何人读。

## 二、落库规则：三条，逐字段判定

「值没变就不存」只有一条是不够的，它只对**开关量**成立：模拟量（温度、压力、电量）在缩放后
几乎每次都在变（`61.5 → 61.6 → 61.4`），逐位比较永远判「变了」，压缩率会退化成 0。

规则实现在 `ModbusHistoryPolicy`（纯函数，不碰数据库）：

1. **变位**：值 ≠ 基准值 → 落一条。开关量靠这条，天然高效。
2. **死区**：`|值 − 基准值| ≥ deadband` → 落一条。模拟量靠这条；
   死区为 0（缺省）时退化为精确比较。字符串值（`value-list` 的 description、`string` 格式）不走死区，精确比较。
3. **保活打点**：距上次落库超过 `keep-alive-seconds`（缺省 600）→ **强制落一条，即使值没变**。

第 3 条不能省，它有两个作用：

* **区分「值稳定」和「采集挂了」**：没有它，一台设备断线三天，在数据里和「一切正常」长得一模一样。
* **给曲线提供 carry-in 锚点**（见第五节）：长时间不变的点，曲线才不会是一片空白。

`keep-alive-seconds <= 0` 表示**关闭保活**（只按变位/死区落库）。

### 失败记录：同一个故障只落一条

采集不一定成功 —— DTU 离线、超时、帧非法、CRC 不符、异常码，都是常态而不是异常。
这些都进 `ModbusServiceFailure`（`service-failures`），但**规则不同**：

* **只在故障「变了」的时候落一条**。同一条错误持续存在，一个数据库操作都不做。
  一台 DTU 离线三天是最常见的形态：5 秒轮询、逐次落库是一天一万七千条，而信息量和一条完全一样。
* **故障持续多久，由「这条记录」到「下一条成功样本」的间隔推出来**，不逐次打点、
  也不像历史那样打保活点 —— 一个还在持续的故障，这个间隔已经说明了一切，重复打点是重复计费。
  这也正是历史那三条规则里「保活打点」存在的理由：没有它，「值稳定」和「采集挂了」长得一样。
* **失败原因是「分类 + 远端原始码 + 明细」三个字段，不是一段文本**：
  前端要按故障类型聚合，而从 message 文本里反推类型等于把协议语义压成了字符串匹配。
  分类由**抛出点**决定（只有解析器知道是 CRC 不符还是从站异常码、异常码是几号），
  记录端只做透传。分类见 `ModbusFailureType`：

  | 分类 | 含义 | 归属 |
  |---|---|---|
  | `NO_RESPONSE` | 设备没回：DTU/从站离线、超时，或父设备应答里没有帧 | 设备侧 |
  | `SLAVE_EXCEPTION` | 从站异常码（功能码最高位置 1），`remoteCode` 即异常码 | 设备侧 |
  | `DEVICE_ERROR` | 父设备（DTU）自己报错，`remoteCode` 即其状态码 | 设备侧 |
  | `CRC_MISMATCH` | CRC16 校验失败：干扰、接线、波特率 | 报文侧 |
  | `FRAME_MISMATCH` | 数据字节数与点表声明对不上：点表配错或从站不对 | 报文侧 |
  | `INVALID_FRAME` | 帧不成形：太短、非十六进制、结构非法 | 报文侧 |
  | `FIELD_DEFINITION_ERROR` | 不支持的 format、bit 偏移越界 | 定义侧 |
  | `CONFIG_ERROR` | 调用坐标不全（did/siid/aiid/argument） | 定义侧 |
  | `TRANSPORT_ERROR` | 调用链路失败：manipulation 不可用 | 平台侧 |
  | `UNKNOWN` | 兜底：非本模块抛出的异常 | — |

  分这么细的理由是**每一类对应不同的处置方向**：`TRANSPORT_ERROR` 查服务间调用，
  `NO_RESPONSE` / `DEVICE_ERROR` 查现场，`FRAME_MISMATCH` 查点表，`CRC_MISMATCH` 查接线与波特率。
  合并成一类，故障列表就会把人指错地方。拿不准的一律 `UNKNOWN` —— 宁可说「不知道」，
  也不猜一个会带偏方向的分类（把编解码异常猜成「设备离线」，排查就从现场跑到代码上去了）。
  因此**异常没带分类就不进统计**：`IllegalArgumentException` 那类通用异常归 `UNKNOWN`，
  而「方法序号不存在」这种请求写错的情况**根本不记**（没有可归因的 poll，不算执行失败）。
* **`message` 是「同一故障」的判定依据**，超长截断（500 字符）：上游描述可能很长，
  越长越容易让同一条故障被判成不同的两条，去重就压不住了。
  判定用 message 而不是分类 —— 分类相同时 message 未必相同（异常码不同），
  反过来 message 相同则分类必然相同，用 message 是更严的那个键。
  异常没带 message 时退回类名（否则 NPE 之类在库里就是一串 null，什么也说明不了）。
* **记录的是整条 poll，不是某个点位** —— 帧都 CRC 不符的时候，哪个字段都没读到。
  所以它单独一张表，不和「一个字段一个值」的历史混在一起。
* **失败时影子上的值与时刻一律不动**：它们是「最后一次成功的采集」，
  前端要靠它们显示最后已知值以及它从哪一刻起不再更新。

由此带出一条**恢复规则**：上一次调用失败过、这一次成功了，则**这次成功一律落全字段**
（基准置空，等同首次采集）。否则值恰好没变时，恢复的那一刻一个样本都不落，
曲线上就分不清「已恢复」和「仍在故障」。

故障态记在影子上（`lastError` / `errorAt`），所以一次 invoke 仍然只读一次库；
代价是**写方法也要读一次影子**（写方法没有读值可落，但「这次调用成功了」得落下来，
否则上一次失败留的故障态永远清不掉）—— 写方法是低频的人工操作，这一次点读可以忽略。

## 三、数据模型（落库 `modbus`，三张集合）

```
ModbusServiceLastPolling    serviceId / pollIndex / fields{字段→值} / recordedAt
                                     / lastError / errorAt
ModbusServiceHistory   serviceId / pollIndex / field / value / at / keepalive
ModbusServiceFailure   serviceId / pollIndex / type / remoteCode / message / at
```

* **影子按 poll 一条（字段内嵌），历史按 field 一条，失败记录按 poll 一条。**
  影子一张表三用：它既是「当前值」（前端要看的最新值直接读它），
  也是下一次采集的**比对基准**，还记着**最后一次调用是否失败**（`lastError`）。
  成功与失败共用这一个文档，是为了让每次 invoke 只读一次库 ——
  最后一次调用的结果本来就该是一份状态：要么是值，要么是一个故障。
  按 poll 存，一次采集一次写就够；历史按字段落，粒度才对得上前端按点取曲线。
* **点位的身份是 `(serviceId, pollIndex, field)` 三元组**，不额外拼 `pointId` 字符串
  —— 字段名是用户填的（可能是中文，可能带任何分隔符），拼串做前缀匹配迟早会撞上转义问题。
* **时间统一 `Date`（UTC）**，不存字符串：将来真要搬走时这种格式最省事。
* **`value` 是异构的**：数值（`int8`..`uint32`/`float32`、缩放后的 `double`）、
  命中了 `value-list` 时的 `description` 字符串、`bit-list` 展开出来的 `0/1`。
  比对时数值一律按 `doubleValue()` 比（`Integer 5` / `Long 5` / `Double 5.0` 视为同一个值），
  非数值精确比较 —— 这条在 `ModbusHistoryPolicy` 里，有单测覆盖。
  落到实体上它只能是 `public Object value`，于是**必须有**一条按运行时类型分发的 codec
  （`db/mongo/ObjectPropertyCodecProvider`，Quarkus 自动装到 POJO codec 上）——
  驱动的自动 POJO codec 是**按声明类型**取 codec 的，`Object` 这一格没人认领，
  缺了它编码第一步就报 `Can't find a codec for CodecCacheKey{clazz=class java.lang.Object}`。
  也正因为读回来的类是**BSON 类型**决定的（`int32 → Integer`、`int64 → Long`、`double → Double`），
  比对才必须按 `doubleValue()` 来，别去写「类型也要相等」的判断。
* `recordedAt` 是**最后一次成功落库的时间**（值变化或保活打点都算），它是保活周期的判定依据。
  失败不动它：它和 `fields` 一起表示「最后已知值，以及它有多旧」。
* `lastError` 为 null 表示最后一次调用成功；`errorAt` 是当前这条故障**首次**出现的时刻，
  同一故障持续期间不刷新 —— 它到下一行成功样本的间隔就是故障持续了多久。
* 失败记录里的 `type` **存字符串、不存枚举值**（`SpaceEntity.type`、`ModbusResponseField.format` 同口径）：
  枚举增删时老文档还读得出来。`remoteCode` 只有 `SLAVE_EXCEPTION`（异常码）与
  `DEVICE_ERROR`（父设备状态码）有值，其余为 null —— 前端按 `type` 聚合后靠它下钻。

**索引**：影子 `(serviceId, pollIndex)` 唯一；历史 `(serviceId, pollIndex, field, at)`；
失败记录 `(serviceId, pollIndex, at)`。历史那条复合索引支撑「某点某时间段倒序取 N 条」：
前三个字段等值 + `at` 范围，排序可正可反，不需要额外索引。

## 四、写入顺序：先明细，后影子

```
读影子 → 判定 → 追加明细（历史 / 失败记录） → 更新影子
```

顺序不能反。若先更新影子、明细写失败，那次变化**永久丢失**（下次比较基准已经是新值了）。
反过来写：明细写失败时影子保持旧态 → 下次采集仍判为「变了」→ 自动重试一次即自愈。
失败记录同理：影子留着旧故障，同一条故障下次仍会被重判并重试落库。
`ModbusHistoryService.record` / `recordFailure` 用 `Uni.chain` 表达了这层依赖
（影子的写必须构造在 chain **之内** —— 放外面哪怕 `Uni` 是懒的，也等于把这条约定
寄托在 Mutiny 的懒执行上，读代码的人和测试都看不出来）。

另外三条：

* **明细写失败不能让 invoke 失败**：invoke 已经成功拿到值了，历史只是副作用。
  失败只记日志，响应照旧返回（影子因此没更新，正好走上面那条自愈路径）。
* **失败记录写失败也不能改变对调用方的错误响应**：该报的错原样往下报，
  不能因为「记录失败」而变成另一个错误。同理只记日志。
* **手动 invoke 与轮询走同一个 `record` / `recordFailure`**：否则影子会和实际状态脱节，
  变化判定跟着错。
* **调用路径上不许抛同步异常**（连请求帧的组装都放进 `Uni` 里）：`onFailure().call(...)`
  只拦得住经 `Uni` 流转的失败，同步抛出的会绕过去 —— 那次失败既不进故障记录、
  也不在影子上留故障态。

## 五、读取

三个接口，都在 `/matrix/v1/modbus/history` 之下（另起一段路径而不挂在 `/modbus/device` 下：
服务定义是用户维护的配置，采集数据是设备跑出来的时间序列，生命周期与权限粒度将来可能分开）。
**鉴权与 `invoke` 同口径**：路径上带 `spaceId` 只作鉴权锚点，要求该空间的**成员**；
判定通过后一律按 id 查，不再校验「服务是否属于该空间」。
**时间参数与响应里的时间一律是 epoch 毫秒**（`from` / `to` / `at` / `recordedAt` / `errorAt` / `lastAt`），
省掉两端各写一遍格式解析。

```
GET /current/{spaceId}/{serviceId}
    → { serviceId, polls: [ { pollIndex, fields, recordedAt[, lastError, errorAt] } ] }
GET /registry/range/{spaceId}?serviceId&pollIndex&field&from&to&maxPoints
    → { serviceId, pollIndex, field, from, to, maxPoints, downsampled, total,
        points: [ {at, value[, keepalive]} | {at, until, count, first, last, min, max, avg} ]
        [, carryIn] }
GET /polling/failures/{spaceId}?serviceId&pollIndex&type&from&to&limit
    → { serviceId, from, to, limit, truncated, items: [...], summary: {total, byType, byRemoteCode} }
```

**另有一条独立的「寄存器缓存网格」读投影**（`GET /matrix/v1/modbus/shadows/{spaceId}?serviceIds=a,b,c`，
见 `ModbusShadowResource` / `ModbusShadowService`，路径独立、不挂在 `/modbus/history` 下）：它不读
`service-histories`，而是把 `service-last-polling`（每个方法最后一次成功调用快照）× 点表可读寄存器
**归并成「按寄存器」的网格**，一次返回整张点表的缓存实况，含从没被调用过的可读寄存器（`no-data`）。
与上面的 `GET /current/{spaceId}/{serviceId}`（按方法、只回有快照的方法）互补：要看某个服务**全部可读
寄存器**的当前缓存值，用它。

两件事必须做对，否则数据存了也没法用：

* **carry-in 点**（`carryIn` 键，可能没有）：只有变化点时，一次区间查询（`from` 到 `to`）如果某点最后一次变化在 `from` 之前，
  **必须把 `from` 之前最近的那一条也带回来**，前端才知道 `from` 时刻的值是多少。
  漏了它，曲线从 `from` 开始就是空白 —— 这是变化点存储最经典的第一坑。
  它**不混进 `points`**：它不是这个区间的数据，是区间起点的**状态**（也是第一个桶的 `first`）。
* **服务端降采样**（`maxPoints`，缺省 500、封顶 2000）：前端画 30 天不可能要 50 万个点
  （浏览器画不动、网络也传不动）。样本数不超过 `maxPoints` 时返回**原始点**（`downsampled: false`）；
  超过则按 `(to - from) / maxPoints` 分桶（`downsampled: true`），桶按时间升序。

**一个桶描述的是「这段时间里值是怎么走的」，不是「桶里那几条样本」**：这份数据不是均匀采样序列，
相邻两条样本之间的时间差就是前一条的值持续了多久，所以桶里每个量都按**阶梯函数**算
（每条样本从自己的 `at` 起有效到下一条样本的 `at`，最后一条延到窗口末尾）。
`first` / `last` 是桶起止时刻的值（不是「桶内第一条/最后一条样本」—— 那会把变化提前或推后）；
`count` 是桶内样本条数，只用来决定这个桶出不出点；**空桶不出点**（值没变的时段由前后两点
之间的直线表示，而中间没有样本时那条直线是精确的）。区间是闭的：时刻正好等于 `to` 的样本算在最后一桶里
（持续时间为 0，只进 `last` 与 `min`/`max`，不改变加权均值 —— 否则降采样前后末值对不上）。

由此带出一条报表侧的约束：**均值必须按时间加权**，不能按记录条数平均。
一个点 23 小时是 0、1 小时是 100，按条数平均得 50，按时间加权是 4.17 —— 后者才对。
变化点存储丢掉了 95% 的样本，「有多少条记录」已经不再代表「持续了多久」。
`min` / `max` 与 `avg` 同源（同一段阶梯函数），所以 `min ≤ avg ≤ max` 恒成立，
前端画 min/max 带 + 均值线时线不会穿出带子。
非数值点位（`value-list` 的 description、字符串）只有 `first` / `last`，统计量为 **null**（键仍在）。

**增量口径**（首页「历史数据」卡的 `valueMode: "delta"`，`ModbusRegistryHistoryWindow.ofDeltas`）：
当次读数 − 上次读数。它是**取数层的一个折算分支**，不是区间接口的参数 —— 看板的数据服务
在同一批原始样本上算出增量再走同一套整形（前端在降采样后拿不到原始样本，差值只能在这一侧算）：

- **基准是 carry-in**：carry-in 的值一直保持到窗口内第一条样本，正好是 `from` 时刻的值，
  所以首点增量 `v_first − carryIn` 落在窗口内。没有 carry-in 时首个样本不出点。
  该模式下响应里**不再下发 carryIn**（已经折进首点），否则前端会减第二次。
- **负增量 = 断点**：计数器回零 / 翻转 / 重启时差值为负，这个数不画，但要**保留时刻、值置空**
  （丢掉会让折线把前后两点连起来）。非数值样本同样留一个空点。空点不计入 `sum`。
- **降采样按 `sum` 聚合**：桶里正增量之和，即「这段时间累计增加了多少」；`avg` 照旧是
  「每次采样的平均增量」。**只有增量模式的桶才有 `sum` 键**，普通值模式不下发（一段读数的和没有意义）；
  增量桶的 `first` / `last` 为 null（求和量没有「桶起点的读数」可言）。差值按 9 位小数收掉浮点误差。

**一次区间查询最多取回 20000 条原始样本，超了报错、不静默截断**：截断会把曲线的尾巴悄悄抹掉
（前端看起来一切正常），报错至少能让人把时间范围缩小。降采样在服务端内存里做，
没做成 Mongo 聚合管道 —— 管道里的算子支持（`$floor` / `$isNumber` 之类）只能在线上试错，
而这套东西的正确性恰恰全在「怎么折算」上，所以折算逻辑放在纯函数 `ModbusHistoryWindow` 里，
由单测钉住（端到端那一层要连 Mongo，本机跑不了）。

**故障列表**（`items` 按时间倒序，前端在曲线上打阴影）与历史区间接口同批做。
`summary` 是**这批 `items`** 按 `type`（故障类型分布，取不到分类的老文档算 `UNKNOWN`）与
`remoteCode`（哪几个从站、哪个异常码最多）的聚合，两个数组都按条数降序、条数相同按键升序，
输出稳定、前端不必再排；这正是分类落地成三个字段的目的。
`pollIndex` / `type` 不传即不限；`type` 拼错**报错而不是返回空列表**
（「拼错的分类」和「这段时间没这类故障」在界面上长得一样）；`limit` 缺省 200、封顶 1000，
取到「上限 + 1」条时置 `truncated: true`，与 `total` 一起读。
**当前值接口**带 `lastError` / `errorAt` —— 前端得能显示
「最后已知值 + 它从哪一刻起不再更新」，而不是把陈旧值当最新值画；
没有影子的方法不出现在结果里（那是「从没调用过」，与「有值但很旧」不是一回事，
完整的 methods 清单要读服务定义）。

## 六、保留与清理

**结论：`service-histories` 靠集合级 TTL 索引（`at` 上）保留 90 天，服务端后台删；
故障记录与当前值不设。** 声明式、零运维，没有批删任务。

* **机制**：`at` 上一条单字段 TTL 索引 `createIndex({at:1}, {expireAfterSeconds: N})`，
  `N = modbus.history.retention-days × 86400`，缺省 **90 天**。
  每次启动对一遍：没有就建、秒数变了就（先 `dropIndex` 再建）、`retention-days <= 0` 就把已有的删掉。
  改动 TTL 在原版 MongoDB 里是 `collMod` 的活，托管服务的命令支持面窄，故走重建这条路
  （判定在纯函数 `ModbusHistoryRetention.decide`，四种动作各有一条单测；秒数一致时什么都不做，
  否则每次重启都会重建索引）。
* **为什么用集合级 TTL 而不是文档级 `ttl` 字段**：这份明细 append-only，`at` = 采样时刻 ≈ 写入时刻，
  一条索引就表达了「保留 N 天」；文档级要多一个字段、多一处实体改动，
  而「同一集合里不同文档不同保留期」这个需求并不存在。
* **为什么故障记录不设 TTL**：`service-failures` 的 `at` 是「故障**首次**出现时刻」且同一故障只落一条 ——
  挂上 TTL 会让「还在持续的老故障」那条记录先消失，曲线上的故障阴影就断了。
  它去重后行数很少，本来也不是容量问题。
* **为什么当前值不设**：`service-last-polling` 是每字段一条的当前状态，删了就没了（不是历史）。
* **删除是服务端后台行为**：MongoDB 系的 TTL 监视器约每分钟跑一趟，删除**可能滞后**，
  不是到点即删；不占应用侧资源。
* **保留策略必须一开始就有**，否则数据只进不出，存储费无声地涨。
* 服务/设备被删除时 **级联删掉该服务的影子、历史、失败记录与告警**（影子是派生状态，
  历史和失败记录也不再有消费者 —— 服务没了，前端就没有入口能查到这些点位；
  告警同理，而且它比前两者更绝对：一条没人能处理的告警没有任何意义，见 `Modbus-Alarm.md` 第五节末）。
  四处都在 `ModbusHistoryService.purge(...)` 的同一个 `Uni.combine()` 里。
  要改成「删服务但保留历史」，改 `FieldDeviceService` 里那两处级联调用即可。

### TTL 在本部署上已实证可用（2026-09-13，staging）

staging / prod 跑的是 **Azure DocumentDB**（原 Cosmos DB for MongoDB vCore，资源类型
`Microsoft.DocumentDB/mongoClusters`）—— 它是建在 **PostgreSQL** 上的开源 DocumentDB 引擎，
**不是 MongoDB 本体**，而官方兼容性清单里 TTL **既没列为不支持、也没写明支持**，
所以当时的结论是「只能实测」。实测已做（staging `openxiot-staging`，serverVersion 8.0.0）：

| 步骤 | 结果 |
|---|---|
| 临时集合 `modbus/ttl-smoke` 上 `createIndex({at:1}, {expireAfterSeconds:60})` | 建成功；索引文档 `{"key":{"at":1},"name":"at_1_ttl_smoke","expireAfterSeconds":60}` |
| 插一条 `at` = 2 分钟前的文档 | 写入后 `count=1` |
| 等后台删 | `t+15s count=1` → **`t+30s count=0`** —— 服务端删了，比原版 MongoDB 的「约每分钟一趟」还快 |
| 清理 | 临时集合已 drop（`modbus` 库里只剩 `configs` / `services`） |

顺带确认两件与落地相关的事：**`modbus.service-histories` 目前还不存在**（`modbus` 库里只有
`configs` 与 `services`），所以（a）不存在「既有 `{at:1}` 普通索引」与我们这条 TTL 索引撞 key 的问题
（撞了会 `IndexOptionsConflict`、天数改了不生效）；（b）90 天保留**不会删掉任何现有数据** ——
这个集合正好是第一份样本落库时才建出来的。批删任务（原计划的兜底）**不需要做**。

## 七、选型结论：留在 Cosmos，不引入第二个存储

落库规则生效后（按 5% 落库率、其余不变）：

| 采集写入量 | 优化前月 RU 费 | 优化后 | 月存储（保留 30 天） |
|---|---|---|---|
| 100 条/s | ~$30–60 | ~$2–3 | <$1 |
| 1000 条/s | ~$300–600 | ~$15–30 | ~$1.5 |

写入量降到 5% 之后，Cosmos 再也没有撑不住的理由 —— **Azure Table Storage / Blob / ADX
那条分支可以取消**。省下的不只是钱，是「第二个存储的 SDK、密钥、数据模型、查询路径、
备份策略」这一整套；对一个消费者只是前端的场景，这才是大头。

判据留在这里，量级变了再回头看：

* 采集写入量 **< 500 条/s**（落库后）→ Cosmos，别折腾。
* 超过 → 把**原始明细**换到 Azure Table Storage（同一个存储账号已经在用），
  影子与聚合留 Cosmos。分区键 `serviceId`、行键倒序时间戳、**同分区批量写 ≤100 条算一次事务**。
* 上到 ADX 只在「历史变成平台级能力（多协议、多服务都要写、要自由聚合）」时才需要考虑，
  而那样也是「明细落 Blob（Parquet）+ ADX 挂外部表」的形态。**现在不做**，
  但要保证数据格式搬得动：字段扁平、时间是标准类型、一条记录自带归属三元组 —— 满足这三条就够了。

## 八、配置项

| 配置 | 缺省 | 说明 |
|---|---|---|
| `modbus.history.deadband` | `0` | 死区（仅对数值生效）；`0` = 精确比较 |
| `modbus.history.keep-alive-seconds` | `600` | 保活打点周期；`<= 0` = 关闭 |
| `modbus.history.retention-days` | `90` | 明细保留天数；`> 0` 建 `at` 上的 TTL 索引，`<= 0` = 关（并删掉已有的索引）。**缺省即生效**，只作用于 `service-histories` |
| `modbus.index.enabled` | `true` | 建索引开关，与点表/服务共用；关掉时保留策略也不建 |

**按字段配死区**（每个点位一个死区值）是后续的增强：需要在 `ModbusResponseField` 上加字段，
现阶段先用全局缺省。

## 九、变更记录

### 2026-10-22

* **「当前值」改名并对齐语义**：`ModbusServiceShadow` → `ModbusServiceLastPolling`（`{@code fields}` 等字段
  不变）——它存的其实是「某个读方法**最后一次被调用**的快照」，叫"影子"名不副实。集合
  `service-shadows` → `service-last-polling`，**直接改名、不做数据迁移**（dev/staging 上旧的
  `service-shadows` 成孤儿不再读，下次轮询自动重建）。
* **新增独立的寄存器缓存网格读投影**：`ModbusShadowResource` / `ModbusShadowService`
  （`GET /modbus/shadows/{spaceId}?serviceIds=a,b,c`，见第五节末）——把 `service-last-polling` ×
  点表可读寄存器归并成按寄存器的网格，与 per-method 的 `GET /current/{spaceId}/{serviceId}` 互补。

### 2026-09-13

* **保留策略落地（第六节）**：`service-histories` 在 `at` 上挂集合级 TTL 索引，
  保留 **90 天**（`modbus.history.retention-days`，缺省即生效，`<= 0` = 关）；
  故障记录与当前值不设。启动时对一遍：没有就建、秒数变了就 drop 再建、关了就把索引删掉 ——
  判定集中在纯函数 `ModbusHistoryRetention.decide`（四种动作各一条单测），
  仓储只照做，失败留告警（保留没生效意味着历史永远不会被删，不能静默）。
* **口径更正**：本部署是 **Azure DocumentDB**（原 Cosmos DB for MongoDB vCore，
  `Microsoft.DocumentDB/mongoClusters`），按集群规格计费、**不按操作计 RU**，
  分片由服务托管 —— 第一节与第七节的 RU 数字都不适用（第一节已加更正说明），
  「建集合时指定分区键」在本部署里也不再是一个待办。TTL 在 DocumentDB 上是否真的会删文档
  **尚未实证**，留了一条 staging 冒烟项（`Modbus-History-TODO.md` 第四步）。
* `interval` 下限 3 → 5 秒（`ModbusServiceValidator` 与前端编辑器常量，
  `MODBUS.md` 第七节文案同步）。
* 初版：确定「落库规则（变位 / 死区 / 保活打点）+ 影子作基准 + 先历史后影子」的形态。
* 落地 `ModbusServiceLastPolling` / `ModbusServiceHistory` / `ModbusHistoryPolicy` /
  `ModbusHistoryService`，并接进 `ModbusServiceService.invoke`（手动调用即开始记录）。
* 加执行失败记录：`ModbusServiceFailure`，同一个故障只落一条；故障态记在影子上
  （`lastError` / `errorAt`），恢复后的第一次成功强制落全字段。
  invoke 的失败路径接上记录，但**不改写对调用方的错误响应**。
* 失败原因细化为 `type` + `remoteCode` + `message`：加 `ModbusFailureType`（10 类，
  按设备侧 / 报文侧 / 定义侧 / 平台侧分组）与 `ModbusInvokeException`，
  在解析器与调用路径的每个失败点打上分类 —— 前端按类型聚合，从文本反推类型这条路不走。
  顺手修掉一处：请求帧组装原本是同步的，异常会绕过 `onFailure().call(...)` 而不被记录。
* 读取接口落地：`ModbusHistoryResource`（当前值 / 区间曲线 / 故障列表三个接口）、
  `ModbusHistoryQueryService`（参数校验 + 20000 条上限报错而非截断）、
  `ModbusHistoryWindow`（carry-in 与降采样的纯函数，桶的四个量都按阶梯函数算）、
  读侧三个 Codec 与两个仓储查询方法。
* 轮询器（执行侧）的设计写在 `Modbus-Service-Poller.md`，落地清单见 `Modbus-History-TODO.md` 第三步。
