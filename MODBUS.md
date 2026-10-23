# 将 Modbus 配置，生成具体的 Modbus 服务

## 一、设计思想

Modbus 点表配置（`ModbusConfig`）描述挂在一条 Modbus 总线上的一台物理设备的寄存器 / 线圈点表。
把它变成「可调用的东西」有两条路，本项目走过前一条、最终落在后一条：

* **生成设备实例**（**已废弃**）：把点表展开成一台虚拟设备的实例定义，作为依赖设备（DTU）的子设备挂进设备表，
  读属性 / 写属性 / 执行方法都复用通用的设备接口（`DeviceResource`）。
  优点是不用另设接口；缺点是每个请求都要经过设备拦截器翻译指令（虚拟 action ↔ 父设备“发送”），
  步骤繁多，且要读好几次数据库。
* **生成 Modbus 服务**（**现行方案，本文档**）：把「怎么调依赖的设备、发什么帧、怎么解应答」在**创建时**
  一次性展开成一份 `ModbusService` 文档；调用时只读这一条记录即可组装出父设备调用。
  优点是简单、一次数据库访问；缺点是需要单独设计一套 API（`ModbusServiceResource`）。

前者的代码（虚拟设备资源 / 实体 / 仓储、`DeviceInterceptor`、`DeviceManipulator`、以及 `DeviceService` 里
为虚拟子设备做的 online 归一）已整体删除，见变更记录。**现在只剩这一条路径**：
通用的设备线（`DeviceResource` 直接调 `ManipulationService`）+ 点表配置线 + Modbus 服务线。

## 二、示例

./modbus-service-example.json

文件里 8 个方法把两侧的形态都覆盖了：读方法四个（fc 03 / 01）与写方法四个（fc 05 / 06 / 0F / 10）。
顶层的 `"id": "xxxx"` 只是占位 —— **创建时不由请求体决定**，POST 填什么都会被忽略、由服务端生成。

**照着抄的时候认准方向。** 这份 JSON 里「键在不在」本身就是语义：**值为 null、列表为空的键一律不写**
（codec 的既有口径）—— 空的时候不会被写出来，保存后读回来也不会凭空多出 `fields: []`、
`response: {}` 这种空壳键。所以键并不与 `ModbusPolling` 的字段一一对应，而是按方法方向出现或消失：

| 键 | 读方法（fc 01/02/03/04） | 写方法（fc 05/06/0F/10） |
|---|---|---|
| `request.slaveId` / `request.fc` / `request.start` | 必填 | 必填 |
| `request.quantity` | 必填：帧里那个数量字段的**字面值**（01/02 位数、03/04 寄存器数） | **不写**：个数由 `fields[]` 推出，给了反而报错 |
| `request.fields[]` | **不写**：读请求没有具名字段，读哪儿由 `start`/`quantity` 定 | 必填：写入字段（可带缺省值 `value`） |
| `response` 这个对象 | 随之出现 | **整段没有**：写应答是请求回显，没有读值 |
| `response.fields[]` | 必填：没有解析规则的读方法返回 `{}`，是静默失效 | ——（随上一行一起没有） |
| `interval` / `polling` | 可选：`polling` 缺省按有没有 `interval` 判定 | **不许有**：那等于周期性写寄存器，校验直接拒 |

示例里方法 2/3/4 看不到 `interval`/`polling`、方法 5~8 看不到 `response`，都是「空」而不是「漏了」。
逐条的校验规则见第七节；`ModbusServiceCodecTest.documentedExampleFileIsValid` 会拿这个文件真跑一遍校验，
规则改了忘改示例会被测试挡住。

## 三、数据模型（落库 `modbus`/`services`，Panache 生成 ObjectId，对外十六进制字符串）

```
ModbusService                       name / version / configSnapshot / slaveId / device / functions[] / creator / updater
ModbusConfig（快照）                id / owner / slave / visibility / registries[] / commands[] / lifecycle / creator / updater
                                    ← 创建服务时整份拷贝进 configSnapshot；之后点表改动/删除不影响该服务
DeviceOn                            did / siid / aiid / argument / space  ← 依赖的设备：调用坐标 + 所在空间，前端创建时写入
SpaceOn                             spaceId / rootId                     ← 设备落点，与 DeviceEntity.space 同一个类
ModbusPolling                      index / name / request / interval / polling / response
ModbusPollingRequest               slaveId / fc / start / quantity / fields[]  ← 请求帧定义（读给 quantity，写给 fields）
ModbusPollingRequestField          index / field / offset / format / byteOrder / value  ← 写入字段（含缺省值）
ModbusPollingResponse              fields[]                                        ← 应答定义（与 request 对称；写方法整段没有）
ModbusPollingResponseField         index / field / bytes / format / byteOrder / scale / unit / value-list[] / bit-list[] / alarms[]
ModbusPollingResponseFieldValue    value / description       ← 取值表条目：原始值 → 描述字符串
ModbusPollingResponseFieldBit      offset / field / alarms[] ← 位区里的具名位（01/02），位是独立的结果键
ModbusPollingResponseFieldAlarm    id / enabled / compare / threshold / state / level / text
```

* 服务本身**不记组织**（原来的 `orgId` 字段已删）：服务这一层按空间判权（见第四节）。
  `configSnapshot.owner` 只是点表归属的**历史副本**（展示/溯源用），不参与鉴权。
  设备归属用的是**设备所在根空间**，见第五节与 `ManipulationService`。
* **`configSnapshot` 是点表的创建时快照**（整份 `ModbusConfig`，含 `id`）：创建服务时由前端把
  当前选中的点表原样写进请求体，之后点表被修改/删除都不影响这条服务 —— 服务不再依赖点表集合。
  读服务的地方一律读它，**不再回查 `configs`**：寄存器缓存网格（`ModbusShadowService`）用它出
  可读寄存器、详情/清单页用它显示「源点表」、设备页用它取寄存器名、统计按 `configSnapshot.id`
  分组（`ModbusServiceRepository.findConfigKeysBySpaceId` 只投影 `configSnapshot._id`）。
  落库形状与点表文档同形（嵌套 `_id`），迁移脚本见
  `docs/migrations/modbus-service-config-snapshot.js`（configId → configSnapshot）。
* `slaveId` 是服务级从站地址（1..247），创建时从快照的 `slave.slaveId` 拷贝、可在界面改；
  各方法的 `request.slaveId` 必须与它一致。
* `device.argument` = 依赖设备的方法**入参的 piid**，请求帧填在这里。DTU 承载数据的动作只有一个入参，
  故提到 device 级、对该服务的所有方法共用（不同型号 piid 不同：s710ym 是 1/2/2、dtu.json 是 1/1/1，
  由前端按型号写入，后端不去产品定义推算）。
* `device.space` 记录该设备所在的空间（`cc.openxiot.matrix.db.space.SpaceOn`，`DeviceEntity.space`
  用的是同一个类）。空间图要按空间反查服务（`ModbusServiceRepository.findBriefBySpaceId`，见第六节），
  落库时带上就不必再到设备表里绕一圈。它不参与校验：缺省的服务只是不出现在按空间的查询里。
* `function.request` = **请求帧的结构化定义**（`ModbusPollingRequest`），**不是**一整串 hex：
  `slaveId` / `fc` / `start` 三个标量，读方法再给 `quantity`、写方法再给 `fields[]`。
  **方向由 `fc` 推出**（01/02/03/04 读、05/06/0F/10 写），没有单独的 area / rw 字段 ——
  一个方法 = 一条命令，这正是点表 `ModbusCommand` 的口径。**帧在 invoke 时由后端现组**
  （`ModbusFrameCodec.encodeRequest(request, values)`），包含 CRC16，前端不再算帧。
  以前这里存的是整帧，方向得从 `parseHex(request)[1]` 猜、写死的值也跟着帧一起烤进去，
  想写别的值只能再建一个方法。见第五节与第七节。
* `request.quantity` = 帧里那个数量字段的**字面值**（03/04 = 寄存器数、01/02 = 位数），
  **只在读方法上给**。**口径与点表不同**：点表的 `quantity` 是「值的个数」，成立的前提是
  一条命令只有一个 `dataType`（跨度统一，`readQuantity` 据此乘跨度）；服务里每个应答字段
  各带自己的 `format`/`bytes`，「值个数 × 单一跨度」根本算不出来，所以这里直接就是线上那个数。
  写方法的数量由 `fields[]` 推出，给了 `quantity` 反而报错。
* `request.fields[]` = **写方法的写入字段**（`ModbusPollingRequestField`：`index` / `field` /
  `offset` / `format` / `byteOrder` / `value`），读方法必须为空。`field` 是字段名，
  既是 `invoke` 请求体里取值用的 key，也是人看的名；`value` 是 **缺省值**（原始值，不做
  scale 换算）—— 没配缺省值的字段每次调用都必须给值，配了则可开箱即用、又能被 invoke 覆盖。
  点表展开出来的写命令（`运行模式 = 制冷`、`开机 = 1`）都带死值，缺省值就是为它们留的。
  `offset` 只对 fc 0F/10 有意义（位偏移 / 寄存器偏移，相对 `start`），05/06 写一个值，不许填。
* `function.interval` = 服务端**自动调用**本方法的周期（秒）：到点自动 invoke 一次，再按
  `response.fields[]` 解出字段值；缺省表示没配周期。只挂在读方法上 —— 写方法填了会被校验拒绝。
* `function.polling` = **自动轮询开关**：`true` 按 `interval` 周期跑，`false` 保留周期但暂停，
  缺省 = 按 `interval` 判定（有周期即启用，兼容加这个字段之前的定义）。**写方法一律不许开启**
  —— 让定时器周期性地往寄存器里写值是危险动作，`polling == true` 时校验器直接拒绝（不只是
  「`polling` 要求 `interval`、而 `interval` 不许配在写方法上」这种绕一层的间接成立），
  `ModbusPollPlan.shouldPoll` 在执行侧再堵一道。
  **执行侧的调度器尚未落地**（设计已定，见 `Modbus-Service-Poller.md`），这两个字段目前都只是定义。
* `response` = **应答定义**（`ModbusPollingResponse`），目前只有 `fields[]` 一张应答解析规则表
  （`ModbusPollingResponseField`），读方法必填、写方法整段不出现。包一层而不是直接把字段表挂在
  `function` 上：一是与 `request` 对称（帧的两侧都是对象，各有一处放自己的属性 ——
  `request.fields[]` / `response.fields[]`），二是给 **应答级**的设定留位子（异常码到文案的映射、
  缺省字节序、期望帧长）。刻意 **不**做成 `List<ModbusPollingResponse>`：一帧请求只对一帧应答，
  消费端（parser / 影子 / 告警）要的始终是「 **一个**有序切片表覆盖 **一个**数据区」，
  套一层 List 之后那边只能 flatten 或取 `[0]`，外面那层永远只有一个元素，却给每条校验文案
  加一个恒定的 `[0]`。真要支持「一次调用读写多个不连续区段」，该加的是 `requests[]` /
  `responses[]` 这一对，那是另一个特性。
  `version` 标记 functions 内部格式的口径， **当前是 `2`**（`request` 从 hex 字符串改成结构化定义
  那一版；`response` 从裸数组改成对象并入同一版 —— v2 尚未部署过，不必为此再抬一版），
  后续演进时递增。校验器要求 `version == 2`：旧口径的定义会 **响亮地失败**，而不是被
  `getJsonObject` 读成 null 后报一句与病因无关的「request.slaveId is required」。
* `response.fields[].alarms[]` = 该出值的 **一组阈值告警规则**（`ModbusPollingResponseFieldAlarm`）：
  `enabled` 为 `true` 且值越限时按级别 **选出一条**开出告警（同一出值同时最多一条开着；
  选举、关闭原因与状态见 `Modbus-Alarm.md`）。 **只能配在读方法（fc 01/02/03/04）上**
  —— 写方法的应答是请求回显，没有「读到的值」可言。上限 **8 条/出值**。
* **`alarms` 挂两处**：字段（`response.fields[].alarms`）与位（`response.fields[].bit-list[].alarms`），
  **各是一组、各自算**（含条数上限与 `id` 唯一性）。位的值是**独立的结果键**
  （parser 把每个具名位单独放进结果对象，值为 0/1），只挂字段上的话「进水阀 = 1 告警」
  这类配置根本够不着 —— 父字段那份是整个位掩码，不是一回事。
* `id` = 这条规则的**身份**（前端生成、随配置落库、后端只校验：非空、≤64、同一出值内唯一）。
  它存在是为了让「这条开着的告警是哪条规则开的」可比对 —— 库里的告警行存着 `ruleId`。
  **不能用数组下标当身份**：删掉第 0 条会让第 1 条正开着的告警静默改嫁到新第 0 条。
  `id` 只是后端内部标识，**不下发**（与告警行的 `openKey` 同口径）。
* `compare` 用**符号**（`>` `>=` `<` `<=` `=`）而不是 `GT/GTE` 这类枚举：语言中立、与需求原话一致、
  日志与响应里回显无歧义；「超过 / 达到 / 低于」那些**词**是页面自己的文案（进词典），符号本身不翻译。
* `=` 的比较目标按字段形态二选一：带 `value-list` 的字段出的是**描述字符串**，用 `state` 逐字比；
  数值字段与位字段用 `threshold`（位只能比 0 或 1）。两者互斥，校验器按 `compare` 与字段形态判定。
  用 `Double threshold` + `String state` 而不是一个 `String` 在运行期解析：后者是把协议语义压成
  字符串匹配（`ModbusServiceFailure` 的注释已明确反对这种做法），而且它落在**每次采集的热路径**上，
  `""→0`、`"1e3"`、前后空格全是坑。

## 四、接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/matrix/v1/modbus/service/many/{spaceId}` | 空间成员 | 列出设备落点在该空间的服务 |
| GET | `/matrix/v1/modbus/service/parent/{spaceId}/{did}` | 空间成员 | 按依赖设备 did 列出（路径里的 `parent` 暂未随字段改名） |
| GET | `/matrix/v1/modbus/service/one/{spaceId}/{id}` | 空间成员 | 指定 ID 查找 |
| POST | `/matrix/v1/modbus/service/one/{spaceId}` | 空间管理员 | 创建（body = 完整 ModbusService） |
| PUT | `/matrix/v1/modbus/service/one/{spaceId}/{id}` | 空间管理员 | 修改（缺省字段保留既有值） |
| DELETE | `/matrix/v1/modbus/service/one/{spaceId}/{id}` | 空间管理员 | 删除 |
| POST | `/matrix/v1/modbus/service/invoke/{spaceId}` | 空间成员 | 调用方法，body `{"service": "<id>", "function": <index>, "values": {"字段名": 值}}`（`values` 只在写方法上用，见第五节） |
| GET | `/matrix/v1/modbus/shadows/{spaceId}?serviceIds=a,b,c` | 空间成员 | **寄存器缓存网格**：一次读一个/多个服务的全部可读寄存器当前缓存值（独立资源，见 `ModbusShadowResource`/`ModbusShadowService`；`serviceIds` 缺省则该空间全取，可用逗号或重复参数） |
| GET | `/matrix/v1/modbus/poll/{spaceId}` | 空间成员 | 该空间下服务的**轮询任务**与运行时状态（另起一段路径，见 `Modbus-Service-Poller.md` 第八节） |

**权限一律按空间判，与 `DeviceResource` 同口径**：路径上的 `spaceId` 是当前项目空间（前端统一传
当前项目根空间，与设备接口一样），增删改要该空间的**管理员**、查询与 `invoke` 要**成员**；判定是
`OrgAccessService.checkSpaceAdmin` / `checkSpaceMember` —— 空间 accesses 里的组织条目 + `X-Org-Id` 命中时，
组织管理员 / 组织成员同样通过，故 **`X-Org-Id` 在增删改上不再是必填**，只在组织条目匹配时起作用。

`spaceId` 只作**鉴权锚点**：判定通过后一律按 id 操作，不校验「服务是否真属于该空间」（与
`DeviceResource.removeDevice` 的「spaceId 仅用于管理员鉴权，按 did 删除」一致）；id 是不可猜的 ObjectId，
风险与设备接口同级，换来的是「一个项目一把钥匙」的简单模型。`/many/{spaceId}` 是唯一按空间**过滤数据**
的接口：它筛 `device.space.spaceId`（与空间图同一字段、同一索引），设备搬走后副本陈旧的服务不在其中；
要看整个项目的服务用空间图 `/matrix/v1/space/graph/{rootId}`（按子孙空间逐个查）。

**删除设备会级联删掉该设备下的服务**（`DeviceResource.removeOne` / `removeDevices` → `ModbusServiceRepository.deleteByDeviceDids`）：
按 `device.did` 匹配、**不限组织**。设备先删服务、再删设备行，任一步失败整链失败还能重试；
反过来会出现「设备行已删、服务永久孤儿」（既查不出来、也删不掉）。点表 `configs` 不引用设备，不参与级联。

## 五、invoke 的调用链与返回

`ModbusServiceService.invoke`：按 id **读一次** `ModbusService` 文档 → 取该 index 的 function →
**按 `request` 现组帧**（`ModbusFrameCodec.encodeRequest(function.request, values)`）→
组装依赖设备的 `ActionOperation`（did/siid/aiid 取 `device`，in 填 `{argument: 帧}`）→
交 `ManipulationService.invokeActions`（DTU 的“发送”把帧透传到 RS485）→ 把应答帧按
`response.fields[]` 解成字段值。

* **读写两条路都在 invoke 时现组帧**，一条代码路径：读用 `slaveId/fc/start/quantity`，
  写用 `fields[]` 叠上 `values`（没给的字段回落到 `field.value` 缺省值）。
  组帧失败在校验之后不该发生（属配置 bug），抛 `ModbusInvokeException.of(CONFIG_ERROR, …)`，
  进故障记录。CRC16 与字节序复用 `ModbusFrameCodec` 现成的原语，与点表那条线出的帧逐字节相同
  （有单测钉住）。
* `values` 是**写方法**的入参（body 里的 `{"字段名": 原始值}`，`Map<String, Object>`）：
  字段名即 `request.fields[].field`，值就是原始值（位是布尔、寄存器是数值），不做 scale 换算。
  **读方法给了 `values` 直接报错** —— 静默忽略一个用户以为生效了的载荷，比报错糟得多。
  写字段里没有缺省值的必须给、给了未声明的字段名报错、类型不符 / 越界报错，
  这套判定是 `ModbusServiceValidator.validateInvoke`，在 `execute` **之前**拦下：
  它是**调用方的请求错误**（与「function not found」同类），与设备故障不是一回事，
  因此不进 `service-failures`。

* 往下传的**归属**是 `ownerId` = **设备所在根空间 id**（资源层用路径上 `spaceId` 现推：
  `SpaceEntity.rootSpaceId()`），不是 `X-Org-Id`：manipulation 的归属记录是 `(did, appId, ownerId)`，
  `ownerId` 对不上就报 "device not owned"。详见 `ManipulationService` 的类注释。

* 应答帧按 `[slave][fc][byteCount][data...][crc16]` 校验（长度 / 异常码 `fc|0x80` / CRC16，复用
  `ModbusFrameCodec` 的 CRC 与字节序原语），数据区按 `response.fields[].index` 升序、以 `bytes` **依次累加偏移**切分，
  各字段 bytes 之和必须等于 byteCount。
* 取值：原始值命中 `value-list` → 输出其 `description`（不再应用 scale）；否则输出 scale 后的数值；
  `unit` 只做展示、不参与取值（调用方从服务定义里查）。
* **01/02 的数据区是按位打包的位数组**，不按字节序拼装：第 i 位 = 第 i/8 个字节的第 i%8 位
  （LSB-first，第 0 位即请求的起始地址那个线圈），故 9–16 个位读回来的掩码是字节小端拼装
  （`03 01` → `0x0103`，第 0、1、8 位为 1）。`bit-list[]` 在该字段的掩码之外，把其中的若干位
  各自再作为一个 0/1 取值输出（key 即该位的 `field`）—— 位区只能整段占一个字段（字节数是
  `ceil(位数/8)`，且各字段 bytes 之和必须等于 byteCount），逐位取值靠位清单展开。
* 返回 `OxResponse.ok(data)`，`data` = **字段 → 值**：

  ```json
  { "success": true, "data": { "进水温度": 61.5, "开关状态": "ON", "线圈状态": 5, "进水阀": 1 } }
  ```

  同一个应答里的出值名必须唯一：字段值与各位值进的是同一个结果对象，重名会互相覆盖（见第七节）。

  写方法（没有 `response`）的应答是请求回显、没有读值，`data` 为空对象 —— **不回显写入值**：
  `ModbusHistoryService.record` 把非空 `values` 当**采集样本**落库，回显会把写回声灌进采集历史曲线。
  调用方自己知道发了什么，成功即 200。
  父设备报错 / 帧非法 / CRC 不符 / 异常码一律 `OxResponse.error(message)`，不做部分成功。

* **`invoke` 有副作用：顺带落历史**。成功按落库规则写 `service-histories` 并更新 `service-last-polling`，
  失败写 `service-failures`（见 `Modbus-History.md`）。**手动调用一次 = 记一次**，
  与轮询器走的是同一个入口 —— 所以「只想看一眼当前值」的调用也会在曲线上留一个点。
  记录写失败既不改写成功响应、也不改写失败响应：值已经拿到了，历史只是副作用。

* **`invoke` 还有第二个副作用：顺带评阈值告警**（`Modbus-Alarm.md`）。成功路径上是
  `recordHistory(...).chain(ignored -> evaluateAlarms(...))` —— 两件事**并列**，
  串起来只是为了顺序确定（历史是「采到什么」、告警是「值对不对」，现场排查先看前者），
  它们互不依赖、也各有各的失败重试。`evaluateAlarms` 与 `recordHistory` 同壳：
  失败只记日志 + `recoverWithNull`，**既不改写成功响应、也不改写失败响应**。
  第一层闸门 `ModbusAlarmPolicy.hasEnabledAlarm` 是纯内存判断，所以**没配告警的服务在告警这一步
  零库操作**；配了的方法每轮多一次带索引的读（「这个方法的哪些出值开着」）。
  失败路径（帧非法 / 无应答等）没有读值，不评告警 —— 那是 `service-failures` 的事。

## 六、空间图里的服务

`/matrix/v1/space/graph/{rootId}` 在 `spaces` / `devices` 之外平级返回 `services`：取该根空间下全部子孙空间，
逐个按空间并行查设备（`DeviceService.getBySpaceId`）与服务（`ModbusServiceService.getBriefBySpaceId`），
再各自拍平成一张表。

* 两个列表走 `SpaceResource` 里同一个泛型 helper（`bySpace(spaceIds, loader)`），
  再 `Uni.combine().all().unis(...)` 并行等齐。
* 服务侧 `ModbusServiceRepository.findBriefBySpaceId` 用 Mongo 投影（`FindOptions.projection`）只带
  `_id` / `name` / `device.did` / `device.space.spaceId` 回来（命中 `device.space.spaceId` 索引）：
  文档里的 functions / response 一个字节都不上网络。代价是返回的实体只有这几个字段有值——
  **别当完整定义用**，要详情另按 id 查。
* `services` 用 `ModbusServiceCodec.encodeBrief(List)` 出**精简视图**，只有五个字段：

  ```json
  { "id": "65f0c0ffee0123456789abcd", "name": "1 号冷水机组", "type": "modbus",
    "did": "did-1", "spaceId": "space-1" }
  ```

  `type` 固定 `modbus`，供前端把服务和设备混在一张表里渲染；`did` / `spaceId` 是依赖设备及其落点，
  供清单里把服务与设备对上（同表渲染、按空间或设备筛选）；详情另调 `/matrix/v1/modbus/service/one/{spaceId}/{id}`。
  整份定义（functions / response）动辄几十 KB，一个图里挂几十个服务就不适合整份带出来了。

## 七、校验

规则实现在 `ModbusServiceValidator`（纯静态函数，返回 `null` = 通过，否则是给前端的错误文案），
`ModbusServiceService` 只负责把文案转成 `IllegalArgumentException`。

* `name`：服务名称（展示用）必填。
* `version`：必填，且**必须等于 `2`**（定义格式口径）。旧口径的定义硬拒，不静默降级读成 null。
  后端在 service 里的缺省填充形同虚设，前端要带上。
* `device`：did 非空、siid/aiid/argument ≥ 1。
* `functions`：index 必填 / ≥1 / 服务内唯一；name 必填；request 必填（见下）。
* `request`（请求帧定义，`ModbusPollingRequest`）：
  * 公共：`slaveId` ∈ 1..247；`fc` ∈ `01/02/03/04/05/06/0F/10`（大小写不敏感，`0f` 与 `0F` 同一件事）；
    `start` ∈ 0..65535。
  * 读（01–04）：`quantity` 必填（01/02 ∈ 1..2000 **位**、03/04 ∈ 1..125 **个寄存器**）；
    `fields` 必须为空 —— 方向由 `fc` 定，两者都给或都不给都是定义错误。
  * 写（05–10）：`quantity` 必须不填（帧里的数量由字段推出，给了报错）；`fields` 非空；
    05/06 恰好一个字段（fc 06 只写一个寄存器）且 `offset` 不填 —— 这两个帧里没有「第几个」可言；
    0F 全是 `bit`、10 全是寄存器格式。
  * 每个写字段：`index` ≥1 且组内唯一；`field` 非空且组内唯一；`format` 属于该 fc 的允许集
    （05/0F 只有 `bit`；06 只有 `int16/uint16`，4 字节格式写进去会被静默截掉一半；
    10 是 `int16/uint16/int32/uint32/float32`）；跨度 > 1 个寄存器时 `byteOrder` 必填且 ∈
    `ABCD/DCBA/BADC/CDAB`；`value`（给了的话）与 `format` 自洽 —— 位是布尔，寄存器是**有限数值**，
    整数格式还必须**是整数值且在取值范围内**。「整数值」按**值本身**判而不是按 Java 类型判：
    请求体过 Jackson 后 `2` 可能是 `Integer` 也可能是 `Double`，而 `Double` 能精确表示全部
    int32/uint32，按类型判会把合法值挡在外面。
  * **偏移必须按 index 升序首尾相接**（10：每条 `offset == 上一条 offset + 上一条跨度`，从 0 起；
    0F：恰好是 `0..n-1`）：fc 0F/10 的帧只有「起始地址 + 个数 + 紧凑数据区」，没有空洞可言
    —— 点表那条线的 `writeMultipleRegistersBody` 也正是这么要求的。
  * **同一服务各方法的 `slaveId` 必须一致**：一个服务由一条点表（`configSnapshot`）映射而来，
    一条点表只有一个 `slave`；从站地址写错不会报错，只会静默读到隔壁设备。
* **请求 ↔ 应答对账**（结构化之后白拿的那层）：
  * 读方法 `response.fields[]` **必填非空** —— 没有解析规则的读方法返回 `{}`，是静默失效。
  * 写方法 `response` **必须没有**（整段不出现）—— 写应答是请求回显，解析规则无从谈起。
  * `Σ response.fields[].bytes` 必须等于这一读**实际取回的字节数**：01/02 = `ceil(quantity / 8)`、
    03/04 = `quantity × 2`。**顺带的一条硬约束**：03/04 永远整寄存器地读，所以 1 字节的
    `int8/uint8` 字段**不可能**出现在 fc 03/04 的应答里 —— 它只能来自位区（fc 01/02，quantity 8）。
* `interval`：可缺省（缺省 = 没配周期）；给了就必须是 `5 ≤ interval ≤ 3600` 的整数秒，
  且该方法是**读方法**（`request.fc` ∈ `01/02/03/04`）—— 写方法填了报错。
* `polling`：可缺省；缺省 / `false` 不校验（没开轮询时 `interval` 只是一份没生效的配置）；
  但 **`true` 有两条要求**：只允许**读方法**（写方法开轮询 = 让定时器周期性往寄存器里写值，
  是危险动作），以及**必须同时给 `interval`**（开了轮询却没有周期，服务端无从知道多久调一次）。
  前者以前是间接成立的（`polling=true` 要求 `interval`、而 `interval` 不许配在写方法上），
  绕了一层，读代码的人看不出这条规矩，现在是一条显式拒绝。
* `values`（`invoke` 的入参，**执行期**校验 `ModbusServiceValidator.validateInvoke`）：
  读方法给了 `values` 报错；写方法逐字段查 —— 没有缺省值的字段必须给、给了未声明的字段名报错、
  类型不符 / 越界报错（取值判定与定义期的缺省值共用同一处）。这是**调用方的请求错误**，
  与「function not found」同类，在 `execute` 之前拦下，**不进** `service-failures`。
* `response.fields[]`：index 必填 / ≥1 / 组内唯一；field 必填；bytes ≥ 1；format ∈
  `int8/uint8/int16/uint16/int32/uint32/float32/string`，且非 string 时 bytes 必须等于该格式的宽度；
  bytes > 1 时 byteOrder 必填且 ∈ `ABCD/DCBA/BADC/CDAB`；value-list 内 value/description 必填且 value 不重复。
  出错文案里的位置就是 JSON 里的路径（`functions[0].response.fields[1].bytes`），照着提示能直接找到那一项。
* **同一应答内的出值名唯一**：各字段的 `field` 与各位清单的 `bit-list[].field` 不能重复 ——
  解析结果是一个 `字段 → 值` 的对象，重名即互相覆盖。
* `bit-list`（01/02 位区的逐位取值）：offset 必填且 `0 ≤ offset < bytes × 8`、组内不重复，field 必填。
* `alarms`（阈值告警，一个出值一组）：**只在 `enabled == true` 时校验** —— 半配置合法，
  与 `polling` 同一条规矩（`false` 的用法就是「把配置留着、先不报」），所以下面的规则读作
  「启用之后必须自洽」：
  * 一组最多 **8 条**（`alarms` 与 `bit-list[].alarms` 各自算）—— 防呆，不是安全边界。
  * 启用规则的 `id` **非空、去空白后 ≤ 64 字、同一出值内唯一**；停用的规则既不查 `id`、
    也不占用组内的唯一名额。`id` 只在 `enabled` 时校验，理由与本节开头同。
  * 只能配在**读方法**上（与 `interval` 共用同一个 `isReadFunction` 判断，不各写一份）。
  * `compare` ∈ `> >= < <= =`；`level` ∈ `INFO/WARN/CRITICAL`；`text` 去首尾空白后非空且 ≤ 64 字。
  * **能比什么由字段的取值形态决定**，不留给运行期碰运气：`format` 为 `string` 的字段一律不收
    （出的是任意文本，没有可比的量）；带 `value-list` 的字段只能用 `=` 比 `state`
    —— 给它配 `>` 会让比较随原始值是否命中取值表而**时灵时不灵**，正是要避免的静默失效。
  * `=`：带 `value-list` ⇒ `state` 必填且**逐字命中**某个 `description`，`threshold` 必须不给；
    是位 ⇒ `threshold` ∈ `{0, 1}`；否则 `threshold` 必填且有限（NaN / Infinity 拒）。
  * 数值比较（`> >= < <=`）：`threshold` 必填且有限，`state` 必须不给，位上不许配。
  * `OPERATORS` / `LEVELS` 两张白名单**只有一份**（`ModbusAlarmPolicy`），校验器与判定器共用 ——
    两边各写一份迟早漂移。

## 八、变更记录

### 2026-10-23

* **服务内嵌点表快照：`String configId` → `ModbusConfig configSnapshot`**。点表在服务建好之后
  仍可能被修改/删除，而服务的方法、寄存器名、统计分组都依赖「建这条服务时那张点表」——
  改为创建时把**整份点表**快照进服务（前端在创建/换点表时把 `configSnapshot` 内容写全），
  读服务即拿到全量定义，**不再回查 `configs` 集合**：
  * `ModbusShadowService.registerCache` 直接用 `service.configSnapshot.registries`（不再
    `resolveConfig`），旧服务没有快照时给空网格；
  * `ModbusServiceRepository.findConfigIdsBySpaceId` → `findConfigKeysBySpaceId`，只投影
    `configSnapshot._id`，统计的分组键改为快照里的点表 id（键的形状没变，前端显示不受影响）；
  * 校验器要求 `configSnapshot` 且其中 `id` 非空；更新（PATCH 合并）时缺省保留原快照。
  * 落库形状：嵌套文档与点表集合同形（`_id` 为 ObjectId），`ModbusServiceSnapshotBsonTest` 钉住。
  * 存量数据迁移：`docs/migrations/modbus-service-config-snapshot.js`（默认演练，`APPLY=1` 落库）；
    **先部署新后端再迁库**，脚本头注释里有部署前后空窗的说明。

### 2026-10-22

* **新增独立的「寄存器缓存网格」读端点** `GET /matrix/v1/modbus/shadows/{spaceId}?serviceIds=...`：
  独立资源 `ModbusShadowResource` / 独立 `ModbusShadowService`（不在 `/modbus/service` 下、不复用
  `ModbusServiceService` 的调用逻辑），把点表可读寄存器 × `service-last-polling` 快照归并成按寄存器的
  网格，一次看整张点表缓存实况（含 `no-data`）。`serviceIds` 支持逗号或重复参数，缺省则该空间全取。
* **当前值改名对齐语义**：`ModbusServiceShadow` → `ModbusServiceLastPolling`、集合
  `service-shadows` → `service-last-polling`（直接改名不迁移），相应计划类 `ModbusShadowPlan` →
  `ModbusLastPollingPlan`。详见 `Modbus-History.md` 九、2026-10-22。

### 2026-09-23

* **`function.request` 从「一整串 Modbus RTU 帧」改成结构化定义**（`ModbusPollingRequest`），
  **组帧下沉到后端**。以前前端现算 CRC16 把帧烤进定义里，带来三个问题：写操作的值被焊死在定义里
  （点表展开出的 `开机 = 1`、`运行模式 = 制冷` 各自焊成一个方法，想写别的值只能再建一个 ——
  「界面上手动输入值」根本做不到）；读写方向靠 `parseHex(request)[1]` 猜，还得为库里的脏数据
  包 try/catch；`fc` 本身没有任何校验，应答字段的字节数之和与请求读了多少个寄存器之间也没有
  交叉校验，对不上只能在运行期撞 `FRAME_MISMATCH`。几条口径记在这里：
  * **方向由 `fc` 推出**（01/02/03/04 读、05/06/0F/10 写），没有 area / rw 字段 —— 与点表
    `ModbusCommand` 同口径：一个方法 = 一条命令。`isReadFunction` 从解析 hex 简化成
    `ModbusFrameCodec.isRead(request.fc)`。
  * **`quantity` 换成线上那个字面值**，不沿用点表口径。点表的 `quantity` 是「值的个数」，
    成立的前提是一条命令只有一个 `dataType`（跨度统一，`readQuantity` 据此乘跨度）；服务里
    每个应答字段各带自己的 `format`/`bytes`，「值个数 × 单一跨度」根本算不出来。随之而来的是
    **请求 ↔ 应答对账**：`Σ response.fields[].bytes` 必须等于实际取回的字节数（01/02 = `ceil(quantity/8)`、
    03/04 = `quantity × 2`），于是「1 字节字段配 fc 03」这类定义在**定义期**就被挡下
    （03/04 整寄存器地读，单字节只能来自位区）。
  * **写字段带缺省值**（`ModbusPollingRequestField.value` 用 `Object` 而非 `Double` / `String`：
    位是布尔、寄存器是数值，压成 `Double` 会把「位」变成 0/1 数字，压成 `String` 是把协议语义
    压成字符串匹配 —— 见 `ModbusPollingResponseFieldAlarm` 那条注释）。点表展开出的写命令都带死值，
    没有缺省值它们会退化成「每次调用都得手填 0」的哑方法，枚举语义全丢。`Object` 落 Mongo
    靠 `ObjectPropertyCodecProvider`（先例：`ModbusServiceHistory.value`），故不能换成接口或
    泛型变量声明。本版写字段只收 **原始值**，不做 `scale` 工程量换算（将来要「按 ℃ 输入」再加）。
  * **组帧只有一条代码路径**：`ModbusFrameCodec.encodeRequest(ModbusPollingRequest, Map)`，
    读写都在 invoke 时现组、复用现成的 CRC16 与字节序原语；与点表线
    `encodeRequest(slaveId, ModbusCommand)` 出**逐字节相同**的帧（有单测钉住，含
    `010300000001840A` 这个既有断言值）。前端不再需要算帧。
  * **写方法一律不许开轮询**：校验器里是一条**显式拒绝**（以前是间接成立 —— `polling=true`
    要求 `interval`、而 `interval` 不许配在写方法上，绕了一层，读代码的人看不出这条规矩）；
    执行侧 `ModbusPollPlan.shouldPoll` 保留作最后一道闸（历史数据 / 直接改库都能让写方法
    带着 interval 出现在库里）。定义从读改成写时，`ModbusPollReconciler` 每轮全量对账会自然清掉任务。
  * **`invoke` 加 `values`（字段名 → 原始值）**：写方法用，读方法给了报错（静默忽略一个用户
    以为生效了的载荷，比报错糟得多）。这套判定是**调用方的请求错误**，与「function not found」
    同类，在 `execute` 之前拦下，因此**不进** `service-failures`（那是设备故障记录）。
    写方法的返回仍是空对象：`ModbusHistoryService.record` 把非空 `values` 当**采集样本**落库，
    回显写入值会把写回声灌进采集历史曲线。
  * **`ModbusService.VERSION` 1 → 2**，校验器硬拒旧口径 —— `version` 字段就是为这件事留的。
    让旧定义**响亮地失败**，而不是被 `getJsonObject` 静默读成 null 之后报一句与病因无关的
    「request.slaveId is required」。
  * **function 这一族统一改名**：`ModbusServiceFunction` → `ModbusPolling`、
    `ModbusServiceRequest(Field)` → `ModbusPollingRequest(Field)`、原 `ModbusServiceField` →
    `ModbusPollingResponseField`，连它的三个嵌套类一起走全名 ——
    `ModbusPollingResponseFieldValue` / `…Bit` / `…Alarm`。两条理由：
    同一个 `ModbusService` 的子结构不必各自再背一遍 `Service` 前缀；而 Request / Response 这一对
    说的是「报文哪一侧、怎么编解码」—— 原类挂在 `function.response` 上、职责是解应答，
    叫 `ReadField` 会和「读请求」混淆（读请求里根本没有命名字段）。
    嵌套类跟着父类走全名，是为了 **每个名字都能从上一级推出来**（省下的那几个字不值一次猜测）。
    Codec 与测试随类名同步（`ModbusPollingCodec` / `ModbusPollingRequestCodec` / …），
    **包名不动**（`db.modbus.service.function.*`：它确实是「服务的方法」，读起来仍成立）。
    `PollOnce`（invoke 接口的请求体，与上面这几个类无关） **不改名**。
    `ModbusService` / `ModbusServiceService` / `ModbusServiceResource` / `ModbusServiceValidator` /
    `ModbusServiceHistory` / `ModbusServiceFailure` 这批「服务一级」的名字也不动。
  * **`function.response` 从裸数组包成对象**（`ModbusPollingResponse`，内含 `fields[]`）。原来
    `request` 是对象、`response` 却是一根裸数组，两侧读起来不是一个句式；包一层之后
    `request.fields[]` / `response.fields[]` 对称，而且应答级的东西（异常码到文案的映射、
    缺省字节序、期望帧长）有了落点，将来加它们不用再动 `function` 的形状。
    刻意**不做** `List<ModbusPollingResponse>`：一帧请求只对一帧应答，消费端
    （`ModbusResponseParser.parse(fields, frame)` / 影子对账 / 告警遍历）要的始终是「一个有序
    切片表覆盖一个数据区」，套一层 List 那边只能 flatten 或取 `[0]`；真要「一次调用多个区段」，
    该加的是 `requests[]` / `responses[]` 这一对。写方法因此是**整个 `response` 键不出现**
    （不再有「空数组」这种写法），校验文案里的路径也跟着变成
    `functions[0].response.fields[1].bytes`。`ModbusPolling.responseFields(function)` 把
    「function 为 null / response 缺失 / fields 为空」三段式判断收在一处（十来处调用点因此更短，
    校验器也多一层：读方法拿到空表本身就是要报的错）。
  * **第二节（示例）补了一张「键按方法方向出现 / 消失」的表**：这份 JSON 里「键在不在」本身就是
    语义（值为 null / 列表为空的键一律不写，`codec` 的既有口径），读写两侧的键本来就不一样 ——
    读方法给 `quantity`、不给 `fields`，写方法反过来，`response` 更是只有读方法才有。
    示例文件同时演示了这两侧，照抄时认准方向；顺手说明顶层 `"id"` 是创建时被忽略的占位。
  * **本版只做后端**：`webapp-matrix` 未同步（编辑器发的是 hex 字符串、`invokeService` 只发
    `{service, function}`、`functionFcOf` / `isReadFunction` / `request.frame.ts` 都按旧结构写），
    **合入后前端不可用**，需要跟着改：request 结构化表单、写字段配缺省值、详情页写入弹窗填值。
  * 存量迁移：脚本 `scripts/migrate-service-request-v2.sh`（GET → 换掉 `request` → PUT 回读）。
    读方法**无损**（slave/fc/start/quantity 四个数在帧里逐一对应，`response` 字段一个字节不改、
    只是从裸数组包成 `{fields: [...]}`，CRC 丢掉由后端现算）；
    写方法只反解 05/06（补字段名 `写入值`，迁移后请改名），并顺手摘掉写方法的 `interval` / `polling`；
    **0F/10 拒绝处理** —— 数据区能反解出值，但字段名与每段的类型 / 字节序无从得知，只能手工重建。
    用法：`BASE_URL=… TOKEN=… ./scripts/migrate-service-request-v2.sh <spaceId>:<serviceId> …`，
    先 `DRY_RUN=1` 看一遍要改成什么。PUT 需要**空间管理员**。
  * **迁移必须在新后端部署之后**：旧后端把 `request` 当字符串解，结构化 request 的 PUT 必然失败
    （自检：GET 回来的 `version` 已经是 2 就说明部署好了；已迁移的服务脚本会跳过）。
    staging 上当时只有 2 条服务、都是单条读方法（`硬拓PT100-1` / `11`），**截至本次提交尚未迁移**。
  * 整库迁移走库、不经 HTTP（因此不需要空间管理员 JWT）：`docs/migrations/modbus-service-request-v2.js`，
  默认**只读演练**、`APPLY=1` 才落库。与上面那个 shell 脚本同一套反解口径，两处不同：
  其一，**0F/10 也还原**（0F 逐位、10 逐寄存器）—— 帧逐字节不变，代价是「保帧不保语义」，
  原来一条 int32 占两个寄存器会拆成两个 uint16 字段、界面上看到两行；
  其二，写库前把还原出来的 request 用一份 JS 组帧算式重新组帧，与库里原帧**逐字节比对**，
  不一致就连同它所在的那条服务一起拒绝迁移（原帧 CRC 不匹配只告警：那是原帧本来就发的坏帧）。
  * **staging 试迁过一次、已按备份还原**：`硬拓PT100-1`（`6aa7e7959ce0a89b9445c8ba`）与 `11`
    （`6aa9d1d7eb0e3568f2dc24aa`）两条升到 `version: 2` 后，轮询任务立刻开始失败 ——
    部署在 `matrix.openxiot.cn` 的后端**仍是 v1**（报错里的 `ModbusServiceFunction` 是 v1 的类名），
    读到结构化 `request` 直接解码失败：`readString can only be called when CurrentBSONType is
    STRING, not when CurrentBSONType is OBJECT`。还原后轮询即恢复（`polls.lastStatus` 回到 `OK`）。
    **顺序是硬约束：先部署 v2 后端，再跑库迁移**（HTTP 那条路相反 —— 它要求后端先新，
    因为 PUT 由新后端校验）。v2 上线后直接 `APPLY=1` 重跑本脚本即可（幂等）。
    迁移本身的正确性已独立验证过三件事：反解出的 request 经后端 `ModbusFrameCodec.encodeRequest`
    重编后与库里的原帧逐字节相同（8 种功能码 15 个帧全过）、过 `ModbusServiceValidator.validate`、
    以及除 `version` 与 `request`/`response` 的形状外一个字段都没动。

### 2026-09-16

* **阈值告警的第四个边沿：`handled == true` 也关旧行、开新行**（`CLOSE_HANDLED`，页面文案
  「处理后再报」）。起因是一条 **语义反了**的现场事实：「处理」的意思是「我知道了， **以后还有
  越线再提醒我**」，而不是「这事到此为止」；在那之前，一条已处理而未恢复的行会一直挂着挡住
  后续越限，判定每轮都落在稳态零写那一支，用户再也收不到提醒。改动只有四处：
  `ModbusAlarm`（加常量）、`ModbusAlarmPolicy`（`judge` 多一支 + `Decision` 多一张
  `handledCloses`）、`ModbusAlarmRepository`（投影加 `handled`，值型 `String` →
  `OpenAlarmRule`）、`ModbusAlarmService.apply`（第四段关闭）。三条口径记在这里：
  * **不动任何已落库的行**：只加了新的关行判据，行上的定义快照 / `handledBy` / `ruleId` 都不改写。
    旧行留着 `handled=true` 与它的处理人（那是**当时**的记录），新行 `handled=false` 且不带
    `handledBy` —— 回执不继承，新的一条要重新处理。
  * **顺序即语义**：`judge` 的 `else if` 链里换规则的检查排在处理检查**之前**（规则换了又恰好
    处理过 ⇒ 只记 `SUPERSEDED`）；值恢复正常时走 `VALUE` 而不是 `HANDLED`（**值恢复才是实情**）；
    全 `SKIP` 时什么都不做（截断 / 畸形的应答既不关也不重报）。
  * **`OpenAlarmRule` 放在 `db` 包**：判定要的资料从「出值名 → ruleId」变成「出值名 →
    (ruleId, handled)」。放 `api` 会新造一条 `db → api` 的反向依赖（现有的箭头是
    `ModbusAlarmPolicy` → `db.modbus.service.*`，而 `ModbusAlarmRepository` 一个 `api`
    包都不 import）。详见 `Modbus-Alarm.md` 第十三节 2026-09-16 那条。

### 2026-09-14

* **数据看板（首页）的聚合统计接口** `GET /matrix/v1/statistics/overview/{spaceId}`（新包
  `api/statistics`：`StatisticsResource` + `StatisticsQueryService` + `StatisticsCodec`，
  见 `API.md` 第 8 节）。这一节只在 MODBUS.md 记与本方案相关的三处口径：
  * **为什么不复用告警 / 故障的清单接口**：它们都带条数上限（200 / 1000），`summary` 也只统计
    本次返回的那一批 —— 拿它们算「今日告警 / 告警曲线 / 类型分布」得到的是**被截断的下限**，
    而卡片和曲线上完全看不出来。新接口不打 limit，量级由 31 天的窗口上限兜住。
  * **服务这一次读取同时给出总数、按点表分组、以及告警与故障要逐服务查的 id 清单**
    （`ModbusServiceRepository.findConfigKeysBySpaceId`，与 `findBriefBySpaceId` 同过滤、只投影
    `configSnapshot._id`；`_id` 由 Mongo 默认带出）。三者同源，故「分组的条数之和 == 服务总数」是构造出来的；
    这也是没有复用 `ModbusServiceService.subtreeServiceIds` 的原因（它不带点表分组键，点表得再查一次）。
    分组键仍是**点表 id**（现在是快照里的 `id`，文案层说是 `configId` 也行），由前端解成「厂家 型号」。
  * **告警与故障仍逐个 serviceId 各查一次再内存归并**，新增
    `ModbusServiceAlarmRepository.findTimeline` / `ModbusServiceFailureRepository.findTimeline`
    （**不打 limit、不排序**，只投影 `at`(+`text`)）：索引 `(serviceId, pollingIndex, at)` 跨服务查
    会丢掉前缀，与 `ModbusHistoryQueryService` 的说明同一条；分桶也在内存里做（整点密集零填充），
    全后端依旧没有 `$group` / `Aggregates`。


* **阈值告警模型返工：一个出值一组规则**（`alarm` → `alarms[]`、规则加 `id`）。`ModbusPollingResponseField.alarm`
  与 `ModbusPollingResponseFieldBit.alarm` 各改成 `List<ModbusPollingResponseFieldAlarm>`，两处 Codec 与非空数组的
  「不出键」口径成对改掉；校验器改为「逐条 + 组内共性」（条数上限 8、`id` 非空/≤64/组内唯一，
  见第七节）；判定侧只留级别最高的一条开着（`Modbus-Alarm.md` 第四、五节）。零成本窗口期的
  最后一刻：集合一行没落过、也没有任何存量服务的配置里带过 `alarm` 这个键。

* **Modbus 阈值告警（第五步：查询服务与接口）**。`api/modbus/alarm/ModbusAlarmQueryService`（新）+
  `ModbusAlarmResource`（新，`/matrix/v1/modbus/alarm`：`GET /many/{spaceId}`、
  `POST /handle/{spaceId}/{id}`）+ `ModbusAlarmService.handle`（写回执）+ `subtreeServiceIds` 从
  `ModbusHistoryResource` 下沉到 `ModbusServiceService`（与故障清单共用）。几处值得记下的口径：
  * **路径另起一段**（不挂在 `/modbus/history` 或 `/modbus/service` 之下）：告警是第三件事 ——
    既不是配置也不是时间序列，而是**判定出来的事件**，权限粒度（成员可处理、不要管理员）与保留口径
    （永久保留、不挂 TTL）都不同。与历史接口当初另起一段同理。
  * **`/handle` 写之前先判归属**：路径上的 `spaceId` 只证明「你是这个空间的成员」，不证明
    「这条告警属于这个空间」。判法是「按 id 读出那行 → 它的 `serviceId` 在
    `subtreeServiceIds(spaceId)` 里吗 → 才写」—— 与列表查询用**同一个**函数，所以
    「列表里看得到的就能点」恰好成立，而不是两处各写一份、日后分叉。
  * **非法 id 在进库之前先挡**（`ObjectId.isValid`）：Panache 的 `findById(String)` 在构造
    `ObjectId` 时就抛普通 `RuntimeException`，落不到 `IllegalArgumentException` 的兜底里。
  * **「已经处理过了」算成功，不算错**：CAS 改动为 0 时再读一次，行在就照常返回它并记一条日志
    （重复点击 / 两人同时点）、行不在了才 not found。报错会为一件恰好如愿的事弹一条红字；
    那一次读就是为了分辨这两种解释。`handledBy` 记的是**第一个**处理的人。
  * **单服务那条路只是批量那条路的一个元素**（`alarms(...)` → `alarmsOfServices(List.of(id), …)`）：
    排序与截断的口径只有一份。空间级仍是「逐服务一条带索引的查询 + `limit + 1` + 内存归并」。
  * 单测：`ModbusAlarmQueryServiceTest`（7）+ `ModbusAlarmServiceTest` 补 6 例（`handle`）。
    全量 511 例中 510 绿（唯一红色是 `SpaceResourceTest`，要连 Mongo 的环境性失败）。
* **Modbus 阈值告警（第四步：定义变更对账与删除级联）**。`api/modbus/alarm/ModbusAlarmPlan`（新，
  纯函数判据）+ `ModbusAlarmReconciler`（新，定义变更 / 启动两个时机）+ `ModbusAlarmRepository`
  两个只投影坐标的查询 + `ModbusServiceService` 写入路径接线 + `ModbusHistoryService.purge` 第四个 `Uni`。
  几处值得记下的口径：
  * **拾遗另起一个 `ModbusAlarmReconciler`，没有挂进 `ModbusPollReconciler`**：轮询任务的对账挂在
    `ModbusPollScheduler` 的启动事件上，而那个事件只在 `modbus.poll.enabled=true` 时才起；
    告警判定挂在**采集链路**上、手动 invoke 也会评 —— 关掉轮询开关不该让残留从此没人扫。
  * **判据按新定义重算覆盖集，不做新旧差分**：差分要存旧定义，而写入路径手上只有 `merged`；
    重算只需要 `modbusService.functions` 一份输入，启动那趟复用同一个函数。
  * **对不认识的服务一律不动**（保守口径）：写入路径只传一个服务、只取那个服务的开行，这条在那里
    永远不触发；价值是拦住「调用方传半份服务列表」时的误关。代价是启动那趟不清理「服务已删除、
    级联又崩在中途」留下的行 —— 明说，不掩饰。
  * **删除走真删，不走 `DEFINITION` 关闭**：服务整个没了时那条告警再也没人能处理、也没人需要
    「当时为什么报」；关掉的语义是「定义不再覆盖它」，那是留给还在的服务的。
  * **写入路径的三样派生数据对账统一成 `reconcileDerived(String what, Uni<Void>)`**：轮询任务、
    影子、告警同壳（各自只记日志、不让一次定义维护跟着失败），日志里带上是哪一样。
  * 单测：`ModbusAlarmPlanTest`（15）+ `ModbusAlarmReconcilerTest`（14）。全量 498 例中 497 绿
    （唯一红色是 `SpaceResourceTest`，要连 Mongo 的环境性失败）。
* **Modbus 阈值告警（第三步：评估接进采集链路）**。`api/modbus/alarm/ModbusAlarmService`（新）+
  `ModbusServiceService.execute` 里的那一处 `chain`：采集成功后先记历史、再评告警（第五节）。
  几处值得记下的口径：
  * **两件并列的副作用，不是串在成功路径上**：各有各的失败重试（下一个仍越限的样本重试插入、
    下一个仍正常的样本重试关闭），两者都对库幂等 —— 所以这个特性**不需要**故障记录那套
    「先明细后影子」的顺序不变量。但**后果等级不同**：历史多一行是信息，告警多一条是页面上
    同一件事出现两次、而未处理计数永远不对，所以插入走唯一索引 + 重复键吞掉，关闭按整个开行标识
    `updateMany`。
  * **`SKIP` 的日志进程内去重**（上限 256，记满清空重来）：判不了的常见形态是**长期对不上**，
    5 秒一条就是一天一万七千行 WARN，刷到那个程度只会训练人忽略它 —— 而这条日志存在的全部意义
    就是被看见。去重只影响日志，不影响判定（`SKIP` 仍然不写库）。
  * **一次评估里的所有动作共用同一个时刻**：否则会写出「关了又开、开的时间还早于关的时间」。
  * **闸门有边界**：方法上**最后一个**告警被关掉时，闸门整个跳过这个方法，那条残留的开行
    **不在这里关**，归定义变更对账（`ModbusAlarmReconciler`，见上一条）扫。不为此松开闸门是因为残局的数量
    与「定义刚被改过」同阶，不该用**每次采样**的代价去兜。
  测试：`ModbusAlarmServiceTest`（13 例，Mockito 不连库）+ `ModbusAlarmPolicyTest` 扩到 16 +
  `ModbusServiceServiceTest` 补 2 例（`InOrder` 顺序、告警失败不改写响应）。
  全量 469 例中 468 绿；唯一红色的仍是 `SpaceResourceTest`（`@QuarkusTest`、要连 Mongo）。

* **Modbus 阈值告警（第一步：配置模型与校验）**。在此之前只知道「采到没采到」
  （`ModbusServiceFailure`：帧错 / 设备无应答 / CRC 不过）， **采到了但值不对看不见** ——
  温度已经 12℃ 了，曲线画出来了，没人盯着就不会有人发现。本次补上「读方法的出值可配阈值」这一层：
  `ModbusPollingResponseFieldAlarm`（新）、`ModbusPollingResponseField.alarm` 与 `ModbusPollingResponseFieldBit.alarm`（新）、
  `ModbusPollingResponseFieldAlarmCodec`（新）、判定口径 `api/modbus/alarm/ModbusAlarmPolicy`（新）、
  `ModbusServiceValidator.validateAlarm`；`validateInterval` 里那段取功能码的代码同时抽成了
  共用的 `isReadFunction`。 **采集链路上的接线、查询接口与前端在后续提交** ——
  这一步零 IO、可独立落地，没配告警的服务在采集热路径上一分钱都不多花
  （`ModbusAlarmPolicy.hasEnabledAlarm` 是纯内存判断，与 `ModbusHistoryPolicy`「绝大多数采集
  一个数据库操作都没有」同一条纪律）。几处值得记下的口径：
  * **告警挂在位上而不是父字段上**：parser 把每个具名位单独放进结果对象，位是独立的结果键。
  * **判定比的是解析后的值**（`scale` / `unit` 已生效），不是原始寄存器值 —— 用户的阈值是工程单位，
    曲线画的也是解析值，将来在图上画阈值线才对得齐。这条有测试专门钉住。
  * **判定是三态（`HIT` / `MISS` / `SKIP`），不是两态**：判不了（值缺失 / `NaN` / 类型对不上）
    必须区别于「没越限」，否则一次截断或畸形的应答会把一条开着的告警**关掉** —— 那是凭空恢复。
  * **不做防抖**（不回差、不持续时长）是明确的选择，代价写在 `Modbus-Alarm.md` 里。
  测试：`ModbusAlarmPolicyTest`（14 例）+ `ModbusServiceValidatorTest` 补 9 例 +
  `ModbusServiceCodecTest` 把告警收进示例体（encode∘decode 稳定性 + 「没配就不出键」）。
  全量 432 例中 431 绿；唯一红色的 `SpaceResourceTest` 是 `@QuarkusTest`、要连 Mongo，
  本机没有库所以读超时，与本次改动无关。

* **Modbus 阈值告警（第二步：告警文档与仓储）**。`db/modbus/alarm/` 新包：
  `ModbusAlarm`（库 `modbus`、集合 `service-alarms`， **带触发那一刻的定义快照** ——
  定义是活的、行是不可变的历史，只存引用会让改阈值悄悄改写历史告警的含义）、
  `ModbusAlarmRepository`、`codec/ModbusAlarmCodec`。这一步还没有写入者
  （评估在下一步接进采集链路），落地的是「行长什么样、怎么关、怎么处理」这套口径：
  * **状态就是 `openKey` 有没有值**：未恢复时有它，恢复时 `$unset` 掉；不另存一份告警态，
    于是「这个出值当前开着吗」是一次带索引的存在性查询。
  * **关闭按整个开行标识 `updateMany`，不定位到某条 `_id`** —— 消除了「幽灵告警永远关不掉」，
    也让采集热路径只需要存在性而不需要身份；过滤条件带 `exists("openKey")`，
    免得把已恢复行的第一次 `recoveredAt` 覆盖掉。
  * **处理用 `updateOne`（CAS `{handled: {$ne: true}}`），绝不 `replaceOne`**：
    处理与关闭是互不相交字段的写，替换会抹掉另一方刚写的字段。
  * **唯一部分索引刻意没建**（只建了采集侧与列表两个）。部署是 Postgres 底的 Azure DocumentDB，
    部分索引支持未实证：建了不支持就是一串被 `recoverWithNull` 静默吞掉的错（以为有护栏、其实没有），
    而**建错**（服务端忽略 `partialFilterExpression`）会让已恢复的行撞唯一索引、**告警开始丢**。
    冒烟步骤与两条回退档写在 `Modbus-Alarm.md` 第七节。缺它不产生脏数据，因为还没有写入者。
  * **汇总里 `open` 按 `recoveredAt` 数，不按 `openKey`**：两者本该等价，但汇总不能和它汇总的那批行
    自相矛盾 —— `recoveredAt` 才是前端能看到、据以渲染状态的那个字段。
  * **`encode` 每条都发 `id`**（有意偏离 `ModbusServiceFailureCodec`）：故障记录是纯日志、只能看，
    告警行有逐行动作（点「处理」）。`openKey` **绝不下发**：它把内部主键的拼法泄漏成接口契约，
    以后就改不动了。
    测试：`ModbusAlarmCodecTest`（9 例）+ `ModbusAlarmRepositoryTest`（8 例，纯静态）。
    全量 449 例中 448 绿（唯一红色仍是上面那个要连 Mongo 的 `SpaceResourceTest`）。
    设计见 `Modbus-Alarm.md`，余下步骤见 `Modbus-Alarm-TODO.md`。

* **整理：把「类型定义」与「接口层」分开 —— `typedef/modbus/{failure,frame}`、`api/modbus/service/{parser,validator}`**。
  纯搬家，行为不动（纯单测 378 例，搬前搬后同数同绿）。两地混层的理由：
  * `ModbusFailureType`（10 类失败）、`ModbusInvokeException`（带分类与远端原始码）、
    `ModbusFrameCodec`（RTU 帧编解码 + CRC）由 `api/modbus/{failure,frame}` 移到
    `typedef/modbus/{failure,frame}`：三样都不是接口层的东西 —— 协议与 JSON 契约一个字没变，
    但它们既被 `api` 用、又是「平台自己的类型词汇」，挂在 `api` 下会让别的层要用时反向依赖接口层。
    命名与前端 `webapp-matrix` 的 `typedef/define/modbus` 对齐。
  * `ModbusResponseParser`、`ModbusServiceValidator` 由 `api/modbus/service` 下移到同包的
    `parser` / `validator` 子包（与已有的 `params` 同形）：这两个是纯静态函数、不碰数据库，
    与 `ModbusServiceService` 的编排逻辑混在一个包里，看包名分不出「哪几个能单测」。
  * 搬家的两处副产物：`ModbusServiceValidator` 里给 Javadoc `{@link ModbusServiceService}` 用的
    import 跨包后成了**必需**（不是死 import）；`ModbusServiceCodecTest` 挪到被测类同包
    `db/modbus/service/codec`（其余三个 codec 测试都在各自实体包下，原地 `db/mongo/codec` 与两套口径都不挨着）。
  * **提醒后来者**：移动过类之后再跑增量的 `mvn test`，会看起来像改坏了 ——
    `target/test-classes` 里还留着旧包下的测试类，它们引用的旧包类已不在 classpath 上，
    surefire 一扫就 `NoClassDefFoundError: cc/openxiot/matrix/api/modbus/failure/ModbusFailureType`
    （**不是**代码问题）。搬家后跑一次 `clean test` 即可。
* **修：轮询器一次都没采到设备 —— 内存任务没带 `_id`**。现网现象（staging，
  `MODBUS_POLL_ENABLED=true` 的副本）：每周期一条
  `modbus poll task has no id, key: <serviceId>:<pollingIndex>`，间隔看着就是 `interval`，
  而设备一次都没被采到 —— 库里那一行只有写入路径插进去的那几个字段
  （`nextRunAt` 停在首次写入的时刻，`lastRunAt` / `leaseOwner` / `lastStatus` **始终为空**，
  说明认领（`claim`）一次都没成功过）。
  成因：对账重建内存任务时，id 取的是**计划对象**（`ModbusPollPlan.tasksOf` 现算的那条）身上的 `id`，
  而库里**已经有这一行**时它身上没有 id —— `_id` 是插入那一刻才有的。库里那一行是写入路径
  （`ModbusPollReconciler.applyService`）早就插好的，所以「先建服务、后起 poller」这个最自然的顺序
  **必然**踩到：任务每周期进一次轮、每次都在 `claim` 前被跳过，空转但不报错。
  两条一起改：
  * `_id` 由**计划**就生成（`ModbusPollPlan.tasksOf`）：插入原样落库、内存表用同一个，
    **不等插入回写**（回写时机由驱动/编解码器决定，不是这条链路能假定的）。
  * 对账以**库里那一行的 `_id`** 为准（`ModbusPollReconciler`）：计划对象上的 id 只在
    「这一轮要插入」时才等于落库后的 `_id`。
* **顺带收掉同族的第二处隐患**：库里那一行被删了又插（关掉轮询再打开、方法序号删了又加）
  会换 `_id`，内存任务停在旧 `_id` 上时认领永远「抢不到」—— 而且同样是**静默**的。
  所以 `ModbusPollTask.id` 不再是 final，由 `ModbusPollWheel.offer` 跟着对账同步
  （在飞的那一帧不能换**对象**，换的是对象上的字段，与 `intervalSeconds` / `ownerId` 同一条路）。
  `ModbusPollScheduler.claim` 里那条守卫从 warn 提到 error：它现在只可能是不变式破了。
  测试：新增 `ModbusPollReconcilerTest`（3 例：库里已有行 / 还没有行 / 行换了 `_id`；
  **已反向验证** —— 去掉「以库行为准」那一行，3 例挂 2 例），
  `ModbusPollPlanTest` 里的 `assertNull(task.id)` 随之改成 `assertNotNull`。纯单测合计 371 例。
  部署后 poller 重启即自愈：启动对账按库行的 `_id` 重建内存任务，而它那行 `nextRunAt` 是过去的时刻，
  于是**立刻补采一次**（「错过不补」的口径：只补一次），之后按周期走。
* **修：采集结果落不了库 —— 声明成 `Object` 的字段没有 codec**。上面那条修好之后轮询真的采到了
  （日志里每 ~30s 一条 `ManipulationService invokeActions`、往返 20ms），但每次都倒在写历史这一步：
  `record modbus history failed, service: …, function: 1, error: An exception occurred when encoding using
  the AutomaticPojoCodec. … Failed to encode 'ModbusServiceHistory'. Encoding 'value' errored with:
  Can't find a codec for CodecCacheKey{clazz=class java.lang.Object, types=null}.`
  —— 注意 `modbus.service-histories` 在 staging 上**这个集合根本不存在**：
  历史写入从来没成功过一次（不只是这次），手动 invoke 与轮询两条入口报的是同一个错。
  成因与轮询器无关：驱动的 `AutomaticPojoCodec` 是**按字段的声明类型**取 codec 的
  （`LazyPropertyModelCodec` → `PropertyCodecRegistry`），`public Object value` 走到兜底那格时
  拿 `Object.class` 去问注册表，没人认领这个类，编码第一步就炸。
  修法是补上「声明类型 = `Object`」这一格的兜底：新增 `db/mongo/ObjectPropertyCodecProvider`
  —— 驱动自己的扩展点，Quarkus 的 `MongoClients.configureCodecRegistry` 会把容器里所有
  `PropertyCodecProvider` 注册到那个 `automatic(true)` 的 `PojoCodecProvider` 上、
  且驱动把它们排在自带的 Collection / Map / Enum / Fallback **之前**，所以不需要任何额外配置。
  编码按**运行时类**取 codec、解码按 **BSON 类型**取（`BsonTypeClassMap`），与驱动自己的
  `DocumentCodec` / `MapCodec` / `IterableCodec` 同一口径；只认声明成 `Object` 的字段，
  其余类型一律交还驱动（声明成 `Number` / 接口 / 泛型变量的字段该报错就报错，不在这里被吞掉）。
  **边界**：`Map<String, Object>`（影子的 `fields`、空间的 `attributes`）本来就不会被卡住 ——
  驱动的 `MapPropertyCodecProvider` 对「值类型是 `Object` 的 Map」另有一条兜底（退回去用裸 `Map` 的 codec）；
  本 provider 因为排在 Map 之前会把那张 Map 的值槽接过来，口径与它原来那条一致，
  所以「map 的值能落库」不是本类的功劳（`Modbus-History.md` 第三节记了这一条）。
  测试：新增 `ObjectPropertyCodecProviderTest`（7 例，**纯单测不连库**：注册表按
  `MongoClients.configureCodecRegistry` 原样搭 + `BsonDocumentWriter`/`BsonDocumentReader` 走一遍编解码；
  含一条「摘掉 provider 时同一个写就失败」的失败形态钉子）。**已反向验证** —— 把 provider 的
  `get()` 改成恒返回 `null` 重跑：四个 `value` 用例（标量、字符串 / 布尔 / 空、嵌套数组与内嵌文档、
  数值类型）报出与现网一字不差的错，两个地图用例仍绿（与上面那条边界一致），
  所以那四例确实钉住了这次修的东西。纯单测合计 378 例。
  装配另有一条本机实证：`@QuarkusTest` 开 `io.quarkus.arc.processor` 的 DEBUG 能看到
  `Unremovable - programmatic lookup: … ObjectPropertyCodecProvider` ——
  那条 `Instance<PropertyCodecProvider>` 查找正是 `MongoClients` 取它的方式，也是它没被 ArC 删掉的原因，
  所以这个作用域注解（`@Singleton`）不是可有可无的。

### 2026-09-13

* **历史明细保留策略：`service-histories` 挂 TTL 索引，保留 90 天**
  （`Modbus-History-TODO.md` 第四步；设计见 `Modbus-History.md` 第六节）。
  `modbus.history.retention-days` 缺省 90（**不设 env 也生效**，部署起来就开始删 90 天前的明细），
  `<= 0` = 关（并把已有的 TTL 索引删掉）。启动时对一遍：没有就建、秒数变了就 drop 再建。
  判定集中在纯函数 `ModbusHistoryRetention.decide`；仓储失败留告警 ——
  保留没生效意味着历史永远不会被删，不能静默。故障记录与当前值**不设**（理由见该文档）。
  测试：`ModbusHistoryRetentionTest`（8 例，四种动作 + 天数换算）+
  `ModbusServiceHistoryRepositoryTest`（7 例，喂 `listIndexes` 的真实返回形态钉住「认哪条是 TTL 索引」——
  认错会静默失效或每次启动白重建一遍；其中一例直接用 staging 冒烟时原样吐回的那条索引文档）。
  纯单测 320 例，合计 369 例。
  **注意 `modbus.index.enabled=false` 的测试进程不跑这段**（测试配置里关着建索引），
  所以保留策略的实跑只能在本机 dev 或部署上看日志。
* **部署前提纠正：staging / prod 是 Azure DocumentDB**（原 Cosmos DB for MongoDB vCore，
  `Microsoft.DocumentDB/mongoClusters`，引擎是建在 PostgreSQL 上的开源 DocumentDB），
  不是 RU 版 Mongo API：**按集群规格计费、不按操作计 RU**，**分片由服务托管**
  （应用侧不能也不该指定分区键，第四步那条「建集合时定分区键」随之结论化）。
  查询与写入**恒带 `serviceId`** 的口径保留（索引打头 + 将来迁移成本）。
  TTL 在 DocumentDB 上是否真的会删文档**已冒烟实证可用**（staging，serverVersion 8.0.0：
  临时集合挂 60 秒 TTL、`at` 是 2 分钟前的文档在 **t+30s 被服务端删掉**），批删任务那套兜底不做。
  同一趟还确认了 `modbus.service-histories` 尚不存在，所以 90 天保留不会删掉任何现有数据。
* **`interval` 下限 3 → 5 秒**：`ModbusServiceValidator.MIN_INTERVAL_SECONDS`、
  `ModbusServiceValidatorTest`（含新增的「4 秒被拒」一例）、第七节文案、
  前端 `device.service.editor.ts` 的常量与 `ModbusService.ts` 的 JSDoc，六处同步。
  （本节下方的历史叙述保留当时的 3 秒，不回改。）
* **补充说明：`invoke` 会顺带落历史**（第五节）：成功写 `service-histories` 并更新
  `service-last-polling`，失败写 `service-failures` —— 手动调用一次 = 记一次，
  与轮询器同一个入口。记录写失败既不改写成功响应、也不改写失败响应。
* **轮询器落地**（`Modbus-History-TODO.md` 第三步全部；设计见 `Modbus-Service-Poller.md`）：
  定义里的 `interval` 从「一份没有执行者的配置」变成真有人按时去调。**同一个 jar 双部署**
  （api N 副本 + poller 1 副本），靠 `modbus.poll.enabled` 区分，**缺省关** ——
  一次发布让多个副本一起采集 = 同一条 RS485 上出现重复帧。
  * **新集合 `modbus.polls`**（`db/modbus/poll`）：`serviceId / pollingIndex / did / interval /
    nextRunAt / leaseOwner / leaseUntil / lastRunAt / lastStatus / lastError / failCount`；
    索引 `(serviceId, pollingIndex)` 唯一 + `nextRunAt`。**纯派生数据**，随时可全量重建。
  * **入表口径与原定义分开判**（`ModbusPollPlan`）：读方法（复查 `ModbusFrameCodec.isRead`）+
    有 `interval` + `polling != false`。第三条是**故意与校验器重复**的 ——
    让服务端定时往寄存器里写值是危险动作，库里可能已有历史数据或被直接改过，
    最后一道闸必须在**执行侧**。
  * **对账**（`ModbusPollReconciler`）三种时机：写入路径（create / update / delete，
    与 `purgeHistory` 同口径的副作用，失败只记日志）、启动、周期兜底。
    **写入路径买不到「立即生效」**：定义在某个 api 副本上改，poller 是另一个进程，
    要到下一次周期对账才知道 —— **改动生效延迟 ≤ `modbus.poll.reconcile-seconds`（缺省 5 分钟）**。
  * **调度循环**（`ModbusPollScheduler` + `ModbusPollWheel`）：内存时间轮，JDK
    `ScheduledExecutorService` 单守护线程（不用 `vertx.setPeriodic`：它的回调在 event loop 上，
    而调度步要读库，`await()` 会被 Quarkus 拒掉）。三条细节：**按 `did` 串行**（同一条总线不能并发，
    设备忙的任务留在轮里等）、**在飞 = 不在轮里**（重入保护因此是结构性的，不需要标记判断）、
    **认领一次原子写**（`claim` = 租约 + `lastRunAt` + `nextRunAt`，每次采集 2 次写而不是 4 次；
    **不做提前释放** —— 被中断的那一帧可能已在总线上，立刻放开会让别的副本重发）。
  * **`ModbusHistoryService` 加了一对「基准由调用方给」的入口**（4 参 `record` / `recordFailure`，
    返回新基准；3 参入口变成「读库取基准 → 委托 4 参」）。内存基准（`ModbusPollBaselines`）
    **只在 poller 单副本时成立**，**故障态不吃**（回退读库），冷启动不预热。
  * **观测**：`lastRunAt` / `lastStatus`（只有 `OK` / `FAILED`，**跳过不是状态**）/ `lastError` /
    `failCount` 落库，查询接口 `GET /matrix/v1/modbus/poll/{spaceId}`（空间成员）连内存指标一起给；
    心跳**不做成逐次写库**，只在内存里记、由接口读出去。
  * **影子对账**（`ModbusLastPollingPlan`）：定义里删掉的 `pollingIndex` 是孤儿、必须主动删
    （否则当前值接口一直显示一个不存在的点位）；`response` 里删掉的字段键写路径顺手剪掉即可
    （`record` 整体替换 `fields`，最坏拖到保活周期就自愈）。
  * 测试：新增 66 个纯单测（`ModbusPollScheduleTest` / `ModbusPollPlanTest` / `ModbusPollWheelTest` /
    `ModbusPollBaselinesTest` / `ModbusLastPollingPlanTest` + 两个既有测试文件的扩充），另加
    `ModbusPollingResourceTest`（`@QuarkusTest`，查询接口 + 鉴权 + 空运行时状态，5 例）。
    **`@QuarkusTest` 本机是可以跑的**：只要连库的东西都 `@InjectMock` 掉（`DeviceResourceTest` /
    `ModbusConfigResourceTest` / `ModbusServiceResourceTest` / `ModbusPollingResourceTest` 四个共 49 例全绿）；
    跑不了的只有 **真的要连 Mongo** 的那些（`SpaceResourceTest.getGraph_succeeds`）。
    纯单测 305 例，合计 354 例。
  * **仍未做**：ACA 侧 `maxReplicas=1` 由 ops 保证（代码只提供开关与那条启动日志），
    并发上限 20 与 DTU 实际承载是否匹配需要一次实测 —— 两件都记在
    `Modbus-Service-Poller.md` 第九节。
* **设备归属改为「设备所在的根空间」**（修正设计错误：原先给 manipulation 传的是调用者所在的
  `X-Org-Id`，而 manipulation 是按 `Ownership(did, appId, ownerId)` 三元组查归属的，
  `ownerId` 是**设备归属的隔离键**、不是「谁在调」）：
  * `ManipulationService` 的 10 个方法参数 `orgId` → `ownerId`，类注释写明它等于**设备所在根空间 id**；
    调用点（`DeviceResource` 9 处、`ModbusServiceResource.invoke`）改传 `space.rootSpaceId()` ——
    `requireSpaceAdmin` / `requireSpaceMember` 本来就返回 `Uni<SpaceEntity>`，**一次库都不用多查**。
  * `SpaceEntity` **加 `rootSpaceId()`**（祖先链第一个；根空间即自身），**删死字段 `rootId`**
    （全仓没有任何赋值点，库里恒为 null）：`SpaceOn(SpaceEntity)` 改用它，设备的 `space.rootId`
    从此有真值。`service-manipulation` 一行未改（它只解 claim、无空间概念）。
  * **跨根空间移动设备被拒绝**（`DeviceService.updateSpace` → `checkSameRoot`）：归属记录挂在老根空间上，
    搬过去只会天天报 "device not owned"。要跨根空间就先删掉再添加。
    `DeviceUpdateSpaceRequest.rootSpaceId` **删掉**（根由服务端从目标空间现算，客户端传的只是它自己的认知），
    前端 `MoveDeviceRequest` 同步。
  * **删 `ModbusService.orgId`**（只被 encode 给前端、无任何业务逻辑读它）连同它的索引与校验豁免说明；
    前端 `ModbusService` / `ModbusServiceCodec` 同步。**`ModbusConfig.orgId` 不动** —— 那是点表跨组织
    隔离的键，用途完全不同。
  * 前端同步删 `SpaceEntity.rootId`（内含两处赋值：新建项目、添加子空间）。
  * **存量设备不迁移**：改完之后 staging 上已注册设备都会报 "device not owned"，
    要用**原来的 did + key** 重新注册一次（key 是注册时调用方给的，不会自动生成）；
    老的 org 口径归属记录成为新接口既看不到也删不掉的孤儿，只能手工清库。
  * 验证：`DeviceServiceTest` 补「未落点直接落位 / 同根可移 / 跨根被拒 / 已在目标根空间不查库」四例。
* **新增 `ModbusPolling.polling`：自动轮询开关**（需求：周期只表达了「多久跑一次」，
  还需要一个「现在跑不跑」的开关 —— 现场停一台设备的采集不该把配好的周期删掉）：
  * `true` / `false` / 缺省三态：`true` = 按 `interval` 跑；`false` = 保留周期、暂停（随时可再开）；
    **缺省 = 按 `interval` 判定**（设了周期即启用）—— 与加这个字段之前的定义保持兼容，
    老数据不会因为字段一上线就集体停掉。
  * 校验只有一条：**`true` 必须同时给 `interval`**（开了轮询却没有周期是自相矛盾的）。
    写方法上的限制沿用 `interval` 那条规则（`polling: true` + 无周期在写方法上先被这条拦下）。
  * `false` 不带周期的写法不落库：那种情况与「没配轮询」等价，前端也不会发。
  * 取值范围 `3 ≤ interval ≤ 3600` 秒：下限 3 秒（RS485 是共享总线，周期太密会把总线吃满），
    上限 3600 秒（再长就该由人手动 invoke，而不是挂个几乎不跑的定时器）。调整范围改
    `ModbusServiceValidator.MIN/MAX_INTERVAL_SECONDS`，同时要同步前端
    `device.service.editor.ts` 那份同口径常量（输入框的 nzMin/nzMax）。
  * 示例 `modbus-service-example.json` 第一个方法补 `"interval": 5, "polling": true`；
    `ModbusServiceValidatorTest` 补开关三态通过 / `true` 无周期被拒 / 写方法上被拒三例，
    `ModbusServiceCodecTest` 的示例夹具补 `polling`（收发都要带上）。
* **新增 `ModbusPolling.interval`：服务端自动调用周期（秒）**（需求：方法不再只靠人手动
  invoke，服务端按各自的周期自动执行；周期是 per-function 的，同一服务里各方法可以不同）：
  * **只挂在读方法上**（fc 01/02/03/04）：写方法的应答是请求回显，周期调用等于让服务端周期性地往
    寄存器里写值，是危险动作。功能码取请求帧第二个字节判定（`ModbusFrameCodec.isRead`），
    写方法填了直接拒。
  * 缺省 / `null` = 不自动调用（只手动 invoke）。**不用 0 表达**：0 既像「不轮询」又像「尽可能快」。
  * 取值范围 `3 ≤ interval ≤ 3600` 秒：下限 3 秒（RS485 是共享总线，周期太密会把总线吃满），
    上限 3600 秒（再长就该由人手动 invoke，而不是挂个几乎不跑的定时器）。调整范围改
    `ModbusServiceValidator.MIN/MAX_INTERVAL_SECONDS`，同时要同步前端
    `device.service.editor.ts` 那份同口径常量（输入框的 nzMin/nzMax）。
  * **执行侧的调度器尚未落地**：这个字段目前只是定义。落库后怎么跑已经定下来了（同代码双部署、
    按 `did` 串行、任务表 + 租约、内存基准 ⇒ 单副本），见 `Modbus-Service-Poller.md`。
    其中「同一设备多个周期合并成一帧读」明确**不做**：`request` 是前端编好的整帧，
    后端手里没有寄存器地址与数量，当前数据模型下合并不了（那份文档第二节写死了这条）。
  * 示例 `modbus-service-example.json` 第一个方法补 `"interval": 5`；`ModbusServiceValidatorTest`
    补读方法通过 / 写方法被拒 / 上下限越界三例，`ModbusServiceCodecTest` 的示例夹具补 `interval`。
* **01/02 读位改为逐位命名：点表 `ModbusCommand.bitNames` → 应答字段 `bit-list[]`**（需求：01/02
  读多个线圈时只有一个字段名，「字段名称」表达不了「第 N 位是什么」；位是位区里的子结构，本就
  不该按字节切成多个字段）：
  * `bitNames`（01/02 专用）= 逐位命名，元素 `{offset, name}`；`offset` 是 **0 基位偏移**（从起始
    地址起，与 0F 的 `Coil.offset` 同口径），可只命名其中几位。`fieldNames` 收敛为 **03/04 专用**
    （01/02 填了报错：`fc 01 names each bit via bitNames`），因其应答字段只有一个、整段位掩码的名字
    由生成方按功能名称兜底。
  * `ModbusPollingResponseField.bitList`（对外 JSON `bit-list`）= 位区字段里的具名位，元素 `{offset, field}`；
    生成服务时由 `bitNames` 直接落过来。解析器在字段值（整段位掩码）之外，把每位再作为一个 0/1
    取值补进结果，key 即该位的 `field`。
  * 位区取值口径修正为 **LSB-first**（第 i 位 = 第 i/8 字节的第 i%8 位，第 0 位即起始地址）：
    ≤ 8 位（单字节）结果不变，9–16 位原先按字节序大端拼装的掩码（`03 01` → `0x0103` 而非
    `0x0301`）从此与位序号一致。
  * 校验：点表侧 `bitNames` 的 offset 必须落在 `[0, quantity)`、不重复、名称非空；服务侧 `bit-list`
    的 offset 必须 `< bytes × 8`、组内不重复、名称非空，且与同应答内其它出值名不重复（新增了
    **字段名唯一性**校验：出值名重名会在解析结果里互相覆盖）。
  * 示例 `modbus-service-example.json` 增加 `读8个开关量`（01 读 8 位 + `bit-list`）。
  * 测试：`ModbusConfigServiceTest` 补 `bitNames` 合法 / 越界 / 重复 offset / 空名 / 01 误填
    `fieldNames` / 03 误填 `bitNames` 六例；`ModbusResponseParserTest` 补位清单展开、跨字节位序、
    越界抛错三例；`ModbusServiceValidatorTest` 补重名与位清单四例；`ModbusServiceCodecTest` /
    `ModbusConfigCodecTest` 补 `bit-list` / `bitNames` 的编解码往返。
  * 前端 webapp-matrix 同步（命令对话框 01/02 的「数量」下改出「位名称」逐位命名）。
* **读操作的数量改为「值的个数」，并新增应答字段名称 `ModbusCommand.fieldNames`**（需求：示例里的
  `读多个温度值`（三个各 2 字节的 uint16 字段）在旧口径下表达不出来 —— 03/04 的数量被锁死为类型跨度，
  一次只能读一个参数、出一个应答字段）：
  * `quantity`（03/04）= **值的个数**，每个值占「数据格式」的寄存器跨度（int16/uint16→1、
    int32/uint32/float32→2）；`string` 的数量仍是字符串长度（寄存器数）、整段算一个值。
    请求帧里的数量 = 数量 × 跨度（`ModbusFrameCodec.readQuantity`，03/04 非 string），01/02 仍是位数。
    校验随之改为「数量 × 跨度 ≤ 125」（原先的「数量必须等于类型跨度」删除）。
  * `fieldNames` = 应答字段名称，个数即应答字段数（01/02 恒 1 个；03/04 非 string 为数量个、string 为 1 个）。
    生成服务时逐个填进 `ModbusPolling.response.fields[].field`；整体缺省允许（存量点表），
    一旦给出就必须个数一致且逐项非空（名称会直接成为 invoke 返回值的 key）；写操作没有应答字段，填了报错。
  * `ModbusCommandCodec` 只多一个 `fieldNames` 键（空列表不输出）。
  * 测试：`ModbusConfigServiceTest` 的「数量必须等于类型跨度」用例改为「数量 × 跨度超限」与
    `fieldNames` 个数 / 空名 / 写操作误填四例，并补 uint16 读 3 个值的通过用例；
    `ModbusFrameCodecTest` 补「数量 × 跨度」「string 数量即长度」两例。
  * 前端 webapp-matrix 同步（点表命令对话框的「数量」+「字段名称」）。
* 说明：`ModbusFrameCodec.decodeResponse`（旧的点位式读值，按类型取数据区首个值）**不在 invoke 链路上**
  （invoke 走 `ModbusResponseParser`），多值读只解第一个值，仅为存量单测保留。

### 2026-09-10

* **`GET /matrix/v1/modbus/config/visible` 不再要求组织成员**（点表本身的增删改查仍按组织鉴权，不动）：
  设备映射（服务）已按空间判，读点表却被「组织成员」挡着，未选组织 / 账号没有组织时选不到源点表，
  连服务都建不出来。现在**未携带 `X-Org-Id`** 时直接回公开集（`ModbusConfigService.listVisible(null)`
  → `repo.listPublic()`），不再报 `X-Org-Id header is required`；**带了组织头才校验成员** ——
  这个头由客户端自己填，不校验就能拿别人的组织 id 读走其私有配置（组织 id 会从设备 URN 里露出来）。
  要彻底不校验，去掉 `getVisible` 里的 `requireMember` 分支即可。
* **接口全空间化（推翻下面那条「删除权限放宽到空间管理员」的半吊子口径）**：7 个接口的鉴权维度由
  **组织** 改为 **空间**，与 `DeviceResource` 完全同口径 —— 增删改要**空间管理员**、查询与 `invoke` 要
  **空间成员**，空间 ID 移入 Path（`/one/{spaceId}`、`/one/{spaceId}/{id}`、`/parent/{spaceId}/{did}`、
  `/many/{spaceId}`、`/invoke/{spaceId}`）。起因：服务天然属于设备所在的空间，而「只有组织管理员能增删改」
  把项目管理员（空间 admin 但不是组织管理员）挡在门外，同一个页面上「设备能删、服务删不掉」。
  * 资源层改用 `requireSpaceAdmin` / `requireSpaceMember`（照抄 `DeviceResource`），
    上一轮为删除单独加的 `requireDelete` / `requireSpaceAdminOfDevice` / `checkSpaceAdminOrRoot`
    根空间兜底链与 `DeviceService` 注入一并删除（`spaceId` 由前端统一传当前项目根空间，兜底不再需要）。
  * Service 层去 org 参数：`get(id)` / `update(id, patch)` / `deleteById(id)` / `listByParent(did)` /
    新的 `listBySpaceId(spaceId)`；`getById`（裸取特例）删除 —— 现在「按 id 查」就是常态。
    仓储删 `findByIdAndOrg` / `deleteByIdAndOrg` / `listByOrg`，新增 `listBySpaceId`（`device.space.spaceId`）。
  * `invoke` 仍把 `X-Org-Id` 往下传给 manipulation（调用归属），但取服务不按 org 约束。
    **（口径已改：归属应传设备所在的根空间 id，见 2026-09-13 首条）**
  * `ModbusServiceValidator` 去掉 `orgId is required`；`orgId` 字段保留，只记录创建时的组织。
    **（该字段已删，见 2026-09-13 首条）**
  * **破坏性变更**：路径全变，前后端必须一起发布（已确认 webapp-matrix 之外的客户端都没引用这批接口）。
  * 测试：`ModbusServiceResourceTest` 由「删除权限」专项重写为空间口径（增删改要管理员、查询 / invoke 要成员、
    空间不存在、服务不存在），`ModbusServiceValidatorTest` 的 orgId 用例改为「不校验」。
* **删除设备时级联删除其 Modbus 服务**（需求：设备都没了，服务留着既查不出来也删不掉）：
  `ModbusServiceRepository.deleteByDeviceDids`（`device.did in [...]`，**不限组织**）、
  `ModbusServiceService.deleteByDeviceDids`；`DeviceResource.removeOne` / `removeDevices` 在 manipulation
  删归属成功之后、删设备行之前插一步级联（先删服务再删设备行，失败可整链重试）。点表不引用设备，不参与级联。
* **（已被本节首条推翻，仅存档）删除服务的权限放宽到空间管理员**：`DELETE /matrix/v1/modbus/service/one/{id}` 除组织管理员外，
  该服务依赖设备落点空间（含其根空间）的空间管理员也能删（与前端「项目管理员才显示删除」的口径对齐）。
  为此新增 `findById` / `deleteById`（**不带 org 约束**）与 `ModbusServiceService.getById` / `deleteById`，
  删除链改为「裸取服务 → 判权限 → 按 id 删」；设备落点优先取设备表现行值，设备行已不在时回落服务里的副本；
  子空间判定失败再按根空间判一次（子空间 accesses 是创建时拷的，后加到根空间的成员不会同步）。
* 新增 `ModbusServiceResourceTest`（组织管理员 / 空间管理员 / 根空间兜底 / 设备行已不在 / 无权限 / 服务不存在），
  `DeviceResourceTest` 补 `ModbusServiceService` 的 mock 并断言两处删除都有级联。
* **删掉 `slave.type`（推翻本节下面那条「先标记 `@Deprecated`、不删」的决定）**：点表不再记产品品类，
  后端删 `ModbusDeviceType` 与 `ModbusDeviceTypeCodec`，`ModbusSlave.type` 字段、`ModbusSlaveCodec` 的 type 编码、
  `ModbusConfigService.validate` 的 `type is required` 校验、以及 update 里「patch 缺 type 时保留既有值」的
  兼容逻辑一并移除（`slave` 现在整体替换）。存量文档里遗留的 `slave.type` 因 `@JsonIgnoreProperties` 被忽略，
  下次 update 落库时自然消失。前端 webapp-matrix 同步：`Modbus.ts` 删 `ModbusDeviceType` 与 `ModbusSlave.type`，
  设备信息对话框去掉「名字空间 → 设备类型」级联（连带 `ProductService` 依赖）、详情页去掉类型标签，
  点表类型 `ModbusDeviceConfig` 改名 `ModbusConfig`（与后端同名）。
* **文档整理**：删除方案一的 `MODBUS.md`，原 `MODBUS2.md` 更名为 `MODBUS.md`（本节即由此而来），
  并按实现校正正文：去掉已完成的实施步骤清单、补 `version` 校验、把「按空间反查」的方法名写成实际的
  `findBriefBySpaceId`。
* **删除方案一的全部代码**（本方案就此成为唯一路径）：删掉虚拟设备资源 / 服务
  （`ModbusVirtualDeviceResource` / `ModbusVirtualDeviceService`）、实体与仓储（整个 `db/modbus/virtual` 包：
  `ModbusVirtualDevice` / `ModbusMapping` / `ModbusDeviceInstance` / `ModbusVirtualDeviceRepository`）、
  拦截器与转发层（整个 `api/device/interceptor` 包：`DeviceInterceptor` + 产品实例客户端 `ProductService` /
  `ProductClient`；`DeviceManipulator`）以及 `ModbusVirtualDeviceResourceTest`。
  * `DeviceResource` 改为直接注入 `ManipulationService`：写设备 / 读影子 / 读写属性 / 执行方法原样转发，
    按设备分组下发的那层逻辑随拦截器一起去掉（`invokeActions` 现在只透传请求体）。
  * `DeviceService` 去掉为虚拟子设备做的 online 归一（`isModbusVirtual` / `withParentOnline` 及其 helpers）：
    `getBySpaceId` / `getByDeviceIds` / `getByDeviceId` 现在直接返回仓储结果。
  * 随之停用的 HTTP 路由：`POST /matrix/v1/modbus/virtual/one`、`DELETE /matrix/v1/modbus/virtual/one`、
    `GET /matrix/v1/modbus/virtual/instance/{type}`（**前端 webapp-matrix 仍有调用点，待同步清理**）。
  * `DeviceEntity.parentId` / `protocol` 字段保留（来自产品摘要，非虚拟设备专有）。
* `ModbusFrameCodec` 由 `api/device/interceptor/modbus` 移到 `api/modbus/frame`：它仍被本方案的
  `ModbusResponseParser` / `ModbusServiceValidator` 复用，包名不必再挂着已删的 interceptor。
* `ModbusSlave.type` 与 `ModbusDeviceType` 标记 `@Deprecated`（**不删**，保留回滚与兼容存量文档）：
  新的服务方案只认点表与依赖设备，不再需要产品品类/规范快照。注意 `ModbusConfigService.validate`
  目前仍要求 `slave.type.type` 非空，前端不传会报 `type is required`。
* 空间图的 `services` 每个元素补 `did` / `spaceId`（依赖设备及其落点），
  精简查询的投影相应地多带 `device.did` / `device.space.spaceId`。
* 空间图补齐 `services`：`ModbusServiceRepository.findBySpaceId` / `ModbusServiceService.getBySpaceId`
  （与 `DeviceService.getBySpaceId` 同形），`SpaceResource.getGraph` 用统一的 `bySpace` helper 并行取两个列表；
  服务只读 id / name（Mongo 投影），出图时包一层精简视图（`ModbusServiceCodec.encodeBrief`：id / name / type）。
* `ModbusServiceParent` 改名 `DeviceOn`（`ModbusServiceParentCodec` → `DeviceOnCodec`），
  并加 `space` 字段；`DeviceSpace` 改名 `SpaceOn` 并上移到 `cc.openxiot.matrix.db.space`，
  设备实体与 Modbus 服务共用（原来挂在 `db/device` 下，服务侧的依赖不该引设备包的类）。
* `ModbusService.parent` 字段改名 `device`，**JSON 键同步由 `parent` 改为 `device`**（前端请求体与响应都变），
  Mongo 查询路径 `parent.did` / `parent.space.spaceId` 一并改；校验文案改为 `device.did is required` 等，
  `ModbusServiceValidator.checkParent` 改名 `checkDevice`。
  列表接口的路径 `/matrix/v1/modbus/service/parent/{did}` 与 `listByParent` 方法名**未动**。
* `ModbusServiceRepository.ensureIndexes` 增加 `device.space.spaceId` 索引。
* `modbus-service-example.json` 的 `parent` 改名为 `device`，并补上 `space`。
* 按本节落地方案二：`ModbusService` 实体（含 device / functions / response 值对象）、
  `ModbusServiceRepository`（orgId / device.did 索引）、`ModbusServiceService`（增删改查 + invoke）、
  `ModbusServiceResource`（上表 7 个接口）。
* JSON 互转按「一个实体一个 Codec」拆开、放在实体的 `codec` 子包
  （`cc.openxiot.matrix.db.modbus.service.codec`），统一 `JsonObject encode(X)` / `X decode(JsonObject)`：
  `ModbusServiceCodec`（主键按十六进制串输出，另带 `JsonArray encode(List)`）、
  `DeviceOnCodec`（时名 `ModbusServiceParentCodec`）、`ModbusPollingCodec`、`ModbusPollingResponseFieldCodec`、
  `ModbusPollingResponseFieldValueCodec`。
* 应答帧的解析单独放在 `ModbusResponseParser.parse(fields, frameHex)`（复用了
  `ModbusFrameCodec` 的 CRC 与字节序原语），名字与 JSON 编解码区分开。
* `ModbusFrameCodec` 的 `checkCrc` / `reorder` 由 private 改为 public（仅可见性，行为不变，供本方案复用），
  并让 `reorder` 对非 2/4 字节宽度原样返回（原先会越界）。
* 定义校验从 Service 里抽出为 `ModbusServiceValidator`（`api/modbus/service`，纯静态函数，无状态、不碰数据库），
  `ModbusServiceService` 从 432 行降到 252 行；`invoke` 前的调用坐标检查复用其 `checkDevice`。
* 附纯单测 `ModbusResponseParserTest`（10 例：多字段偏移、取值表、缩放、字节序、异常帧）与
  `ModbusServiceCodecTest`（7 例：按示例报文解码、encode∘decode 稳定、id 十六进制串、value-list 键名、精简视图）
  与 `ModbusServiceValidatorTest`（12 例：顶层字段 / device / functions / request / response / value-list）。
