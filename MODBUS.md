# 将 Modbus 定义的设备，映射成虚拟设备（作为选中父设备的一个子设备）

Modbus 点表配置（`ModbusConfig`）描述挂在 Modbus 总线上一台物理设备的寄存器 / 线圈点表。
「创建 Modbus 虚拟设备」即把这套点表自动生成一个虚拟设备的**实例定义**，挂到用户选中的父设备之下。

> 范围：**仅生成定义并落库，不注册真实设备**。后续如需真正接入，可基于落库的 `definition`
> （`DeviceInstanceCodec` 编码的 DeviceInstance JSON）与派生 DeviceType 注册产品实例。

## 一、创建 Modbus 虚拟设备

接口：`POST /matrix/v1/modbus/virtual/one`（`X-Org-Id` 请求头携带操作组织，需为该组织成员）。

### 1. 输入

```json
{
  "modbusConfigId": "<被虚拟化的点表配置 ID>",
  "did": "<父设备 ID（虚拟设备挂载到其下）>",
  "siid": 1,
  "aiid": 1
}
```

`siid` / `aiid` 是父设备侧的「服务 ID / 方法 ID」，仅作映射信息原样保留。

### 2. 输出

`OxResponse.ok(data)`，字段：

| 字段 | 说明 |
|---|---|
| `virtualDeviceId` | 虚拟设备 ID（服务端生成的 UUID，即摘要里的 `did`；后续接入真实注册时作为子设备 did） |
| `deviceSummary` | 对齐矩阵设备形状：`{ did: 虚拟设备ID, type: 派生DeviceType, online: false, protocol: "modbus", rootId: 父did, parentId: 父did }` |
| `mapping` | 请求原样回显：`{ modbusConfigId, did, siid, aiid }` |
| `description` | 点表配置的描述（若有） |
| `definition` | 生成的实例定义（DeviceInstanceCodec 编码后的 JSON） |

### 3. 生成规则

前提：点表配置带一个完整的**品类 DeviceType**（`type`，如 `urn:xiot-spec:device:chiller:0000A005`。
新建点表必填，前端从规范目录两级选择、不手工输入；存量配置编辑允许暂缺，后端保留旧值）。

* **虚拟设备 DeviceType**：继承配置 `type` 的 ns / name，只追加 vendor + model + version，拼成 8 段 XIOT 实例类型：
  `urn:<ns>:device:<name>:<head>:<vendor>:<model>:1`
  * `vendor` = 父设备 `did`（小写）
  * `model` = `modbusConfigId`（小写）
  * `head` = 由 `type` 的 value 段重编码为 8 位小写十六进制（确定性，重复生成一致）
* **设备描述**：取点表配置描述。

* **功能定义只有一个 service（iid=1）**：
  `urn:<ns>:service:modbus:<hex>:<vendor>:<model>:1`（ns / vendor / model 与 DeviceType 一致，name 固定 `modbus`）

* service 内，点表每条功能码（command）对应生成一个 **Action**（iid 从 1 递增，name 为 `a0、a1、…`）：
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

* 入参校验：`modbusConfigId`、`did` 必填；`did` 不能含 `:`；`siid`、`aiid` 必须 > 0。
* 点表配置不存在 → `modbus device config not found: <id>`；`type` 缺失 / 非法 → 引导先到点表页选择设备类型。
* 落库：`db=modbus`、`collection=virtual-devices`（与点表配置 `configs` 分集合，避免 Panache 双类型同集合
  解码错乱）。字段 = 映射信息 + 派生 DeviceType + description + `definition` + 创建人 / 创建时间。
* **归属 = 请求组织（`X-Org-Id`），而非点表所属组织**——跨组织公开点表可被本组织虚拟化，虚拟设备记入操作方组织。

## 二、参考文档

/Users/ouyang/works/openxiot/service/webapp-docs/docs/spec/spec.mdx

## 三、变更记录

### 2026-09-09
* 点表配置新增必填 `type`：存**完整品类 DeviceType**；前端（点表编辑器）改成两级选择器（名字空间 → 设备类型，
  值存完整 URN），不再支持手工输入；详情页按 `namespace · name` 回显、可由选择器编辑；新建强制、编辑存量配置可暂缺
  （后端 patch 无 `type` 时不覆盖旧值）。
* 实现创建虚拟设备 `createOne`（上一节）：用 xiot-spec 模型构造 `DeviceInstance`，经 `DeviceInstanceCodec` 编码后落库，
  不手拼 JSON；范围仅生成定义、不注册真实设备。
* 虚拟设备落库独立集合 `virtual-devices`；归属当前操作组织（`X-Org-Id`）。
