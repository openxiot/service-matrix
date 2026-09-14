# Modbus 阈值告警：实施计划

设计见 `Modbus-Alarm.md`。图例：`[x]` 已完成，`[ ]` 待办。**最险的先列。**

## 第 0 步：Azure DocumentDB 上验「唯一部分索引」（**门控第三步那个第一个写入者，先做**）

- [ ] 在 staging 临时集合 `modbus/alarm-smoke` 建 `{openKey: 1}` unique +
      `partialFilterExpression: {openKey: {$exists: true}}`。
- [ ] 插两条**同 `openKey`** 的文档 ⇒ 第二条必须报 11000（重复键）。
- [ ] 插两条**都没有 `openKey`** 的文档 ⇒ **两条都必须成功**。
      这一步才证明「部分」；普通唯一索引会把第二条的 null 当重复拒掉。
- [ ] drop 临时集合，把结果（索引文档形态、时间点、结论、最终生效的是哪一档）记进 `Modbus-Alarm.md` 第七节。
- [ ] 失败则走 F1（普通唯一复合索引 `{serviceId, functionIndex, field, recoveredAt}`）
      或 F2（`updateOne` upsert + `updateMany` 兜底，并把竞态写进文档）—— **不静默降级**。

> **本机跑不了这一步**：仓库的 `application*.properties` 里没有 mongo 连接串、环境变量里也没有，
> 27017 端口不可达。需要 staging 连接串。

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
- [ ] **唯一部分索引仍待第 0 步的冒烟结论**（`ensureIndexes()` 里刻意只建了 2、3 两号）。
      两条理由写在 `Modbus-Alarm.md` 第七节：建了被静默吞掉 = 以为有护栏，建错 = 告警开始丢。
      目前告警还没有写入者，缺索引不产生脏数据。
- [x] 全量 449 例中 448 绿（唯一红色仍是 `SpaceResourceTest` 那个要连 Mongo 的环境性失败）。

## 第三步：评估接线进采集链路（待落地）

- [ ] `ModbusAlarmService.evaluate`：第一层闸门 `hasEnabledAlarm` 不过就**一次库操作都不做**；
      过了才读一次 `listOpenFields`（带索引、只投影 `field`）→ `decide` → 执行三件事
      （`insertOpen` / `closeByKeys`）。
- [ ] 接进 `ModbusServiceService.execute`，与 `recordHistory` **并列**（不是串进成功路径）：

      .chain(values -> recordHistory(service, function, values, baselines)
              .chain(ignored -> evaluateAlarms(service, function, values))
              .replaceWith(values));

- [ ] 与 `recordHistory` 同壳：失败只记日志、`recoverWithNull`，
      **既不影响 invoke 的成功响应、也不改写失败响应**。
- [ ] **重复键（11000）必须吞掉**：吞掉之后不变式「该键有一条开着的告警」恰好成立。
- [ ] 单测（Mockito，不连库）：没开告警 ⇒ `verifyNoInteractions(alarmRepo)`；稳态**零写**；
      重复键异常被吞且不让调用方失败；仓储失败不外传；关闭捕获到的 filter 是**按 key 的 `updateMany`**；
      `evaluateAlarms` 在 `recordHistory` **之后**（`InOrder`）。

## 第四步：定义变更对账与删除级联（待落地）

- [ ] `decide` 里的 `DEFINITION` 关闭（**自愈主路径**，已在第一步实现并测过）。
- [ ] `ModbusPollReconciler` 补一条拾遗：覆盖「改了定义后这个方法再也不采」的残留。
- [ ] 删除级联：`ModbusServiceService.purgeHistory(...)` 里加一句删告警。
- [ ] 启动扫一次残留。

## 第五步：查询服务与接口（待落地）

- [ ] `ModbusAlarmQueryService`：`limit+1` / `at` 倒序 / `truncated`；空间级照搬「逐服务一条带索引的
      查询 + 内存归并」，`serviceId == null` ⇒ `subtreeServiceIds(spaceId)`。
- [ ] `ModbusAlarmResource`：`/many/{spaceId}` + `/handle/{spaceId}/{id}`，逐行照
      `ModbusHistoryResource.failures(...)` 的结构（`requireSpaceMember` / `OxResponse.ok` / 内联错误）。
- [ ] `handle` 校验该告警落在传入空间的子树内 —— 否则拿别人的 id 就能点。
- [ ] `subtreeServiceIds` 从 `ModbusHistoryResource` 下沉到 `ModbusServiceService`（两个资源共用）；
      不想 churn 就复制那 15 行并注明。
- [ ] 单测：`ModbusAlarmQueryServiceTest`（对齐 `ModbusHistoryQueryServiceTest`）。

## 第六步：前端（待落地）

- [ ] 类型与编解码镜像：`ModbusServiceFieldAlarm` + 两处 `alarm?` + `ModbusServiceFieldAlarmCodec`；
      `ModbusAlarm.ts`（含 `MODBUS_ALARM_LEVELS` / `MODBUS_ALARM_LABELS` /
      `modbusAlarmCondition(alarm, translate)`，**translate 由调用方传入**，与 `modbusFailureLabel` 同契约）。
- [ ] `modbus.service.ts`：`getAlarms(...)` / `handleAlarm(spaceId, id)`。
- [ ] 编辑器逐字段配置：照抄轮询那套「侧表 + 签名」——
      `alarms` 是 `Map`，键 `` `${configId}#${functionIndex}#${fieldKey}` ``（**`configId` 前缀**：
      换源点表不能把告警带到同序号的方法/字段上）、`withAlarms(func)` 在保存时并回去、
      `seedAlarms` 装载、`alarmSignature` 排序序列化。
- [ ] **`ServiceBaseline` 要加 `alarms: string` 且 `serviceChanged` 要比它** ——
      不加的话**只改告警会让「保存」按钮不亮**，用户以为改动被无声丢掉了。
- [ ] `describeServiceField` 末尾带上已启用的告警（如 `… → 温度过高(>80)`），那一行摘要才不说谎。
- [ ] `/main/alarm` 列表页：骨架 `getSpaceGraph(spaceId)`；筛选条用已定的两列网格写法；
      表头卡（今日告警 / 未处理 / 未恢复 + 按级别分布）；表格含**行内快照**的触发条件与**原样的**告警文本；
      `handle(row)` 就地替换该行（不整页刷新）。**服务端/用户文本一律不翻译**；表格**不写 `nzWidth`**。
- [ ] 词典追加 29 个新键 × 66 份（388 → 417），追加后**前 388 键与追加前逐位相同**。
- [ ] 改掉那条会变成假话的旧提示：`方法由源点表生成（一个功能码动作 = 一个方法），除自动轮询与调用周期外均只读`
      —— 告警可配之后它不成立了（66 份里替换 key 字符串、保留各语言译文）。

## 第七步：端到端验收（需连接串）

- [ ] 造一个配了告警的服务 → 手动 `invoke` 一次越限值 → `/alarm/many/{spaceId}` 应有一条未处理、未恢复。
- [ ] **再 invoke 一次同样的越限值 → 仍然只有一条**（本特性最核心的断言）。
- [ ] invoke 一个正常值 → 该条 `recoveredAt` 有值、`handled` 仍为 `false`。
- [ ] 调 `handle` → `handled=true`、`handledBy` 是当前账号。
- [ ] 再越限一次 → 出现**第二条**。
- [ ] 把该字段的告警关掉（或删掉该字段）保存 → 若此时有开着的告警，它应被 `DEFINITION` 关闭。
