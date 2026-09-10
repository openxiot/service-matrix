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

## 三、数据模型（落库 `modbus`/`services`，Panache 生成 ObjectId，对外十六进制字符串）

```
ModbusService        orgId / name / version / configId / device / functions[] / creator / updater
DeviceOn             did / siid / aiid / argument / space  ← 依赖的设备：调用坐标 + 所在空间，前端创建时写入
SpaceOn              spaceId / rootId                     ← 设备落点，与 DeviceEntity.space 同一个类
ModbusServiceFunction index / name / request / response[]
ModbusServiceField   index / field / bytes / format / byteOrder / scale / unit / value-list[]
```

* `device.argument` = 依赖设备的方法**入参的 piid**，请求帧填在这里。DTU 承载数据的动作只有一个入参，
  故提到 device 级、对该服务的所有方法共用（不同型号 piid 不同：s710ym 是 1/2/2、dtu.json 是 1/1/1，
  由前端按型号写入，后端不去产品定义推算）。
* `device.space` 记录该设备所在的空间（`cc.openxiot.matrix.db.space.SpaceOn`，`DeviceEntity.space`
  用的是同一个类）。空间图要按空间反查服务（`ModbusServiceRepository.findBriefBySpaceId`，见第六节），
  落库时带上就不必再到设备表里绕一圈。它不参与校验：缺省的服务只是不出现在按空间的查询里。
* `function.request` = 完整的 Modbus RTU 帧（含 CRC16），**由前端生成**，后端原样交给父设备透传。
* `response[]` = 应答解析规则；`version` 标记 functions 内部格式的口径，后续演进时递增。

## 四、接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/matrix/v1/modbus/service/many` | 组织成员 | 按组织列出 |
| GET | `/matrix/v1/modbus/service/parent/{did}` | 组织成员 | 按依赖设备 did 列出（路径里的 `parent` 暂未随字段改名） |
| GET | `/matrix/v1/modbus/service/one/{id}` | 组织成员 | 指定 ID 查找 |
| POST | `/matrix/v1/modbus/service/one` | 组织管理员 | 创建（body = 完整 ModbusService，org 取 `X-Org-Id`） |
| PUT | `/matrix/v1/modbus/service/one/{id}` | 组织管理员 | 修改（缺省字段保留既有值） |
| DELETE | `/matrix/v1/modbus/service/one/{id}` | 组织管理员 | 删除 |
| POST | `/matrix/v1/modbus/service/invoke` | 组织成员 | 调用方法，body `{"service": "<id>", "function": <index>}` |

## 五、invoke 的调用链与返回

`ModbusServiceService.invoke`：按 org + id **读一次** `ModbusService` 文档 → 取该 index 的 function →
组装依赖设备的 `ActionOperation`（did/siid/aiid 取 `device`，in 填 `{argument: request}`）→
交 `ManipulationService.invokeActions`（DTU 的“发送”把帧透传到 RS485）→ 把应答帧按 `response[]` 解成字段值。

* 应答帧按 `[slave][fc][byteCount][data...][crc16]` 校验（长度 / 异常码 `fc|0x80` / CRC16，复用
  `ModbusFrameCodec` 的 CRC 与字节序原语），数据区按 `response[].index` 升序、以 `bytes` **依次累加偏移**切分，
  各字段 bytes 之和必须等于 byteCount。
* 取值：原始值命中 `value-list` → 输出其 `description`（不再应用 scale）；否则输出 scale 后的数值；
  `unit` 只做展示、不参与取值（调用方从服务定义里查）。
* 返回 `OxResponse.ok(data)`，`data` = **字段 → 值**：

  ```json
  { "success": true, "data": { "进水温度": 61.5, "开关状态": "ON" } }
  ```

  写方法（`response` 为空）的应答是请求回显、没有读值，`data` 为空对象。
  父设备报错 / 帧非法 / CRC 不符 / 异常码一律 `OxResponse.error(message)`，不做部分成功。

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
  供清单里把服务与设备对上（同表渲染、按空间或设备筛选）；详情另调 `/matrix/v1/modbus/service/one/{id}`。
  整份定义（functions / response）动辄几十 KB，一个图里挂几十个服务就不适合整份带出来了。

## 七、校验

规则实现在 `ModbusServiceValidator`（纯静态函数，返回 `null` = 通过，否则是给前端的错误文案），
`ModbusServiceService` 只负责把文案转成 `IllegalArgumentException`。

* `name`：服务名称（展示用）必填。
* `version`：必填（定义格式口径，当前只认 `1`；后端在 service 里的缺省填充因此形同虚设，前端要带上）。
* `device`：did 非空、siid/aiid/argument ≥ 1。
* `functions`：index 必填 / ≥1 / 服务内唯一；name 必填；request 必填且是合法完整的 16 进制
  （CRC 正确性留给运行期应答校验）。
* `response`：index 必填 / ≥1 / 组内唯一；field 必填；bytes ≥ 1；format ∈
  `int8/uint8/int16/uint16/int32/uint32/float32/string`，且非 string 时 bytes 必须等于该格式的宽度；
  bytes > 1 时 byteOrder 必填且 ∈ `ABCD/DCBA/BADC/CDAB`；value-list 内 value/description 必填且 value 不重复。

## 八、变更记录

### 2026-09-10

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
  `DeviceOnCodec`（时名 `ModbusServiceParentCodec`）、`ModbusServiceFunctionCodec`、`ModbusServiceFieldCodec`、
  `ModbusServiceFieldValueCodec`。
* 应答帧的解析单独放在 `ModbusResponseParser.parse(fields, frameHex)`（复用了
  `ModbusFrameCodec` 的 CRC 与字节序原语），名字与 JSON 编解码区分开。
* `ModbusFrameCodec` 的 `checkCrc` / `reorder` 由 private 改为 public（仅可见性，行为不变，供本方案复用），
  并让 `reorder` 对非 2/4 字节宽度原样返回（原先会越界）。
* 定义校验从 Service 里抽出为 `ModbusServiceValidator`（`api/modbus/service`，纯静态函数，无状态、不碰数据库），
  `ModbusServiceService` 从 432 行降到 252 行；`invoke` 前的调用坐标检查复用其 `checkDevice`。
* 附纯单测 `ModbusResponseParserTest`（10 例：多字段偏移、取值表、缩放、字节序、异常帧）与
  `ModbusServiceCodecTest`（7 例：按示例报文解码、encode∘decode 稳定、id 十六进制串、value-list 键名、精简视图）
  与 `ModbusServiceValidatorTest`（12 例：顶层字段 / device / functions / request / response / value-list）。
