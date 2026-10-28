#!/usr/bin/env bash
#
# 一次性迁移：现场设备（FieldDevice，modbus/fd）的 request/response **字段**从「内联
# name + format/bytes/byteOrder/scale/unit/...」改成「引用寄存器 registryIndex」，version 2 -> 3。
#
# 背景：寄存器中心的点表允许**寄存器重名**，而旧口径的字段身份是「字段名字符串」——
# 同名寄存器在同一条 response 里会互相覆盖，校验器也强制重名唯一，导致这类点表存不上。
# 新版字段只带 registryIndex，重名不再冲突。
#
#   * 每个 response 字段：{index, field, bytes, format, byteOrder, scale, unit, value-list, bit-list}
#     -> {registryIndex: <寄存器序号>}；解码元数据（格式/字节序/缩放/单位/枚举）全部由被引用的
#     寄存器携带（configSnapshot.registries 里已具备）。
#   * 每个 request 字段：{index, field, offset, format, byteOrder, value}
#     -> {registryIndex: <寄存器序号>, value?}。
#   * registryIndex 按**字段名前匹配**被引用寄存器（configSnapshot.registries[].name == 字段名）。
#     v2 能存下来的服务字段名在点表内唯一（v2 校验器强制），故名字匹配无歧义。
#   * 名字在点表里找不到（写方法应答应有的字段、位名……）→ **拒绝处理**（严格模式要求每个字段
#     都引用一只已定义寄存器），手工重建。
#   * value-list / bit-list 直接丢弃：枚举/位现在从寄存器的 enumMap / area 推导。
#
# 幂等：version == 3 且所有字段带 registryIndex 的服务直接跳过。
#
# 前置条件：
#   1. **目标环境的后端已部署 version 3 的代码**（PUT 由旧后端会拒：校验器硬等 version == 3）。
#   2. 调用者对 service-matrix 有 PUT 权限。
#
# 用法（SRC 是 GET/PUT 的对象只写两个形态字段）：
#   BASE_URL=https://matrix.openxiot.cn TOKEN='<jwt>' \
#     ./scripts/migrate-fd-fields-registry-v3.sh <spaceId>:<fdId> [<spaceId>:<fdId> ...]
#
# 干跑（只打印会 PUT 上去的 body，不发请求）：
#   DRY_RUN=1 BASE_URL=... TOKEN=... ./scripts/migrate-fd-fields-registry-v3.sh ...
set -euo pipefail

BASE_URL="${BASE_URL:?BASE_URL is required, e.g. https://matrix.openxiot.cn}"
TOKEN="${TOKEN:?TOKEN is required (管理员的 JWT)}"
DRY_RUN="${DRY_RUN:-}"

if [ "$#" -eq 0 ]; then
  echo "用法：BASE_URL=... TOKEN=... $0 <spaceId>:<fdId> [...]" >&2
  exit 2
fi

# 幂等判定：version == 3，且没有任何字段仍旧带内联 .field
JQ_MIGRATED='
def migrated:
  (.version == 3)
  and ([.polls[]?
        | (.request.fields[]? | has("field"))
        | .  // empty] | length) == 0
  and ([.polls[]?
        | (.response.fields[]? | has("field"))
        | .  // empty] | length) == 0;
'

JQ_TRANSFORM="$JQ_MIGRATED"'
# 寄存器名 -> 序号查询表（用 reduce 建 map，name 重复时靠后覆盖——v2 能存档的字段名点表内唯一）
. as $root
| ($root.configSnapshot.registries | map({key: (.name // ""), value: .index}) | from_entries) as $byName
| def idx($name): $byName[$name] // error("字段名不在点表寄存里: \($name)");
| .data
| del(.id, .creator, .updater)
| .version = 3
| .polls = [.polls[] |
    (.request.fields? | if . then
       [ .[] | {registryIndex: idx(.field), value: .value}
         + (if has("bitList") or has("value-list") then error("写字段不该有 bit/value-list") else {} end) ]
     else null end) as $rf
    | (.response.fields? | if . then
       [ .[] | {registryIndex: idx(.field)}]
       else null end) as $x
    | if (.request.fields? == null) then . elif $rf != null then .request.fields = $rf else . end
    | if (.response.fields? == null) then . elif $x != null then .response.fields = $x else . end
  ]
'

fail=0
for target in "$@"; do
  space="${target%%:*}"
  id="${target##*:}"
  if [ "$space" = "$target" ] || [ -z "$space" ] || [ -z "$id" ]; then
    echo "[错误] 参数应为 <spaceId>:<fdId>，收到：$target" >&2
    fail=1
    continue
  fi

  url="$BASE_URL/matrix/v1/modbus/fd/one/$space/$id"
  resp="$(curl -sS -m 30 -H "Authorization: Bearer $TOKEN" "$url")" || {
    echo "[错误] $id GET 请求失败" >&2
    fail=1
    continue
  }
  if [ "$(jq -r '.success // false' <<<"$resp")" != "true" ]; then
    echo "[错误] $id GET 未成功：$(jq -c . <<<"$resp")" >&2
    fail=1
    continue
  fi

  version="$(jq -r '.data.version' <<<"$resp")"
  name="$(jq -r '.data.name' <<<"$resp")"
  if [ "$(jq -r "$JQ_MIGRATED"'.data | migrated' <<<"$resp")" = "true" ]; then
    echo "[跳过] $name ($id) 已是新口径（version 3、字段已引用 registryIndex）"
    continue
  fi

  if ! body="$(jq -c "$JQ_TRANSFORM" <<<"$resp" 2>&1)"; then
    echo "[错误] $name ($id) 转换失败：$body" >&2
    fail=1
    continue
  fi

  echo "==> $name ($id)  version $version -> 3"
  jq '.polls[] | {index, name, request,
        response: (if (.response | type) == "object"
                   then "fields[\(.response.fields | length)]"
                   else (.response | type) end)}' <<<"$body"

  if [ -n "$DRY_RUN" ]; then
    echo "[干跑] 未发送 PUT"
    continue
  fi

  put="$(curl -sS -m 30 -X PUT -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' -d "$body" "$url")" || {
    echo "[错误] $id PUT 请求失败" >&2
    fail=1
    continue
  }
  if [ "$(jq -r '.success // false' <<<"$put")" != "true" ]; then
    echo "[错误] $id PUT 未成功：$(jq -c . <<<"$put")" >&2
    fail=1
    continue
  fi
  echo "[完成] $name ($id) 已迁移，version=$(jq -r '.data.version' <<<"$put")"
done

exit "$fail"