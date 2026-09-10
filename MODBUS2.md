# 将 Modbus 配置，生成具体的 Modbus 服务

## 一、设计思想

MODBUS.md 的设计思想是：将 Modbus 配置生成具体的设备实例。
优点：
* 复用设备实例定义，读属性、写属性、执行方法，均可使用通用的设备接口（DeviceResource）。
缺点：
* 使用设备拦截器拦截指令时，步骤繁多，且需要读几次数据库。

本文档的设计思想是：将 Modbus 配置，生成具体的 Modbus 服务
优点
* 简单，只需访问一次数据库（ModbusServiceRepository），映射的规则都在ModbusService中。
缺点
* 需要单独设计一套API：ModbusServiceResource

## 二、示例

./modbus-service-example.json

## 三、具体实现

1. 参考示例文件，使用 ModbusService 来描述，然后完成 ModbusServiceRepository。
2. 完成 ModbusServiceResource：
   * ModbusService 的创建、删除、修改、查找（根据组织查找、根据父设备的DID查找，指定ID查找）。 
   * ModbusService 方法的调用。

## 四、API 与实现（已落地）

与方案一（MODBUS.md：虚拟设备 + `DeviceInterceptor`）**并存**，两者互不引用、各走各的路径；
方案一的代码暂不删除，等本方案完善后再清理。

### 1. 数据模型（落库 `modbus`/`services`，Panache 生成 ObjectId，对外十六进制字符串）

```
ModbusService        orgId / name / version / configId / parent / functions[] / creator / updater
ModbusServiceParent  did / siid / aiid / argument     ← 父设备调用坐标，前端创建时写入
ModbusServiceFunction index / name / request / response[]
ModbusServiceField   index / field / bytes / format / byteOrder / scale / unit / value-list[]
```

* `parent.argument` = 父方法**入参的 piid**，请求帧填在这里。DTU 承载数据的动作只有一个入参，
  故提到 parent 级、对该服务的所有方法共用（不同型号 piid 不同：s710ym 是 1/2/2、dtu.json 是 1/1/1，
  由前端按型号写入，后端不去产品定义推算）。
* `function.request` = 完整的 Modbus RTU 帧（含 CRC16），**由前端生成**，后端原样交给父设备透传。
* `response[]` = 应答解析规则；`version` 标记 functions 内部格式的口径，后续演进时递增。

### 2. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/matrix/v1/modbus/service/many` | 组织成员 | 按组织列出 |
| GET | `/matrix/v1/modbus/service/parent/{did}` | 组织成员 | 按父设备 did 列出 |
| GET | `/matrix/v1/modbus/service/one/{id}` | 组织成员 | 指定 ID 查找 |
| POST | `/matrix/v1/modbus/service/one` | 组织管理员 | 创建（body = 完整 ModbusService，org 取 `X-Org-Id`） |
| PUT | `/matrix/v1/modbus/service/one/{id}` | 组织管理员 | 修改（缺省字段保留既有值） |
| DELETE | `/matrix/v1/modbus/service/one/{id}` | 组织管理员 | 删除 |
| POST | `/matrix/v1/modbus/service/invoke` | 组织成员 | 调用方法，body `{"service": "<id>", "function": <index>}` |

### 3. invoke 的调用链与返回

`ModbusServiceService.invoke`：按 org + id **读一次** `ModbusService` 文档 → 取该 index 的 function →
组装父设备 `ActionOperation`（did/siid/aiid 取 `parent`，in 填 `{argument: request}`）→
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

### 4. 校验

规则实现在 `ModbusServiceValidator`（纯静态函数，返回 `null` = 通过，否则是给前端的错误文案），
`ModbusServiceService` 只负责把文案转成 `IllegalArgumentException`。

* `name`：服务名称（展示用）必填。
* `parent`：did 非空、siid/aiid/argument ≥ 1。
* `functions`：index 必填 / ≥1 / 服务内唯一；name 必填；request 必填且是合法完整的 16 进制
  （CRC 正确性留给运行期应答校验）。
* `response`：index 必填 / ≥1 / 组内唯一；field 必填；bytes ≥ 1；format ∈
  `int8/uint8/int16/uint16/int32/uint32/float32/string`，且非 string 时 bytes 必须等于该格式的宽度；
  bytes > 1 时 byteOrder 必填且 ∈ `ABCD/DCBA/BADC/CDAB`；value-list 内 value/description 必填且 value 不重复。

## 五、变更记录

### 2026-09-10
* 按本节落地方案二：`ModbusService` 实体（含 parent / functions / response 值对象）、
  `ModbusServiceRepository`（orgId / parent.did 索引）、`ModbusServiceService`（增删改查 + invoke）、
  `ModbusServiceResource`（上表 7 个接口）。
* JSON 互转按「一个实体一个 Codec」拆开、放在实体的 `codec` 子包
  （`cc.openxiot.matrix.db.modbus.service.codec`），统一 `JsonObject encode(X)` / `X decode(JsonObject)`：
  `ModbusServiceCodec`（主键按十六进制串输出，另带 `JsonArray encode(List)`）、
  `ModbusServiceParentCodec`、`ModbusServiceFunctionCodec`、`ModbusServiceFieldCodec`、
  `ModbusServiceFieldValueCodec`。
* 应答帧的解析单独放在 `ModbusResponseParser.parse(fields, frameHex)`（复用了
  `ModbusFrameCodec` 的 CRC 与字节序原语），名字与 JSON 编解码区分开。
* `ModbusFrameCodec` 的 `checkCrc` / `reorder` 由 private 改为 public（仅可见性，行为不变，供本方案复用），
  并让 `reorder` 对非 2/4 字节宽度原样返回（原先会越界）。
* 定义校验从 Service 里抽出为 `ModbusServiceValidator`（`api/modbus/service`，纯静态函数，无状态、不碰数据库），
  `ModbusServiceService` 从 432 行降到 252 行；`invoke` 前的父坐标检查复用其 `checkParent`。
* 附纯单测 `ModbusResponseParserTest`（10 例：多字段偏移、取值表、缩放、字节序、异常帧）与
  `ModbusServiceCodecTest`（6 例：按示例报文解码、encode∘decode 稳定、id 十六进制串、value-list 键名）
  与 `ModbusServiceValidatorTest`（12 例：顶层字段 / parent / functions / request / response / value-list）。