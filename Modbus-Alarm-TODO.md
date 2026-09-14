# Modbus 阈值告警：实施计划

设计见 `Modbus-Alarm.md`。图例：`[x]` 已完成，`[ ]` 待办。**最险的先列。**

## 第 0 步：Azure DocumentDB 上验「唯一部分索引」（**已完成：成立**）

2026-09-14 在 staging 上跑完，台账（四条探针、索引文档形态、捞到的那条纪律）记进
`Modbus-Alarm.md` 第七节。用的是 mongosh + staging 连接串。

- [x] 在 staging 临时集合 `modbus/alarm-smoke` 建 `{openKey: 1}` unique +
      `partialFilterExpression: {openKey: {$exists: true}}`。落成
      `{"v":2,"key":{"openKey":1},"name":"openKey_unique_partial",
      "partialFilterExpression":{"openKey":{"$exists":true}},"unique":true,
      "storageEngine":{"enableOrderedIndex":true}}`，**没抛错、也没被静默吞掉**。
- [x] 插两条**同 `openKey`** 的文档 ⇒ 第二条报 `11000 Duplicate key violation`（连插两次都挡住，行数 1）。
- [x] 插两条**都没有 `openKey`** 的文档 ⇒ **两条都成功**。
      这一步才证明「部分」；普通唯一索引会把第二条的 null 当重复拒掉。
- [x] 另插两条**显式 `openKey: null`** 的 ⇒ 第二条也被拒。
      **这是一条新纪律**：关闭只能 `$unset`，不能 `$set openKey: null`（已写进 `closeUpdate` 注释）。
- [x] drop 临时集合（`modbus` 库前后集合清单一致，没碰任何真实集合），结果记进 `Modbus-Alarm.md` 第七节。
- [x] 结论成立 ⇒ **1 号索引已补进 `ensureIndexes()`**（回退档 F1 / F2 未启用，留档备查）。

> 失败的判定路径没有走过，但留着：真不成立时走 F1（普通唯一复合索引
> `{serviceId, functionIndex, field, recoveredAt}`）或 F2（`updateOne` upsert + `updateMany` 兜底，
> 并把竞态写进文档）—— **不静默降级**。

## 第一步：配置模型与校验（已完成）

- [x] `ModbusServiceFieldAlarm`：`enabled` / `compare` / `threshold` / `state` / `level` / `text`。
- [x] 挂在**两处**：`ModbusServiceField.alarm` 与 `ModbusServiceFieldBit.alarm`
      —— 位是独立的结果键（parser 逐位 `values.put(bit.field, …)`），只挂字段够不着「位 = 1 告警」。
- [x] `ModbusServiceFieldAlarmCodec` + 接进 `ModbusServiceFieldCodec` / `ModbusServiceFieldBitCodec`；
      守「null 不出键」，空对象读作「没配」。
- [x] `ModbusAlarmPolicy`：`OPERATORS` / `LEVELS` 白名单（唯一一份）、`hasEnabledAlarm`（免费闸门）、
      `hit`（三态 `HIT`/`MISS`/`SKIP`）、`decide`（开 / 按值关 / 按定义关）。
- [x] `ModbusServiceValidator.validateAlarm`：只在 `enabled == true` 时校验；只许读方法；
      `string` 字段不收；`value-list` 字段只许 `=` 比 `state`；位只许 `=` 比 0/1。
- [x] `isReadFunction` 抽取：周期与告警共用同一处功能码判断。
- [x] 单测：`ModbusAlarmPolicyTest`（14）+ `ModbusServiceValidatorTest` 补 9 +
      `ModbusServiceCodecTest` 收进示例体。全量 432 例中 431 绿
      （`SpaceResourceTest` 是 `@QuarkusTest`、要连 Mongo，本机读超时，与本次改动无关）。
- [x] `MODBUS.md` 三 / 七 / 八节 + 本文件 + `Modbus-Alarm.md`。

## 第二步：告警文档与仓储（已完成）

- [x] `db/modbus/alarm/ModbusServiceAlarm`：含触发那一刻的**定义快照**（`compare`/`threshold`/`state`/
      `level`/`text`/`unit`）—— 定义是活的、行是不可变的历史，只存引用会让改阈值悄悄改写历史告警的含义。
      另有 `CLOSE_VALUE` / `CLOSE_DEFINITION` 两个关闭方式常量与 `openKeyOf(...)`。
- [x] `ModbusServiceAlarmRepository`（照 `ModbusServiceFailureRepository`）：索引（**只建了两个**，
      见下）、`insertOpen`、`listOpenFields`、`closeByKeys`（`updateMany`）、`handle`（CAS，
      **绝不 `replaceOne`**）、`deleteByServiceIds`、`findRange` + `RangeFilter`。
- [x] `db/modbus/alarm/codec/ModbusAlarmCodec`：**只出不进**，`id` = `_id.toHexString()`，
      `openKey` **绝不下发**，时刻一律 epoch 毫秒，`handledBy` 走既有 `PersonCodec`；
      `encodeSummary` 出 `{total, open, unhandled, byLevel, byText}`。
- [x] 单测：`ModbusAlarmCodecTest`（9 例：`null 不出键`、`openKey` 不出现、epoch 毫秒、
      `=` 的取值表状态、汇总排序与归桶）+ `ModbusServiceAlarmRepositoryTest`（8 例：三种异常形状、
      非 11000 照常外传、cause 链包装、自引用终止、超长链有界）。
- [x] 唯一部分索引第 0 步冒烟通过后已补进 `ensureIndexes()`（现在 1、2、3 三个都建）。
      「先不建」的两条理由与实测台账见 `Modbus-Alarm.md` 第七节。
- [x] 全量 449 例中 448 绿（唯一红色仍是 `SpaceResourceTest` 那个要连 Mongo 的环境性失败）。

## 第三步：评估接线进采集链路（已完成）

- [x] `ModbusAlarmService.evaluate`：第一层闸门 `hasEnabledAlarm` 不过就**一次库操作都不做**；
      过了才读一次 `listOpenFields`（带索引、只投影 `field`）→ `decide` → 执行三件事
      （`insertOpen` / `closeByKeys`）。
- [x] 接进 `ModbusServiceService.execute`，与 `recordHistory` **并列**（不是串进成功路径）：

      .chain(values -> recordHistory(service, function, values, baselines)
              .chain(ignored -> evaluateAlarms(service, function, values))
              .replaceWith(values));

- [x] 与 `recordHistory` 同壳：失败只记日志、`recoverWithNull`，
      **既不影响 invoke 的成功响应、也不改写失败响应**。
- [x] **重复键（11000）必须吞掉**：吞掉之后不变式「该键有一条开着的告警」恰好成立。
      （实现在第二步的 `insertOpen`；本步只是它的调用方。）
- [x] **`SKIP` 的日志去重**：进程内按开行标识记，上限 256、记满清空重来（理由见 `Modbus-Alarm.md` 第四节）。
- [x] 单测（Mockito，不连库）：`ModbusAlarmServiceTest` 13 例 —— 没开告警 ⇒ `verifyNoInteractions(alarmRepo)`；
      稳态**零写**；行为快照（定义快照 / 单位 / 位不带单位）；两种 `closeType`；一次采样里既开又关；
      判不了只警告一次且不写库；警告内容带上原始值与完整定义；去重上限会清空重来；
      读失败与写失败都**照常外传**；关掉最后一个告警时闸门照常跳过（有意为之，见 `Modbus-Alarm.md` 第五节末）。
- [x] `ModbusServiceServiceTest` 补 2 例：`evaluate` 在 `record` **之后**且拿到**同一份**采样（`InOrder`）；
      告警评估失败不改写已成功采集的响应、也不变成一条故障记录。
- [x] 全量 469 例中 468 绿（唯一红色仍是 `SpaceResourceTest` 那个要连 Mongo 的环境性失败）。

**两处口径从 mock 测试挪到了别处，别以为漏了：**

* 「关闭是**按 key 的 `updateMany`**、不是定位某条 `_id`」——mock 观察不到 `Bson` 形状，
  改由 `ModbusServiceAlarmRepositoryTest.closingTargetsTheWholeOpenKeyNotOneRow` 断言过滤条件
  （第二步已落地）。这条写错了不会报错，只会「关不掉」，比报错的写难查得多。
* 「重复键被吞掉」的**识别**那一半由 `ModbusServiceAlarmRepositoryTest` 钉住（三种异常形状）；
  **吞**的那一半（`insertOpen` 把 11000 变成 `false`）要连库才能触发，
  归第七步的端到端验收。

## 第四步：定义变更对账与删除级联（已完成）

- [x] `decide` 里的 `DEFINITION` 关闭（**自愈主路径**，已在第一步实现并测过）。
- [x] 拾遗：覆盖「改了定义后这个方法再也不采」的残留。
- [x] 删除级联：`ModbusHistoryService.purge(...)` 里加第四个 `Uni` 删告警 ——
      服务整个没了时走**真删**而不是按 `DEFINITION` 关掉（理由见 `Modbus-Alarm.md` 第五节末）。
- [x] 启动扫一次残留。
- [x] 单测：`ModbusAlarmPlanTest`（15，纯函数）+ `ModbusAlarmReconcilerTest`（14，Mockito 不连库）。
- [x] 全量 498 例中 497 绿（唯一红色仍是 `SpaceResourceTest` 那个要连 Mongo 的环境性失败）。
- [x] `Modbus-Alarm.md` 第五 / 八 / 十一 / 十三节 + `MODBUS.md` 变更记录 + `Modbus-History.md` 的
      `purge` 清单。

**一处有意偏离上面的措辞，别以为是漏了：**

* 拾遗做在**新建的 `ModbusAlarmReconciler`** 里，**没有**挂进 `ModbusPollReconciler`。
  理由：轮询任务的对账挂在 `ModbusPollScheduler` 的启动事件上，而那个事件只在
  `modbus.poll.enabled=true` 时才起；告警判定挂在**采集链路**上、手动 invoke 也会评 ——
  关掉轮询开关不该让残留从此没人扫。两个时机（定义变更 / 启动）共用同一个实现，
  判据是纯函数 `ModbusAlarmPlan`。

**一处口径的代价，明说：**

* `ModbusAlarmPlan` 对**不认识的服务**一律不动，所以启动那趟**不会**清理「服务已被删除、
  级联删除又崩在中途」留下的开行。不为此松开那条保守口径（它的正面价值是拦住「调用方传半份
  服务列表」时的误关 —— 那会把别的服务的告警一次关一片）。这与影子 / 历史 / 故障记录是同一个
  失败模式：级联失败只记日志、不重试，留下的是存储垃圾，且页面按空间子树查、看不见它。
  `ModbusAlarmReconcilerTest.startupSweepLeavesRowsOfAServiceThatNoLongerExistsAlone` 钉住这个分工。

## 第五步：查询服务与接口（已完成）

- [x] `ModbusAlarmQueryService`：`limit+1` / `at` 倒序 / `truncated`；空间级照搬「逐服务一条带索引的
      查询 + 内存归并」，`serviceId == null` ⇒ `subtreeServiceIds(spaceId)`。
      单服务那条路**只是批量那条路的一个元素**（`alarms(...)` → `alarmsOfServices(List.of(id), …)`）：
      排序与截断的口径只有一份，日后不会改漏一边。参数校验也在这里（不是 Resource）——它是能被单测覆盖的部分。
- [x] `ModbusAlarmResource`：`/many/{spaceId}` + `/handle/{spaceId}/{id}`，逐行照
      `ModbusHistoryResource.failures(...)` 的结构（`requireSpaceMember` / `OxResponse.ok` / 内联错误）。
- [x] `handle` 校验该告警落在传入空间的子树内 —— 否则拿别人的 id 就能点。
      判法是「先 `findById` 拿行 → 该行的 `serviceId` 在 `subtreeServiceIds(spaceId)` 里吗 → 才写」，
      用的是与列表查询**同一个**函数，于是「列表里看得到的就能点」恰好成立。
- [x] `subtreeServiceIds` 从 `ModbusHistoryResource` 下沉到 `ModbusServiceService`（两个资源共用）；
      选的是**下沉**不是复制（`SpaceService` 只依赖仓储与账号查询，注入进去不成环）。
- [x] 单测：`ModbusAlarmQueryServiceTest`（7，对齐 `ModbusHistoryQueryServiceTest`）+
      `ModbusAlarmServiceTest` 补 6 例（`handle`）。

**两处实现时定下的口径，值得记一笔：**

* **`handle` 里「已经处理过了」算成功，不算错**：CAS 改动 0 时**再读一次**，行在就照常返回它
  （并记一条日志说明是重复点击）、行不在了才 not found。报错会让页面为一件恰好如愿的事弹红字。
  读那一次就是为了分辨这两种解释 —— 与 `SpaceService` 里「删除 0 条按 not found 失败」不同：
  那里 0 条只有一种解释。
* **`/handle` 的参数里，非法 id 必须在进库之前先挡**（`ObjectId.isValid(id)`）：Panache 的
  `findById(String)` 在构造 `ObjectId` 时就抛，而那是个普通 `RuntimeException`，
  落不到 `.onFailure(IllegalArgumentException.class)` 那个兜底里（先例：`AccountUserLookup`、
  `ModbusPollReconciler`）。

## 第六步：前端（已完成）

代码在 `webapp-matrix`，0.9.2 → 0.9.6（`7973e80` `5602cea` `813f65e` `66809e3` `6d7307f`）。

- [x] 类型与编解码镜像：`ModbusServiceFieldAlarm` + 两处 `alarm?` + `ModbusServiceFieldAlarmCodec`；
      `ModbusAlarm.ts`（含 `MODBUS_ALARM_LEVELS` / `MODBUS_ALARM_LEVEL_LABELS` /
      `MODBUS_ALARM_OPERATOR_LABELS` / `modbusAlarmCondition(alarm, translate)` /
      `modbusAlarmOperatorLabel` / `modbusAlarmLevelLabel`，**translate 由调用方传入**，
      与 `modbusFailureLabel` 同契约）。
      **偏离**：计划里写的是一份 `MODBUS_ALARM_LABELS`，实做分成了级别与比较方式两份 ——
      两者查的表、兜底值、用在的列都不同，合成一份要按前缀分派。
- [x] `modbus.service.ts`：`getAlarms(...)` / `handleAlarm(spaceId, id)`。
- [x] 编辑器逐字段配置：照抄轮询那套「侧表 + 签名」——
      `alarms` 是 `Map`，键 `` `${configId}#${functionIndex}#${fieldKey}` ``（**`configId` 前缀**：
      换源点表不能把告警带到同序号的方法/字段上）、`withAlarms(func)` 在保存时并回去、
      `seedAlarms` 装载、`alarmSignature` 排序序列化。
- [x] **`ServiceBaseline` 加了 `alarms: string` 且 `serviceChanged` 比它** ——
      不加的话**只改告警会让「保存」按钮不亮**，用户以为改动被无声丢掉了。
- [x] `describeServiceField` 末尾带上已启用的告警（如 `… → 温度过高(>80)`），那一行摘要才不说谎。
      又**拆出 `describeFieldType`** 给展开行的「类型」列：那一列右边紧挨着就是告警控件，
      再说一遍是同一句话说两遍，而且会随用户敲字实时变。
- [x] `/main/alarm` 列表页。
      * 表头卡按实做的三格是「告警清单（条数）/ 未处理 / 未恢复」，**不是**计划里写的「今日告警」——
        窗口由筛选条决定，写死「今日」在选了「最近 7 天」时就是假话；也**没有**挪用看板那两个键
        （`今日告警` / `告警类型分布` 是 dashboard 伪造数据在用的）。
      * 按级别与按告警文本的两份分布（`byLevel` / `byText`）放在卡片里，级别那份走 `levelLabel`。
      * **筛选下拉不设「全部」那一项**：ng-zorro 的 `writeValue` 里 `covertModelToList` 把 `null`
        变成 `[]`，值为 null 的选项不显示标签、只显示占位符 —— 那样下拉看着是「未选择」而语义是
        「全部」，是在骗人。改用 `nzAllowClear` + `nzPlaceHolder` 表达「不限」。
      * **服务端/用户文本一律不翻译**（告警文本是用户自己敲的）；表格**不写 `nzWidth`**。
- [x] 词典追加 29 个新键 × 66 份（388 → 417），追加后**前 388 键与追加前逐位相同**（已用脚本与
      HEAD 逐位比对）。键清单是**反查出来的**：从两个 HTML 抽 `'X' | translate`、两个 TS 抽
      `instant('X')` / `t('X')`、再从 `ModbusAlarm.ts` 的两份标签映射抽值，与词典求差 ——
      于是发现方案列过的 `汇总` 与 `告警配置` 实测**没有任何页面用到**，未加。
- [x] 改掉那条会变成假话的旧提示：`方法由源点表生成（一个功能码动作 = 一个方法），除自动轮询与调用周期外均只读`
      —— 66 份里原地替换 key 字符串（位置不动）。
      **偏离**：译文尾巴也一并改写了，不是「保留各语言译文」。原话不成立正是因为多了一件事，
      译文不提这件事就还是假话；这是本次唯一一处重写既有译文的键。
- [x] `AGENTS.md`（前端）的 i18n 一节补上「告警的 `text` 是用户自己敲进去的一句话，翻译它就是在改用户的输入」。

## 第七步：端到端验收（需连接串）

- [ ] 造一个配了告警的服务 → 手动 `invoke` 一次越限值 → `/alarm/many/{spaceId}` 应有一条未处理、未恢复。
- [ ] **再 invoke 一次同样的越限值 → 仍然只有一条**（本特性最核心的断言）。
- [ ] invoke 一个正常值 → 该条 `recoveredAt` 有值、`handled` 仍为 `false`。
- [ ] 调 `handle` → `handled=true`、`handledBy` 是当前账号。
- [ ] 再越限一次 → 出现**第二条**。
- [ ] 把该字段的告警关掉（或删掉该字段）保存 → 若此时有开着的告警，它应被 `DEFINITION` 关闭。
