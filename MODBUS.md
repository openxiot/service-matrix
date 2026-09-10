# 将 Modbus 定义的设备，映射成虚拟设备（作为选中父设备的一个子设备）

Modbus 点表配置（`ModbusConfig`）描述挂在 Modbus 总线上一台物理设备的寄存器 / 线圈点表。
「创建 Modbus 虚拟设备」即把这套点表自动生成一个虚拟设备的**实例定义**，挂到用户选中的父设备之下。

> 流程：生成虚拟设备**实例定义**并落库（`modbus`/`devices` 定义注册表），随后以同一 did / 派生类型创建
> 矩阵 **DeviceEntity**（挂在父设备下、复制其所在 space），`createOne` 返回该设备。
> 虚拟子设备无独立在线状态：online 始终镜像父设备（创建时复制父 online，读取时由 `DeviceService`
> 按父设备在线状态归一，见下）。尚未打通「真实 Modbus 接入」（实例注册 / I/O 桥接）；后续可基于落库的
> `definition`（`DeviceInstanceCodec` 编码的 DeviceInstance JSON）与派生 DeviceType 做真正的接入。

## 一、创建 Modbus 虚拟设备

接口：`POST /matrix/v1/modbus/virtual/one`（`X-Org-Id` 请求头携带操作组织，需为该组织管理员）。

### 1. 输入

```json
{
  "configId": "<被虚拟化的点表配置 ID>",
  "did": "<父设备 ID（虚拟设备挂载到其下）>",
  "siid": 1,
  "aiid": 1
}
```

`siid` / `aiid` 是父设备侧的「服务 ID / 方法 ID」，仅作映射信息原样保留。

### 2. 输出

`OxResponse.ok(data)`，`data` = 新建的矩阵 `DeviceEntity`（形状与设备模块 `GET /matrix/v1/device/one/...` 一致）：

| 字段 | 说明 |
|---|---|
| `did` | 虚拟设备 ID（服务端生成的 UUID） |
| `type` | 派生 DeviceType（继承点表 `type` + 追加父 did / 配置 id / version=1，全小写） |
| `online` / `protocol` | `modbus`；`online` = 创建时父设备在线状态（虚拟子设备无独立在线，镜像父设备） |
| `parentId` / `rootId` | 父设备 did；rootId = 父所在树的根（父为根时即父 did） |
| `space` | 复制父设备所在 space：`{ spaceId, rootId }` |

> 映射 `mapping`（configId / did / siid / aiid）与实例 `definition`（DeviceInstanceCodec 编码）不再于响应回显，
> 随 `devices` 文档落库，供后续真实接入使用。

### 3. 生成规则

前提：点表配置带一个完整的**品类 DeviceType**（`slave.type.type`，如 `urn:xiot-spec:device:chiller:0000A005`。
新建点表必填，前端从规范目录两级选择、不手工输入；存量配置编辑允许暂缺，后端保留旧值）。
`slave.type` 是包装对象：除 `type`（DeviceType URN）外还快照 `specDescription`（产品规范多语文案）与
`typeDescription`（产品类型多语文案），前端直接展示无需回产品目录查询；详情回显见「点表配置 JSON」说明。

* **前提（点表功能码）**：点表每条功能码动作带 `index`（1 起自然数，行内唯一）。前端行排序依赖它，
  生成顺序与 action iid 也取它。新建/编辑由前端按行序写入；后端校验 index 必填 / ≥1 / 不重复。

* **虚拟设备 DeviceType**：继承配置 `type` 的 ns / name，只追加 vendor + model + version，拼成 8 段 XIOT 实例类型：
  `urn:<ns>:device:<name>:<head>:<vendor>:<model>:1`
  * `vendor` = 父设备 `did`（小写）
  * `model` = `configId`（小写）
  * `head` = 由 `type` 的 value 段重编码为 8 位小写十六进制（确定性，重复生成一致）
* **设备描述**：取点表配置描述。

* **功能定义只有一个 service（iid=1）**：
  `urn:<ns>:service:modbus:<hex>:<vendor>:<model>:1`（ns / vendor / model 与 DeviceType 一致，name 固定 `modbus`）

* service 内，点表每条功能码（command）对应生成一个 **Action**，**iid = 该 command 的 `index`**（1 起自然数）：
  生成前按 `index` 升序排序，name 依序为 `a0、a1、…`：
  `urn:<ns>:action:a<i>:<hex>:<vendor>:<model>:1`；action 描述 = 功能名称。
  * **读操作**（fc 01/02/03/04）：无输入参数；额外生成一条**只读** backing Property
    （按出现顺序 name 为 `v0、v1、…`），DataFormat 由点表解析（见下表），unit 取功能码单位；
    该 Action 的 `out` 参数 = `[{ piid: 该 Property 的 iid }]`（只引用、不内联值）。
  * **写操作**（fc 05/06/0F/10）：无输入、无输出参数。

  DataFormat 解析（读功能码）：

  | 功能码 | 点表 dataType | DataFormat |
  |---|---|---|
  | 01 / 02（线圈 / 离散输入） | — | bool |
  | 03 / 04 | int16 / uint16 / int32 / uint32 | int16 / uint16 / int32 / uint32 |
  | 03 / 04 | float32 | float |
  | 03 / 04 | string（或缺失） | string |

* 各子类型（service / action / property）的 value 段 `hex` = 由稳定字符串（配置 ID + 种类 + 序号）哈希出的
  8 位小写十六进制，保证同一点表重复生成得到同一套类型；ns / vendor / model 与 DeviceType 对齐。

### 4. 校验与落库

* 入参校验：`configId`、`did` 必填；`did` 不能含 `:`；`siid`、`aiid` 必须 > 0。
* 点表配置不存在 → `modbus device config not found: <id>`；`type` 缺失 / 非法 → 引导先到点表页选择设备类型。
* 落库：`db=modbus`、`collection=devices`（与点表配置 `configs` 分集合，避免 Panache 双类型同集合
  解码错乱）。字段 = orgId + `mapping` 子文档（`configId` / `did` / `siid` / `aiid`）+
  `instance` 子文档（派生 DeviceType + description + `definition`）+ 创建人 / 创建时间。
* **归属 = 请求组织（`X-Org-Id`），而非点表所属组织**——跨组织公开点表可被本组织虚拟化，虚拟设备记入操作方组织。
* 定义落库前先校验父设备存在（`DeviceService.getByDeviceId(did)`；无 → `parent device not found: <did>`，避免“半注册”），
  落库后再把虚拟设备作为父设备下的子设备创建矩阵 `DeviceEntity`（`DeviceService.save`）：did=虚拟设备 id、
  parentId=父 did、rootId=父所在树的根（父为根时即父 did）、复制父所在 `space`、protocol=`modbus`，
  **online = 父设备当前 online**（虚拟子设备无独立在线），使虚拟子设备出现在父设备所在空间的设备列表。
  `createOne` 返回该 DeviceEntity。

### 4.1 online 与父设备保持一致

虚拟子设备不接真实 I/O、无独立在线状态，其 `online` 语义 = 父设备在线状态：

* 创建时镜像父设备当前 `online`（见上一节 `buildDeviceEntity`）。
* 读取时由 `DeviceService` 统一归一：`getBySpaceId` / `getByDeviceIds` / `getByDeviceId` 返回前，凡
  protocol=`modbus` 且 parentId 非空的设备，online 改写为可上溯祖先（父链若仍是虚拟设备则继续上溯）的 online；
  父设备不在批量结果内时按 parentId 补查。故父设备在线状态在 DB 侧更新后，虚拟子设备随读即同步，
  无需单独刷新子设备记录。

### 5. 删除

接口：`DELETE /matrix/v1/modbus/virtual/one?did=<虚拟设备ID>`（`X-Org-Id` 需为该组织管理员；`did` 指虚拟设备
自身 ID，即 createOne 生成的 UUID，不是父设备 did）。

* 先按 `orgId + did` 查 `devices` 定义文档：不存在 / 归属其它组织 → `modbus virtual device not found: <did>`。
* 再删同 did 的矩阵 `DeviceEntity`（`deviceService.deleteByDeviceIds`，createOne 的镜像）与 `devices`
  定义文档；矩阵子设备可能已被先行删除（条数 0）不视为失败。
* 不守卫子设备（沿用 device 模块本地删除口径），子树理论上可能悬空。

### 6. 读取虚拟设备实例

接口：`GET /matrix/v1/modbus/virtual/instance/{type}`（**公开只读**，无需 `X-Org-Id` 与成员校验）。

* `type` = createOne 派生的**实例 DeviceType**（8 段实例类型，与矩阵 `DeviceEntity` 的 `type` 一致，
  如 `urn:xiot-spec:device:chiller:0000a005:<父did>:<cfgid>:1`，经路径传参）。
* 按 `instance.type` **跨组织**精确匹配 `devices` 定义文档，把落库 `definition`
  （`DeviceInstanceCodec` 编码 JSON）解析回 xiot-spec 模型并重新编码返回；
  未命中 → `modbus virtual device instance not found: <type>`。
* 供仅持类型、无组织上下文的调用方（真实 Modbus 接入 / 实例注册侧）读取实例定义。

## 二、参考文档

/Users/ouyang/works/openxiot/service/webapp-docs/docs/spec/spec.mdx

## 三、变更记录

### 2026-09-10
* **点表配置主键改为系统生成**（不再由服务层自造 UUID）：`ModbusConfig` 由
  `ReactivePanacheMongoEntityBase + @BsonId String id` 改为继承 `ReactivePanacheMongoEntity`，
  `_id` 落库为 Panache 生成的 **ObjectId**（口径同 `SpaceEntity`）；`create` 不再赋 id，
  由仓储 `persist` 生成并回写 `cfg.id`。**对外契约不变**：JSON（`ModbusJson`）、URL 路径参数、
  虚拟设备 `mapping.configId` 仍是十六进制字符串——仓储方法签名保持 String、内部转 ObjectId，
  非法 id 按「未命中」处理（不再可能抛 400/500）。虚拟设备派生类型 / 子 URN 里的点表主键段相应改为该 hex 串。
* **启动迁移全部移除**：各环境均已重建为全新库，存量文档不复存在，`ModbusConfigService.init` 只保留
  `repo.ensureIndexes()`（仍受 `modbus.index.enabled` 开关约束）。随之删除六个启动迁移
  （旧 `points[]` → `commands[]`、扁平字段 → `slave`、`slave.type` 字符串 → 对象、`commands[].index` 回填、
  `lifecycle` 回填、字符串主键 → ObjectId）及其 `legacyPointToCommand` / `logicalBase` 辅助方法与
  `mongo`（`ReactiveMongoClient`）/ `logger` 注入——旧结构数据不再有兜底改写，新结构由写入侧校验保证。
* **虚拟设备集合改名 `virtual-devices` → `devices`**：`ModbusVirtualDevice` 落库集合改为 `modbus`/`devices`
  （与矩阵库 `matrix` 的 `devices` 同名但不同库，互不影响）。纯落库位置变更：HTTP 路由、类名、`did`、
  响应结构与前端零改动；库已清空，无迁移。

### 2026-09-09
* **虚拟子设备 online 与父设备保持一致**：虚拟子设备无独立在线状态。创建时 `online` 镜像父设备当前状态
  （不再固定离线起步）；读取面 `DeviceService.getBySpaceId` / `getByDeviceIds` / `getByDeviceId` 返回前对
  protocol=`modbus` 且 parentId 非空的设备按其父链 online 归一（父不在批量结果内时补查，父链再嵌套则上溯）。
  故父设备 DB online 更新后，虚拟子设备随读即同步。详见第 4.1 节。
* 虚拟设备 createOne / deleteOne 授权从「组织成员」收紧为「**组织管理员**」（`OrgAccessService.checkAdmin`），
  仅 `getInstance` 保持公开只读。
* 新增公开只读 `getInstance`：`GET /matrix/v1/modbus/virtual/instance/{type}`（无需鉴权），按派生的实例
  DeviceType 跨组织读取 `virtual-devices` 定义文档并把 `definition` 解码为 `DeviceInstance` 返回
  （未命中 / 存量定义损坏 → 明确报错）。
* 新增删除虚拟设备 `deleteOne`：`DELETE /matrix/v1/modbus/virtual/one?did=...`（需 `X-Org-Id` 为该组织管理员）。
  按当前组织删除 `virtual-devices` 定义文档 + 同 did 的矩阵 `DeviceEntity`（createOne 的镜像清理，不做子设备守卫）；
  不存在 / 归属其它组织 → `modbus virtual device not found: <did>`。
* 点表配置新增 `lifecycle`（生命周期，存储串与 xiot-spec `Lifecycle` 对齐：`development`/`preview`/`released`）：
  新建一律从 `development` 开始（忽略请求里指定的值）；**仅 `development` 态可 update/delete**，
  `preview`/`released` 被后端拒绝（语义仿 service-product，仅多一个 modbus 前缀的报错文案）；普通更新不流转生命周期。
  对外 JSON 输出 `lifecycle`；存量缺省文档启动时幂等回填 `development`（`migrateLifecycle`）。
  前端：设备点表详情页「设备信息」卡片顶部展示生命周期标签、列表新增「状态」列；非 development 的点表隐藏编辑/删除入口。
* lifecycle 改为经**专用接口**流转：`PUT /matrix/v1/modbus/config/one/{id}/lifecycle/{lifecycle}`
  （需 `X-Org-Id` 且为该组织成员；目标值仅 development / preview / released，后端不校验流转、直接覆盖，
  仿 service-product `ProductBasicService.update(productId, lifecycle)`）。普通内容 `update` **永不触碰 lifecycle**
  （且仍仅 development 态可 update/delete，preview/released 报错）。
  前端：设备点表详情页去掉整页「保存」——设备信息 / 功能码 任一变更（编辑对话框确定、删除弹窗确认、拖拽重排）即改即存；
  原「保存」位置按状态显示动作组：development→**预览**、preview→**发布**/**下线**、released→**下线**
  （仿 webapp-product ProductBasicComponent，按钮态随 lifecycle 标签实时刷新）。
* 点表配置新增必填 `type`：存**完整品类 DeviceType**；前端（点表编辑器）改成两级选择器（名字空间 → 设备类型，
  值存完整 URN），不再支持手工输入；详情页按 `namespace · name` 回显、可由选择器编辑；新建强制、编辑存量配置可暂缺
  （后端 patch 无 `type` 时不覆盖旧值）。
* 实现创建虚拟设备 `createOne`（上一节）：用 xiot-spec 模型构造 `DeviceInstance`，经 `DeviceInstanceCodec` 编码后落库，
  不手拼 JSON；范围仅生成定义、不注册真实设备。
* **createOne 扩展为「生成定义 + 创建矩阵 DeviceEntity」**：落库 `virtual-devices` 定义注册表后，把虚拟设备作为
  父设备（请求 `did`）下的子设备创建矩阵 `DeviceEntity`（parentId=父 did、rootId=父所在树根、复制父所在 space、
  protocol=`modbus`、离线起步）；父设备需真实存在（先校验，无 → `parent device not found: <did>`）。
  响应由 `toData` 摘要改为直接返回该 `DeviceEntity`（与设备模块 `/one` 回显同形），`toData` 已移除；
  `mapping` / `definition` 仍随 `virtual-devices` 落库、不再于响应回显。
* 虚拟设备落库独立集合 `virtual-devices`；归属当前操作组织（`X-Org-Id`）。
* 映射信息抽成值对象 `ModbusMapping`（原 `CreateModbusVirtualDeviceRequest` → `ModbusVirtualMapping`，居 db/virtual 包）；
  实体名 `ModbusVirtualDevice`。createOne 请求体 / 实体 `mapping` 子文档 / 响应 `mapping` 回显复用同一类型，
  实体不再平铺重复字段。
* **点表配置对外 JSON 改嵌套 `slave`**（破坏性 wire 变更）：厂家 / 型号 / 从站地址 / 设备类型 / 描述 五个平铺字段
  收拢为 `{ slave: { manufacturer, model, slaveId, type, description }, visibility, commands }`（新建必填 `slave`；
  编辑可带空 `slave` 仅更可见度/命令，type 缺省时不覆盖存量）。实体与响应统一走 `ModbusSlave` 值对象；
  `/one` 响应 `slave` 子对象回显，`mapping`/虚拟设备生成改从 `cfg.slave.type` / `cfg.slave.description` 读取。
  存量平铺文档启动时幂等迁移：`manufacturer` 等顶层键收拢进 `slave` 子文档并删除原键（`migrateFlatSlave`）。
* **`slave.type` 再包装为 `ModbusDeviceType`**（破坏性 wire 变更）：设备类型由字符串改为子对象
  `type: { type: <DeviceType URN>, specDescription: {…多语文案}, typeDescription: {…多语文案} }`——
  新建/编辑时随选择把「产品规范（名字空间）」「产品类型」的多语文案快照落库，前端直接展示、无需回产品目录查询；
  校验读取 `type.type`，update 缺省 type 保留既有（含文案）。虚拟设备生成读 `slave.type.type`。
  存量 `slave.type` 仍是字符串的文档启动时幂等包装为对象（`migrateLegacySlaveType`，文案缺省前端回退解析）。
* 虚拟设备落库的 派生 DeviceType / description / definition 三字段收拢为 `instance` 子文档
  （值对象 `ModbusDeviceInstance`）；createOne 响应结构不变。
* 功能码动作新增 `index`（1 起自然数、行内唯一）：前端排序依赖它；虚拟设备生成的 action iid = `index`
  （不再按数组位置 i+1）；后端校验 index 必填 / ≥1 / 去重；启动迁移回填存量（`migrateCommandIndex`）。
