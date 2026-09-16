# service-matrix API 调用说明

> 通用物联网场地服务（简称 **Matrix**），负责 **空间管理** 和 **设备管理**。
>
> 技术栈：Quarkus (Java) + MongoDB，JWT（RS256）鉴权。
>
> 相关概念定义请参考：
>
> - [产品规范定义](../webapp-docs/docs/spec/spec.mdx)：Device / Service / Property / Action / Event、iid、PID / AID / EID 等 ID 约定
> - [可扩展连接协议 (XCP)](../webapp-docs/docs/spec/xcp.mdx)：DID、IID（SIID/PIID/AIID）、属性读写 / 方法调用 / 状态码等概念

---

## 1. 概述

Matrix 服务向客户端提供两类能力：

| 模块 | 说明 | 主要职责 |
| --- | --- | --- |
| 空间（Space） | 场地 / 楼宇 / 楼层 / 房间等多级空间的组织 | 空间的增删改查、树 / 图查询、组织归属 |
| 设备（Device） | 接入到场地中的物联网设备 | 设备的注册、删除、查询、空间归属，以及属性读写与方法调用 |
| 统计（Statistics） | 数据看板（首页）的聚合数字 | 一个空间（含子空间）内的设备 / 服务 / 告警 / 故障计数与分布（见第 8 节） |

其中 **设备操作**（属性读写、方法调用、设备影子）本身由下游服务 `service-manipulation` 提供，
Matrix 负责：校验调用者的空间 / 组织权限 → 以 `appId + ownerId` 的 JWT 转发给 manipulation → 返回结果。

所有接口都是 **REST + JSON**，业务上统一使用 HTTP `200` 返回，并通过响应体里的 `success` 字段表达成败。

---

## 2. 通用约定

### 2.1 基础路径

| 模块 | Base Path |
| --- | --- |
| 空间 | `/matrix/v1/space` |
| 设备 | `/matrix/v1/device` |
| 统计 | `/matrix/v1/statistics` |

> 部署说明：在公网网关（如 `site.openxiot.cn`）之后，`/matrix` 前缀可能由网关剥离，对外暴露为 `/v1/space/**`、`/v1/device/**`（与 webapp-matrix 前端调用一致）。以实际部署的网关转发规则为准。

### 2.2 认证与鉴权

#### JWT（必需）

所有业务接口都需要在 `Authorization` 头携带 Bearer JWT，否则返回 `401`。

Token 由 `service-account` 签发（RS256，issuer 为 `https://example.com/issuer`），关键声明：

| Claim | 含义 | 用途 |
| --- | --- | --- |
| `upn` | 用户 ID（账号 ID） | 服务端通过 `getUserId()` 读取，用于组织成员校验 |
| `groups` | 角色集合，需包含 `user` | `@RolesAllowed` 校验，否则返回 `403` |

```
Authorization: Bearer <jwt-token>
```

Token 获取方式：通过 service-account 的用户 OAuth2 登录接口签发，例如

```
GET /user/oauth2/v1/login/{platformId}?code={授权码}
```

返回体中的 `data.token` 即所需 JWT（有效期为 7 天）。

#### X-Org-Id 请求头（只有少数接口需要）

`X-Org-Id` 标识 **组织 ID**（organizationId）。它只用来表达「**作用于哪个组织**」，
**不参与权限判定**：

- **需要它的**：`GET /space/all`（列哪个组织的项目）、`POST /space/one`（建组织的项目还是个人的项目）、
  `/modbus/config/**`（组织级点表配置）。这几个接口的请求里没有别的途径表达「哪个组织」。
- **不需要它的**：其余全部接口。空间类接口的归属从**空间自己的 `accesses`** 读 ——
  里面那条组织条目就是「这个项目属于哪个组织」，客户端填什么都不会改变判定结果（填错也不会）。

```http
X-Org-Id: openxiot
```

组织成员关系由 service-account 的 `UserOrganization`（`user-organizations` 集合）维护，
成员字段包含 `userId` 与 `role`（`admin` / `member`）。

#### 权限模型

| 校验级别 | 判定条件 | 适用操作 |
| --- | --- | --- |
| 空间管理员（Admin） | 空间的 `accesses` 含「该用户的 user 条目且 `role = admin`」；或含组织条目，且当前用户是该组织的 `admin` 成员 | 空间的增删改、设备的增删 / 移动 |
| 空间成员（Member） | 空间的 `accesses` 含该用户的 user 条目（任意角色）；或含组织条目，且当前用户是该组织的任一成员 | 空间 / 设备的查询、属性读写、方法调用 |

> 注意：空间不存在、用户既不是该空间的 user 条目也不在其所属组织里、或所属组织已不存在时，
> 均会返回 `{"success": false, "message": "<原因>"}`。

### 2.3 统一响应格式

| 场景 | HTTP | 响应体 |
| --- | --- | --- |
| 成功（无数据） | 200 | `{"success": true}` |
| 成功（带数据） | 200 | `{"success": true, "data": <数据>}` |
| 业务失败 | 200 | `{"success": false, "message": "<失败原因>"}` |
| 未认证 / Token 无效 | 401 | — |
| 角色不足（非 user） | 403 | — |

### 2.4 其他约定

- 请求 / 响应均为 `application/json`；`Content-Type` 见各接口。
- 时间字段：设备的时间为 Date（ISO-8601 字符串），空间的时间为 `LocalDateTime`（`yyyy-MM-ddTHH:mm:ss`）。
- OpenAPI 文档（Quarkus）位于 `/openapi`，Swagger UI 位于 `/q/swagger-ui`（dev 环境）。

### 2.5 关键 ID 概念（来自 xiot-spec / XCP）

| 概念 | 定义 | 示例 |
| --- | --- | --- |
| DID | 设备 ID | `abc` |
| IID | 实例 ID，自然数；分为 SIID（服务）/ PIID（属性）/ AIID（方法） | `1`, `2` |
| PID | 属性 ID `{DID}.{SIID}.{PIID}` | `abc.1.2` |
| AID | 方法 ID `{DID}.{SIID}.{AIID}` | `abc.1.9` |
| type | 设备 / 服务 / 属性等定义的 URN | `urn:xiot-spec:device:fan:0000A005` |

设备属性 / 方法操作均使用 PID / AID 寻址，具体数据格式见 [4.8 设备操作](#48-设备操作属性与影子)。

---

## 3. 空间 API（Space）

Base Path：`/matrix/v1/space`

### 3.1 创建空间 `POST /one`

创建根空间或子空间。

- **权限**：空间管理员。创建根空间时校验 `X-Org-Id` 组织的 admin；创建子空间时校验父空间组织的 admin。
- **请求体**：`SpaceEntity`（见 [5.1](#51-spaceentity)）

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 空间名称 |
| `type` | 是 | 空间类型：`site` / `building` / `zone` / `floor` / `room` / `field` / `workshop` / `parking` / `other` |
| `parentId` | 否 | 父空间 ID；不传则创建根空间 |
| `typeAlias` / `sortOrder` / `location` / `attributes` | 否 | 类型别名 / 排序 / 坐标 / 扩展属性 |

> `organizationId`、`level`、`ancestors`、`createTime` 等由服务端计算，客户端无需（也不应）传。

- **请求示例**（创建根空间）：

```bash
curl -X POST 'https://site.openxiot.cn/matrix/v1/space/one' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot' \
  -H 'Content-Type: application/json' \
  -d '{"name":"北京总部园区","type":"site"}'
```

- **请求示例**（创建子空间）：

```bash
curl -X POST 'https://site.openxiot.cn/matrix/v1/space/one' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot' \
  -H 'Content-Type: application/json' \
  -d '{"name":"A栋","type":"building","parentId":"66f2...0001"}'
```

- **响应**：`data` 为创建后的 `SpaceEntity`（含服务端生成的 `id`、`level`、`ancestors` 等）。

### 3.2 更新空间 `PUT /one`

- **权限**：空间管理员。
- **请求体**：`SpaceEntity`，`id` 必填。可更新 `name`、`type`、`typeAlias`、`sortOrder`、`attributes`；
  若修改 `parentId` 则相当于移动空间，服务端会重新计算 `level` 与 `ancestors`。
- **请求示例**：

```bash
curl -X PUT 'https://site.openxiot.cn/matrix/v1/space/one' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot' \
  -H 'Content-Type: application/json' \
  -d '{"id":"66f2...0001","name":"北京总部园区（已更名）","type":"site","sortOrder":1}'
```

- **响应**：`data` 为更新后的 `SpaceEntity`。

### 3.3 删除空间 `DELETE /one/{spaceId}`

- **权限**：空间管理员。
- **说明**：仅允许删除 **叶子空间**（无子空间），否则返回错误 `Cannot delete space with children`。
- **请求示例**：

```bash
curl -X DELETE 'https://site.openxiot.cn/matrix/v1/space/one/66f2...0002' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot'
```

- **响应**：`{"success": true}`。

### 3.4 查询单个空间 `GET /one/{spaceId}`

- **权限**：空间成员。
- **请求示例**：

```bash
curl 'https://site.openxiot.cn/matrix/v1/space/one/66f2...0001' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot'
```

- **响应**：`data` 为 `SpaceEntity`。

### 3.5 查询组织下所有根空间 `GET /all`

- **权限**：组织成员（无需指定 spaceId）。
- **请求示例**：

```bash
curl 'https://site.openxiot.cn/matrix/v1/space/all' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot'
```

- **响应**：`data` 为根空间列表（`level = 0`）。

### 3.6 查询空间树 `GET /tree/{rootId}`

- **权限**：空间成员。
- **说明**：返回根空间及其全部后代空间（扁平数组，根空间在最前）。
- **请求示例**：

```bash
curl 'https://site.openxiot.cn/matrix/v1/space/tree/66f2...0001' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot'
```

- **响应**：

```json
{
  "success": true,
  "data": [
    { "id": "66f2...0001", "name": "北京总部园区", "level": 0, "parentId": null },
    { "id": "66f2...0002", "name": "A栋", "level": 1, "parentId": "66f2...0001" },
    { "id": "66f2...0003", "name": "3F-会议室", "level": 2, "parentId": "66f2...0002" }
  ]
}
```

### 3.7 查询空间图 `GET /graph/{rootId}`

- **权限**：空间成员。
- **说明**：返回根空间及全部后代空间，并附带这些空间下的全部设备。
- **请求示例**：

```bash
curl 'https://site.openxiot.cn/matrix/v1/space/graph/66f2...0001' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot'
```

- **响应**：

```json
{
  "success": true,
  "data": {
    "spaces": [ { "id": "66f2...0001", "name": "北京总部园区", "level": 0 } ],
    "devices": [ { "did": "abc", "type": "urn:xiot-spec:device:...", "online": true, "space": { "spaceId": "66f2...0002", "rootId": "66f2...0001" } } ]
  }
}
```

---

## 4. 设备 API（Device）

Base Path：`/matrix/v1/device`

### 4.1 添加单个设备 `POST /one/{spaceId}`

注册单个设备到指定空间。

- **权限**：空间管理员。
- **请求体**：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `did` | 是 | 设备 ID |
| `key` | 是 | 设备访问密钥（AccessKey），用于向 manipulation 校验设备归属 |

> 额外字段会被忽略；前端「扫码添加设备」传入的即为此 key:value 结构。
>
> **归属按根空间记**：manipulation 侧的归属记录是 `(did, appId, ownerId)`，其中 `ownerId` 取
> **`spaceId` 所在空间的根空间** —— 注册到哪一级子空间都行，同一个根空间下的子空间共享归属。
> 设备要出现在两个根空间下，就注册两次（各自的 key）。跨根空间移动设备不被允许，见
> [4.6](#46-移动设备到其他空间-put-manyspace)。

- **请求示例**：

```bash
curl -X POST 'https://site.openxiot.cn/matrix/v1/device/one/66f2...0002' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot' \
  -H 'Content-Type: application/json' \
  -d '{"did":"abc","key":"a1b2c3d4e5f6"}'
```

- **响应**：`data` 为写入 Matrix 后的 `DeviceEntity`（见 [5.2](#52-deviceentity)）。

### 4.2 删除单个设备 `DELETE /one/{spaceId}/{did}`

- **权限**：空间管理员。
- **说明**：同步删除 manipulation 中的设备归属关系与 Matrix 中的设备记录。
- **请求示例**：

```bash
curl -X DELETE 'https://site.openxiot.cn/matrix/v1/device/one/66f2...0002/abc' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot'
```

- **响应**：`{"success": true}`。

### 4.3 查询单个设备 `GET /one/{spaceId}/{did}`

- **权限**：空间成员。
- **说明**：返回 Matrix 中记录的设备信息（含空间归属、在线状态、摘要等）。
- **请求示例**：

```bash
curl 'https://site.openxiot.cn/matrix/v1/device/one/66f2...0002/abc' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot'
```

- **响应**：`data` 为 `DeviceEntity`。

### 4.4 批量添加设备 `POST /many/{spaceId}`

- **权限**：空间管理员。
- **请求体**：`DeviceEntity[]` 形式的数组，每个元素包含 `did`、`key`。

```json
[
  { "did": "abc", "key": "a1b2c3d4e5f6" },
  { "did": "def", "key": "f6e5d4c3b2a1" }
]
```

- **请求示例**：

```bash
curl -X POST 'https://site.openxiot.cn/matrix/v1/device/many/66f2...0002' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot' \
  -H 'Content-Type: application/json' \
  -d '[{"did":"abc","key":"a1b2c3d4e5f6"},{"did":"def","key":"f6e5d4c3b2a1"}]'
```

- **响应**：`data` 为已添加的 `DeviceEntity` 列表。

### 4.5 批量删除设备 `DELETE /many/{spaceId}`

- **权限**：空间管理员。
- **Query 参数**：`did`（可重复，至少 1 个）

```bash
curl -X DELETE 'https://site.openxiot.cn/matrix/v1/device/many/66f2...0002?did=abc&did=def' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot'
```

- **响应**：`{"success": true}`。

### 4.6 移动设备到其他空间 `PUT /many/space`

- **权限**：目标空间的管理员。
- **请求体**：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `spaceId` | 是 | 目标空间 ID（其中的根空间由服务端推导，**不用传**） |
| `dids` | 是 | 需要移动的设备 ID 列表 |

> **不能跨根空间移动**：设备的归属记录挂在根空间上（见 [4.1](#41-添加设备-post-one)、
> [5.1](#51-spaceentity) 末关于根空间的说明），跨根空间搬只会让设备在新项目下报 "device not owned"。
> 目标空间的根 ≠ 设备当前空间的根时该接口报错，需先删除再添加。

- **请求示例**：

```bash
curl -X PUT 'https://site.openxiot.cn/matrix/v1/device/many/space' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot' \
  -H 'Content-Type: application/json' \
  -d '{"spaceId":"66f2...0003","dids":["abc","def"]}'
```

- **响应**：`{"success": true}`。

### 4.7 查询空间下所有设备 `GET /many/{spaceId}`

- **权限**：空间成员。
- **请求示例**：

```bash
curl 'https://site.openxiot.cn/matrix/v1/device/many/66f2...0002' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot'
```

- **响应**：`data` 为 `DeviceEntity` 列表。

### 4.8 设备操作（属性 / 影子 / 方法）

设备操作均基于 **PID / AID** 寻址（见 [2.5](#25-关键-id-概念来自-xiot-spec--xcp)），
由 Matrix 校验空间成员权限后转发给 manipulation 执行，返回每个操作项的状态。

#### 读取设备影子 `GET /shadows/{spaceId}`

- **权限**：空间成员。
- **Query 参数**：`did`（可重复）。**不传时**返回该空间下所有设备的影子。
- **请求示例**：

```bash
curl 'https://site.openxiot.cn/matrix/v1/device/shadows/66f2...0002?did=abc&did=def' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot'
```

- **响应**：`data` 为影子属性数组，每个元素为 `{pid, value}` 或 `{pid, status, description}`：

```json
{
  "success": true,
  "data": [
    { "pid": "abc.1.1", "value": 26 },
    { "pid": "abc.1.2", "value": true },
    { "pid": "abc.1.3", "status": -3, "description": "property not found" }
  ]
}
```

#### 读取属性 `GET /properties/{spaceId}`

- **权限**：空间成员。
- **Query 参数**：`pid`（可重复，格式 `{DID}.{SIID}.{PIID}`）
- **请求示例**：

```bash
curl 'https://site.openxiot.cn/matrix/v1/device/properties/66f2...0002?pid=abc.1.1&pid=abc.1.2' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot'
```

- **响应**：同 [读取设备影子](#读取设备影子-get-shadows--spaceid-) 的 `data` 结构。

#### 写入属性 `POST /properties/{spaceId}`

- **权限**：空间成员。
- **请求体**：数组，每个元素 `{pid, value}`；`value` 可为标量，也可为数组（combination 属性）。

```json
[
  { "pid": "abc.2.3", "value": 37 },
  { "pid": "abc.2.4", "value": 3600 },
  { "pid": "abc.2.5", "value": [10.7, 10.9] }
]
```

- **请求示例**：

```bash
curl -X POST 'https://site.openxiot.cn/matrix/v1/device/properties/66f2...0002' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot' \
  -H 'Content-Type: application/json' \
  -d '[{"pid":"abc.2.3","value":37},{"pid":"abc.2.4","value":3600}]'
```

- **响应**：`data` 为每个属性的执行结果：

```json
{
  "success": true,
  "data": [
    { "pid": "abc.2.3", "status": 0 },
    { "pid": "abc.2.4", "status": -2, "description": "property cannot write" }
  ]
}
```

#### 调用方法 `POST /actions/{spaceId}`

- **权限**：空间成员。
- **请求体**：数组，每个元素：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `aid` | 是 | 方法 ID，格式 `{DID}.{SIID}.{AIID}` |
| `oid` | 否 | 操作源 ID（透传） |
| `in` | 否 | 入参列表，每个元素 `{piid: <自然数>, values: [<值>, ...]}` |

```json
[
  {
    "aid": "abc.1.9",
    "in": [
      { "piid": 2, "values": [37] },
      { "piid": 3, "values": [3600, 1800] }
    ]
  }
]
```

- **请求示例**：

```bash
curl -X POST 'https://site.openxiot.cn/matrix/v1/device/actions/66f2...0002' \
  -H 'Authorization: Bearer <jwt>' \
  -H 'X-Org-Id: openxiot' \
  -H 'Content-Type: application/json' \
  -d '[{"aid":"abc.1.9","in":[{"piid":2,"values":[37]}]}]'
```

- **响应**：`data` 为每个方法的执行结果，成功时含 `out`（出参，格式同 `in`）：

```json
{
  "success": true,
  "data": [
    {
      "aid": "abc.1.9",
      "status": 0,
      "out": [ { "piid": 5, "values": [2700] } ]
    }
  ]
}
```

失败时单个元素为 `{aid, status, description}`（如 `{"aid":"abc.1.9","status":-3,"description":"action not found"}`）。

---

## 5. 数据模型

### 5.1 SpaceEntity

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 空间 ID（Mongo ObjectId 十六进制），创建后由服务端生成 |
| `name` | string | 空间名称（必填） |
| `type` | string | 空间类型：`site` / `building` / `zone` / `floor` / `room` / `field` / `workshop` / `parking` / `other` |
| `typeAlias` | string | 类型别名（可选） |
| `parentId` | string | 父空间 ID（根空间为 `null`） |
| `level` | int | 层级深度（根空间为 0） |
| `ancestors` | string[] | 祖先 ID 列表（不含自身，**从根往下排**） |
| `sortOrder` | int | 排序序号 |
| `location` | object | 坐标：`{type: "Point", longitude, latitude, coordinates: [lng, lat]}` |
| `organizationId` | string | 所属组织 ID（服务端设置） |
| `attributes` | object | 扩展属性 |
| `createTime` | string | 创建时间 `yyyy-MM-ddTHH:mm:ss` |
| `updateTime` | string | 更新时间 `yyyy-MM-ddTHH:mm:ss` |

> **根空间 ID 不落字段**（原先那个 `rootId` 字段已删）：它就是 `ancestors[0]`
> （根空间自己没有祖先，即自身）。**设备归属用的就是它**：给 manipulation 传的 `ownerId`、
> 以及响应里 `device.space.rootId` 都取这个值 —— 详见 [5.2](#52-deviceentity) 的 `space`。
>
> 删掉 `rootId` 的理由不是「没人写」—— 恰恰相反：它由**客户端**在创建子空间时自己填
> （服务端从不计算、也不写这个字段），而它恰好与 `ancestors[0]` 一致。问题是这个一致性
> 只能靠每个调用方自觉维持：服务端建的空间、或换一个客户端建，归属就会是空的。
> 现在统一由服务端从 `ancestors[0]` 现算。旧客户端仍传 `rootId` 不报错
> （Quarkus 缺省 `fail-on-unknown-properties=false`，未知字段被忽略），只是不再被采信。

### 5.2 DeviceEntity

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `did` | string | 设备 ID |
| `type` | string | DeviceType（URN，如 `urn:xiot-spec:device:fan:0000A005:...`） |
| `online` | boolean | 是否在线 |
| `protocol` | string | 接入协议（如 `xcp`） |
| `rootId` | string | 根设备 ID（设备组场景） |
| `parentId` | string | 父设备 ID（设备组场景） |
| `members` | string[] | 成员设备列表（仅设备组） |
| `lastOnline` | string | 最后一次上线时间 |
| `lastOffline` | string | 最后一次离线时间 |
| `space` | object | 所在空间：`{spaceId, rootId}` |
| `create` | string | 记录创建时间 |

> 设备注册接口（[4.1](#41-添加单个设备-post-onespaceid) / [4.4](#44-批量添加设备-post-manyspaceid)）的返回
> 即为此结构（来源于 manipulation 返回的设备摘要 + 空间归属）。

---

## 6. 错误码

设备操作（属性 / 影子 / 方法）返回的操作级 `status` 遵循 XCP 协议约定：

| status | 说明 |
| --- | --- |
| 0 | 成功 |
| 1 | 已接收到请求，但操作未完成（异步，稍后通过事件通知） |
| -1 | 不可读 |
| -2 | 不可写 |
| -3 | 不存在 |
| -4 | 内部错误 |
| -5 | 值（Value）错误 |
| -6 | 方法（Action）参数错误 |
| -7 | 验证错误 |

除上述操作级错误码外，接口本身的失败通过响应体 `{"success": false, "message": "<原因>"}` 表达，
常见原因包括：

| 场景 | message 示例 |
| --- | --- |
| 缺少 `X-Org-Id` | `X-Org-Id header is required` |
| 空间不存在 | `Space not found: <spaceId>` |
| 空间无权访问 | `No access to this space` |
| 空间未配置访问条目 | `Space accesses is not set` |
| 组织已不存在（如被删除） | `Organization not found` |
| 非组织成员 | `Not a member of the organization` |
| 非组织管理员 | `Not an admin of the organization` |
| 删除非叶子空间 | `Cannot delete space with children` |
| 父空间不存在 | `Parent space not found` |
| 请求体非法 | `Invalid JSON body` |
| manipulation 转发失败 | 下游返回的 `message` |

---

## 7. 调用流程示例

一个典型的「扫码添加设备 → 读取设备影子 → 写属性 → 调用方法」流程：

```bash
BASE='https://site.openxiot.cn/matrix/v1'
TOKEN='<service-account 签发的 JWT>'
ORG='openxiot'

# 1. 列出组织根空间
curl "$BASE/space/all" -H "Authorization: Bearer $TOKEN" -H "X-Org-Id: $ORG"

# 2. 在根空间下创建楼层
curl -X POST "$BASE/space/one" -H "Authorization: Bearer $TOKEN" -H "X-Org-Id: $ORG" \
  -H 'Content-Type: application/json' \
  -d '{"name":"3F","type":"floor","parentId":"<rootId>"}'

# 3. 扫码/录入设备（body 为 did + key）
curl -X POST "$BASE/device/one/<floorSpaceId>" -H "Authorization: Bearer $TOKEN" -H "X-Org-Id: $ORG" \
  -H 'Content-Type: application/json' -d '{"did":"abc","key":"a1b2c3d4e5f6"}'

# 4. 读取设备影子（不传 did 则返回该空间全部设备影子）
curl "$BASE/device/shadows/<floorSpaceId>" -H "Authorization: Bearer $TOKEN" -H "X-Org-Id: $ORG"

# 5. 写属性
curl -X POST "$BASE/device/properties/<floorSpaceId>" -H "Authorization: Bearer $TOKEN" -H "X-Org-Id: $ORG" \
  -H 'Content-Type: application/json' -d '[{"pid":"abc.1.2","value":true}]'

# 6. 调用方法
curl -X POST "$BASE/device/actions/<floorSpaceId>" -H "Authorization: Bearer $TOKEN" -H "X-Org-Id: $ORG" \
  -H 'Content-Type: application/json' -d '[{"aid":"abc.1.9","in":[{"piid":2,"values":[37]}]}]'
```

---

## 8. 统计 API（Statistics）

Base Path：`/matrix/v1/statistics`

### 8.1 数据看板总览 `GET /overview/{spaceId}`

一次取回数据看板（首页）一屏要用的全部数字：设备、Modbus 服务、阈值告警、Modbus 故障。
范围是**该空间及其全部后代空间**（项目 = 根空间 + 子空间，设备与服务可以挂在任一子空间上），
与 `GET /space/graph/{rootId}` 同一条路径。

- **权限**：空间成员（只读，不需要管理员）。
- **说明**：**为什么是一个聚合接口**：现成的清单接口都带条数上限（告警缺省 200 / 封顶 1000、
  故障 1000），且它们的 `summary` 只统计**本次返回的那一批** —— 拿它们算「今日告警 / 告警曲线 /
  类型分布」得到的是**被截断的下限**，而卡片和曲线完全看不出来。本接口不打上限，量级由窗口上限兜住。

**查询参数**：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `from` | 是 | 区间起点，**epoch 毫秒** |
| `to` | 否 | 区间终点，epoch 毫秒；缺省 = 现在 |

窗口由**调用方**算（后端没有「最近 N 分钟」这类参数，这是本服务的既有约定）：要看最近 24 小时的
告警曲线，就传「当前整点往前 23 小时」到「现在」，后端按整点返回 24 个桶。约束：

- `from` 必填；`to` 必须晚于 `from`（反着或零宽报错，不返回空结果）。
- 区间**不得超过 31 天**，否则报 `time range too wide`（本接口不打条数上限，窗口是唯一的量级闸门）。
- 报错与别的接口一致：HTTP 200 + `{"success": false, "message": "..."}`。

**响应 `data` 字段**：

| 字段 | 说明 |
| --- | --- |
| `from` / `to` | 实际生效的区间（`to` 缺省时补的是「现在」，回显给调用方） |
| `devices` | `{total, online, byType: [{type, count}]}`；`type` 是设备 URN 的**第 4 段**（如 `dtu`） |
| `services` | `{total, byConfig: [{configId, count}]}`；按服务所配的**点表 id** 分组，未配点表的落在 `""` 键上 |
| `alarms` | `{total, byText: [{text, count}], hourly: [{at, count}]}`；按**告警文本**（用户自己填的）分组 |
| `failures` | `{total, hourly: [{at, count}]}` |

口径说明：

- **`total` 都是整个窗口内的条数**。「今日」由调用方从 `hourly` 里取桶起点在本地今天 00:00 之后的
  那些相加（今日恒被 24 小时窗口盖住），曲线与数字因此同源。
- **`hourly` 是整点桶，窗口内每个桶都在**（没有发生的整点给 `count: 0`）—— 调用方不必猜
  「这段是没数据还是没请求」。桶的 `at` 是桶**起点**的 epoch 毫秒。
- **`failures.total` 是「有几种失败」而不是「失败了多少次」**：故障行在写入时就按 message 去重，
  同一条消息只记首次出现的那一行。
- 三份分组都按**条数降序、同数按名称升序**排好，调用方拿到即画。缺键（设备没类型、告警没文本）
  归到 `UNKNOWN` 桶，让计数与总数对得上。
- `type` / `configId` / `text` 全是**数据**，原样下发、**永不翻译**。

- **请求示例**（最近 24 小时，按整点对齐）：

```bash
HOUR=3600000
NOW=$(date +%s000)
FROM=$(( NOW / HOUR * HOUR - 23 * HOUR ))

curl "$BASE/statistics/overview/66f2...0001?from=$FROM&to=$NOW" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Org-Id: $ORG"
```

- **响应**：

```json
{
  "success": true,
  "data": {
    "from": 1757833200000,
    "to": 1757919600000,
    "devices": { "total": 12, "online": 9, "byType": [{ "type": "dtu", "count": 5 }] },
    "services": { "total": 7, "byConfig": [{ "configId": "65f0...", "count": 3 }, { "configId": "", "count": 1 }] },
    "alarms": {
      "total": 42,
      "byText": [{ "text": "温度过高", "count": 9 }],
      "hourly": [{ "at": 1757833200000, "count": 2 }]
    },
    "failures": { "total": 5, "hourly": [{ "at": 1757833200000, "count": 1 }] }
  }
}
```

