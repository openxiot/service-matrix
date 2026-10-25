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
> `{serviceId, pollIndex, field, recoveredAt}`）或 F2（`updateOne` upsert + `updateMany` 兜底，
> 并把竞态写进文档）—— **不静默降级**。

## 第一步：配置模型与校验（已完成）

> 这一步的「一个出值一条规则」已被**第 4.5 步**改成「一个出值**一组**规则」；
> 下面的记录保持当时的样子，现行口径看第 4.5 步与 `Modbus-Alarm.md`。

- [x] `ModbusResponseFieldAlarm`：`enabled` / `compare` / `threshold` / `state` / `level` / `text`。
- [x] 挂在**两处**：`ModbusResponseField.alarm` 与 `ModbusResponseFieldBit.alarm`
      —— 位是独立的结果键（parser 逐位 `values.put(bit.field, …)`），只挂字段够不着「位 = 1 告警」。
- [x] `ModbusResponseFieldAlarmCodec` + 接进 `ModbusResponseFieldCodec` / `ModbusResponseFieldBitCodec`；
  守「null 不出键」，空对象读作「没配」。
- [x] `ModbusAlarmPolicy`：`OPERATORS` / `LEVELS` 白名单（唯一一份）、`hasEnabledAlarm`（免费闸门）、
      `hit`（三态 `HIT`/`MISS`/`SKIP`）、`decide`（开 / 按值关 / 按定义关）。
- [x] `ModbusDeviceValidator.validateAlarm`：只在 `enabled == true` 时校验；只许读方法；
      `string` 字段不收；`value-list` 字段只许 `=` 比 `state`；位只许 `=` 比 0/1。
- [x] `isReadPoll` 抽取：周期与告警共用同一处功能码判断。
- [x] 单测：`ModbusAlarmPolicyTest`（14）+ `ModbusDeviceValidatorTest` 补 9 +
      `ModbusDeviceCodecTest` 收进示例体。全量 432 例中 431 绿
      （`SpaceResourceTest` 是 `@QuarkusTest`、要连 Mongo，本机读超时，与本次改动无关）。
- [x] `MODBUS.md` 三 / 七 / 八节 + 本文件 + `Modbus-Alarm.md`。

## 第二步：告警文档与仓储（已完成）

- [x] `db/modbus/alarm/ModbusServiceAlarm`：含触发那一刻的**定义快照**（`compare`/`threshold`/`state`/
      `level`/`text`/`unit`）—— 定义是活的、行是不可变的历史，只存引用会让改阈值悄悄改写历史告警的含义。
      另有 `CLOSE_VALUE` / `CLOSE_DEFINITION` 两个关闭方式常量与 `openKeyOf(...)`。
- [x] `ModbusAlarmRepository`（照 `ModbusServiceFailureRepository`）：索引（ **只建了两个**，
  见下）、`insertOpen`、`listOpenFields`、`closeByKeys`（`updateMany`）、`handle`（CAS，
  **绝不 `replaceOne`**）、`deleteByServiceIds`、`findRange` + `RangeFilter`。
- [x] `db/modbus/alarm/codec/ModbusAlarmCodec`：**只出不进**，`id` = `_id.toHexString()`，
      `openKey` **绝不下发**，时刻一律 epoch 毫秒，`handledBy` 走既有 `PersonCodec`；
      `encodeSummary` 出 `{total, open, unhandled, byLevel, byText}`。
- [x] 单测：`ModbusAlarmCodecTest`（9 例：`null 不出键`、`openKey` 不出现、epoch 毫秒、
      `=` 的取值表状态、汇总排序与归桶）+ `ModbusAlarmRepositoryTest`（8 例：三种异常形状、
  非 11000 照常外传、cause 链包装、自引用终止、超长链有界）。
- [x] 唯一部分索引第 0 步冒烟通过后已补进 `ensureIndexes()`（现在 1、2、3 三个都建）。
      「先不建」的两条理由与实测台账见 `Modbus-Alarm.md` 第七节。
- [x] 全量 449 例中 448 绿（唯一红色仍是 `SpaceResourceTest` 那个要连 Mongo 的环境性失败）。

## 第三步：评估接线进采集链路（已完成）

- [x] `ModbusAlarmService.evaluate`：第一层闸门 `hasEnabledAlarm` 不过就**一次库操作都不做**；
      过了才读一次 `listOpenFields`（带索引、只投影 `field`）→ `decide` → 执行三件事
      （`insertOpen` / `closeByKeys`）。
- [x] 接进 `ModbusDeviceService.execute`，与 `recordHistory` **并列**（不是串进成功路径）：

      .chain(values -> recordHistory(service, poll, values, baselines)
              .chain(ignored -> evaluateAlarms(service, poll, values))
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
- [x] `ModbusDeviceServiceTest` 补 2 例：`evaluate` 在 `record` **之后**且拿到**同一份**采样（`InOrder`）；
      告警评估失败不改写已成功采集的响应、也不变成一条故障记录。
- [x] 全量 469 例中 468 绿（唯一红色仍是 `SpaceResourceTest` 那个要连 Mongo 的环境性失败）。

**两处口径从 mock 测试挪到了别处，别以为漏了：**

* 「关闭是**按 key 的 `updateMany`**、不是定位某条 `_id`」——mock 观察不到 `Bson` 形状，
  改由 `ModbusServiceAlarmRepositoryTest.closingTargetsTheWholeOpenKeyNotOneRow` 断言过滤条件
  （第二步已落地）。这条写错了不会报错，只会「关不掉」，比报错的写难查得多。
* 「重复键被吞掉」的 **识别**那一半由 `ModbusAlarmRepositoryTest` 钉住（三种异常形状）；
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
- [x] `subtreeServiceIds` 从 `ModbusHistoryResource` 下沉到 `ModbusDeviceService`（两个资源共用）；
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

- [x] 类型与编解码镜像：`ModbusResponseFieldAlarm` + 两处 `alarm?` + `ModbusResponseFieldAlarmCodec`；
      `ModbusAlarm.ts`（含 `MODBUS_ALARM_LEVELS` / `MODBUS_ALARM_LEVEL_LABELS` /
      `MODBUS_ALARM_OPERATOR_LABELS` / `modbusAlarmCondition(alarm, translate)` /
      `modbusAlarmOperatorLabel` / `modbusAlarmLevelLabel`， **translate 由调用方传入**，
  与 `modbusFailureLabel` 同契约）。
  **偏离**：计划里写的是一份 `MODBUS_ALARM_LABELS`，实做分成了级别与比较方式两份 ——
  两者查的表、兜底值、用在的列都不同，合成一份要按前缀分派。
- [x] `modbus.service.ts`：`getAlarms(...)` / `handleAlarm(spaceId, id)`。
- [x] 编辑器逐字段配置：照抄轮询那套「侧表 + 签名」——
      `alarms` 是 `Map`，键 `` `${configId}#${pollIndex}#${fieldKey}` ``（**`configId` 前缀**：
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

## 第 4.5 步：一个出值一组规则（已完成）

做完第四步、第五步之后插入的一步：把「一个出值只能配一条规则」改成**一组**（分级配置）。
集合一行没落过、也没有存量配置带过 `alarm` 键，所以这是零成本窗口期的最后一刻。
完整口径见 `Modbus-Alarm.md` 第十三节第一条，这里只列交付物。

- [x] `ModbusResponseFieldAlarm` 加 `id`（前端生成、后端校验：非空 / ≤64 / 同出值内唯一；
  **只在 `enabled == true` 时校验**，照抄「半配置合法」）。 **条数上限 8 条/出值**。
- [x] `alarm` → `alarms` 两处（字段与位），两个父 Codec 跟着改；空数组 / 「元素全是空对象」
      都读作**没配**，库里不留 `alarms: []` 也不留空壳规则。
- [x] `ModbusAlarmPolicy`：`LEVEL_ORDER` / `rank` / 选举（只留级别最高的一条；同级取声明顺序
      靠后的；命中优先于判不了）；`SKIP` 日志去重键细到 `ruleId`。
- [x] `ModbusAlarm` 加 `ruleId`（ **不下发**）与 `CLOSE_SUPERSEDED`；
  **`openKey` 与那三个索引一个字没改** —— 「一个出值同时最多一条开着」由唯一部分索引强制。
- [x] `ModbusAlarmService.apply` 改**两段写序（先关后插）**，并用 `InOrder` 钉住：
      `Uni.combine().all()` 是并发的，顺序只能由两段 `chain` 保证 —— 反了会撞唯一索引、
      被 `insertOpen` 静默吞掉，那个出值就一条开着的都没有。
- [x] `ModbusAlarmPlan` 判据细到规则：「该出值上还有没有一条 `id` 等于行上 `ruleId`、
      且启用着的规则」；**行上没有 `ruleId` 的一律不动**（关掉是不可逆的那个动作，
      采集侧的胜者比对会按 `SUPERSEDED` 接手它）。
- [x] 单测：Policy 16 → 26、Service 19 → 22、Plan 15 → 17、Reconciler 14（夹具跟模型改）、
      Validator 告警段 10 → 15、Codec 数组往返。
- [x] 文档：`Modbus-Alarm.md` 第一~六、八~十一、十三节 + `MODBUS.md` 第三、七、八节。

**前端**（`webapp-matrix`，0.9.7 → 0.9.8：`5331d5a` 代码、`f66e74a` 词典）：

- [x] `ModbusResponseFieldAlarm` 加 `id`；两处 `alarm` → `alarms`；
      `ModbusResponseFieldAlarmCodec` 的单对象 `decode`/`encode` 变成一组的
      `decodeList`/`encodeList`（空数组与「元素全是空壳」都读作没配、`!= null` 判值 ——
  两条都与后端同口径，过一趟前端不会把服务定义改了形）。
- [x] 编辑器展开区的**行单位从「出值」变成「出值 + 规则」**：组内一条规则一行，出值名与类型
      只在组首行显示、后续行留空并缩进（照 `.service-alarm-nested` 的老例）；组尾一行
      「添加规则」，到 `MAX_ALARM_RULES = 8` 时禁用（与后端上限同口径，**不是**安全边界）。
      六个 handler 各多带一个 `rule` 形参，`patchAlarm` / `onAlarmRemove` 用**对象身份**
      （`indexOf`）定位而不是数组下标；`seedAlarms` 在**取基线之前**补 `id` ——
      补晚一步保存按钮一进页面就亮着。
- [x] `alarmSignature` 按**数组原序**摊平：声明顺序参与运行期的同级裁决，重排是一次真改动。
- [x] `describeServiceField` 只缀**级别最高**的那条启用规则（与运行期「只留最严重」同口径），
      不缀条数（明细展开就见）。
- [x] `/main/alarm` 的 `closeType`：条件从 `=== 'DEFINITION'` 放开成「有就露」，正文走新的
      `MODBUS_ALARM_CLOSE_LABELS` + `modbusAlarmCloseLabel`（没收录的枚举名原样给出，
      与另两份标签映射同一条兜底），`title` 留**原样枚举名**（排查时拿它搜后端日志）。
- [x] 词典 417 → 422 × 66 份（`添加规则` / `未配置告警` / `值恢复` / `定义变更` / `被取代`），
      仍是**反查法定清单**；前 417 键逐位不变、66 份键集合与顺序一致。
      **偏离**：表里那条「删除」链接沿用现成的 `删除` 键，没有另立 `删除规则` ——
      它右边一列就是「操作」，语境已经把「删什么」说全了，多加一个键只是多 66 份要维护的译文；
      空组中间那格另立了 `未配置告警`（原来是留 `-`），因为一个出值**一条规则都没有**
      与「配了但都停用」在界面上长得一样，不说明白就只是看着空。
- [x] `npm test`（4 文件 60 例）与 `npm run build` 均过。**告警列表页没有 spec，维持现状不新开。**

## 第七步：端到端验收（需连接串）

改为**分级场景**（这是本次返工最核心的那条断言：升一级之后仍然只有一条开着）。

- [ ] 造一个配了**一组**规则的服务（如 温度：`>26` 提示 / `>28` 警告 / `>30` 严重）→
      手动 `invoke` 一个最低级的越限值 → `/alarm/many/{spaceId}` 应有**一条**未处理、未恢复，
      级别是那一级的。
- [ ] **再 invoke 一次同样的值 → 仍然只有一条**（稳态零写）。
- [ ] **升到最高一级 → 仍然只有一条开着**（最核心的断言），
      并多出一条**已恢复**的行、`closeType = SUPERSEDED`，且它的 `handled` 仍为 `false`
      （被取代**仍然算未处理**，这是有意为之）。
- [ ] **降一级 → 再次被取代**：`closeType` 是 `SUPERSEDED` 而**不是** `VALUE`
      （值仍然越限，记成「值恢复」是谎话）。
- [ ] invoke 一个正常值 → 那条开着的关闭、`closeType = VALUE`，`handled` 仍为 `false`。
- [ ] 调 `handle` → `handled=true`、`handledBy` 是当前账号。
- [ ] 再越限一次 → 出现**第二条**开着的（与前面若干条已恢复的共存）——
      **第 7.5 步改了口径**：旧行按 `HANDLED` 关掉，不是留着；这一条按下面的新步骤走。
- [ ] 删掉该出值**其中一条**规则的保存 → 若它有一条开着的行，应被 `DEFINITION` 关闭
      （**判据细到规则**：同出值别的规则还在，也要关掉它）。
- [ ] 整个字段的告警全停用保存 → 开着的行按 `DEFINITION` 关闭（不是 `SUPERSEDED`）。

## 第 7.5 步：处理过之后再越线要重报（已完成）

第七步验收之前插进来的一步。起因是一条**语义反了**的现场事实：「处理」的意思是「我知道了，
**以后还有越线再提醒我**」，而不是「这事到此为止」；可在那之前，一条已处理而未恢复的行会一直
挂着挡住后续越限（判定每轮都落在稳态零写那一支），用户再也收不到提醒。
完整口径见 `Modbus-Alarm.md` 第十三节 2026-09-16 那条，这里只列交付物与**刻意偏离**。

- [x] `ModbusAlarm` 加 `CLOSE_HANDLED = "HANDLED"`（页面文案「处理后再报」）。
- [x] 新类型 `db/modbus/alarm/OpenAlarmRule(String ruleId, boolean handled)` ——
      判定要的资料从「出值名 → ruleId」变成「出值名 → (ruleId, handled)」。
      **放在 `db` 包而不是 `ModbusAlarmPolicy` 里**：放 `api` 会新造一条 `db → api` 的反向依赖。
- [x] `ModbusServiceAlarmRepository.mapOpenRuleByField`：投影加 `handled`、返回新值型；
      折算走一个可单测的静态助手，**`Boolean.TRUE.equals(handled)` 不能省** ——
      可空的 `handled` 必须当**未处理**，与仓储自己的 CAS 过滤 `Filters.ne("handled", true)` 同口径。
- [x] `ModbusAlarmPolicy`：`judge` 加一支（同一条规则、仍在命中、`handled == true` ⇒
      `handledCloses` + 重开一条）；`Decision` 加第五个组件 `handledCloses`，
      **`isEmpty()` 与 `Acc.isEmpty()` 都要算上它**（漏了会让「只剩一次 HANDLED 关闭」的判定
      被当成无事发生而直接返回，重报整个不发生）。
      **顺序即语义**：换规则的检查排在处理检查之前；值恢复走 `VALUE`；全 `SKIP` 什么都不做。
- [x] `ModbusAlarmService.apply` 加第四段关闭，**留在 `closes` 那一段里**（先关后插）——
      反了会撞唯一索引被 `insertOpen` 静默吞掉，用户刚点完「处理」就什么都看不到了。
- [x] 单测：Policy 26 → 30（4 例：重报 / 只按值恢复关 / 规则换了只记 `SUPERSEDED` /
      全 `SKIP` 不重报）、Service 22 → 23（`InOrder` 钉住关闭先于插入）、Repository 11 → 12
      （缺失的 `handled` 折算成未处理）。**全量 579 例全绿**（40 个类）。
- [x] 文档：`Modbus-Alarm.md` 第五、七、八、十、十一、十三节 + `MODBUS.md` 第八节。

**前端**（`webapp-matrix`，1.0.1 → 1.0.2）：

- [x] `MODBUS_ALARM_CLOSE_LABELS` 加 `HANDLED: '处理后再报'`；`ModbusAlarm.closeType` 与
      三处注释里把枚举说成「三个」的地方改成四个。**渲染路径一行没改** ——
      `modbusAlarmCloseLabel` 本来就是「查表 + 没收录的原样回退」，加一个键就够了。
- [x] 词典 468 → 469 × 66 份。**这次逐语种给了真译文**（上一轮 `显示信息` 那个键 65 个语种
      填成了中文键本身，是事后才发现的）：`en`/`en_AU`/`en_GB` = `Re-reported after handling`，
      `zh_HK`/`zh_TW` = `處理後再報`，其余各按该语种 `值恢复`/`定义变更`/`被取代` 三个兄弟键的
      用词与语序给译文；追加在末尾、键序一致、`json.dumps(ensure_ascii=False, indent=2)` 往返
      逐字节不变；另跑了一遍逐语种的值检查（除 `zh` 外不得等于键本身）。
- [x] `npm test`（10 文件 **234 例**）与 `npm run build` 均过。

**刻意偏离与不做的**：

- **不做防抖、不加冷却期、不按「已处理过几次」限流**。触发条件精确到 `handled == true`，
  所以重报由人驱动（每次都要有人真按一次「处理」），上限是「越限次数 + 人工处理次数」，
  不会自激。这是明确的选择，不是漏掉。
- **不改写任何已落库的行**：只加了新的关行判据。旧行留着 `handled=true` 与它的处理人，
  新行是 `handled=false` 且**不带** `handledBy` —— 回执不继承。
- **不动 `handled` 的写入路径**（`handle` / 它的 CAS 过滤），本次只**读**它。
- **不改告警页与 3D 页的筛选语义**（仍是 `handled` 一个口径）。副作用是「只看未处理」那张
  视图会「闪一下」：处理过的那条消失、紧接着出现一条内容相同但 `at` 更新的新行。
  这是本次**要**的行为（不处理就不再出现），但第一次看到会以为是重复上报，验收时看一眼。

**验收（要写 staging，动手前先说一声）**：

- [ ] 阈值改成 24℃、让它越限、并在页面上点一次「处理」。
- [ ] 触发一次采集 → 旧行 `closeType=HANDLED`、`recoveredAt` 有值、`openKey` 已消失；
      同一时刻出现**新的一条**：`at` = 那一刻、`handled=false`、阈值快照是 24；
      **未处理计数不变**（旧的减一、新的加一）。
- [ ] 再触发一次采集（没人处理）→ **不再新增行**（稳态零写没被破坏）。
- [ ] 再点一次「处理」+ 采集 → 又一轮：旧的关、新的开。
- [ ] `mongosh` 查该 `openKey` 的历史，确认既有行的**其余字段一个都没被改**。
