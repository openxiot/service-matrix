#!/usr/bin/env bash
#
# 一次性迁移：Modbus 服务的 function.request 从「整帧 hex 字符串」改成结构化定义，
# 并把 version 1 -> 2（见 MODBUS.md 第八节 2026-09-23）。
#
# 背景：request 以前是前端算好 CRC16 的整帧（如 "010300010001D5CA"），现在是
# {slaveId, fc, start, quantity}（读方法）或 {slaveId, fc, start, fields[]}（写方法）；
# 同时 response 从裸数组包成对象 {"fields": [...]}（与 request 对称，一回事的两面）。
#
#   * 读方法（01/02/03/04）的迁移**无损**：slave/fc/start/quantity 四个数在帧里逐一对应，
#     response 里的字段一个字节不改（只是外面包一层 fields）、CRC 丢掉由后端在 invoke 时现算。
#   * 写方法只处理 05/06（单值，值能从帧里直接反解）：补字段名 "写入值"（迁移后请改名），
#     06 的格式按值是否超过 0x7FFF 取 int16 / uint16（帧里没有类型信息，无法再细）。
#   * fc 0F / 10（多值写入）脚本**拒绝处理**：数据区能反解出值，但字段名与每段的类型/字节序
#     无从得知，只能手工重建一个结构化 request（字段名会丢，这是这个版本口径变化的代价）。
#   * 轮询：写方法一律不许开 polling，脚本顺手把写方法的 interval / polling 去掉；
#     读方法的 interval / polling 原样保留。
#   * response：裸数组包成 {"fields": [...]}；写方法的空数组（[]）直接删掉那个键
#     （新口径下写方法就是「没有 response」这一种写法）。
#
# 脚本是**幂等**的：request 已经是对象、response 已经是对象（或没有）的服务直接跳过。
#
# 前置条件：
#   1. **目标环境的后端已部署 version 2 的代码**。旧后端把 request 当字符串解，PUT 必然失败。
#      自检：GET 回来的 .data.version 是 2 就说明部署好了（脚本对已迁移的服务会跳过）。
#   2. 调用者必须是这些空间的**空间管理员**（PUT 要求 space admin，见 MODBUS.md 第四节）。
#
# 用法：
#   BASE_URL=https://matrix.openxiot.cn TOKEN='<jwt>' \
#     ./scripts/migrate-service-request-v2.sh <spaceId>:<fdId> [<spaceId>:<fdId> ...]
#
# 干跑（只打印会 PUT 上去的 body，不发请求）：
#   DRY_RUN=1 BASE_URL=... TOKEN=... ./scripts/migrate-service-request-v2.sh ...
#
# 迁移后回读确认：再跑一次，全部打印 [跳过] 即成功。
set -euo pipefail

BASE_URL="${BASE_URL:?BASE_URL is required, e.g. https://matrix.openxiot.cn}"
TOKEN="${TOKEN:?TOKEN is required (空间管理员的 JWT)}"
DRY_RUN="${DRY_RUN:-}"

if [ "$#" -eq 0 ]; then
  echo "用法：BASE_URL=... TOKEN=... $0 <spaceId>:<serviceId> [...]" >&2
  exit 2
fi

# 「不用动」的判定单独抽出来：跳过与干跑读的是同一份口径。
# 用谓词而不是「version == 2 就跳过」：万一有服务只迁移了一半（request 是对象、response 还是数组），
# 那种也得接着修。
JQ_MIGRATED='
def migrated:
  (.version == 2)
  and ([.functions[]? | select((.request | type) != "object" or (.response | type) == "array")] | length == 0);
'

# 帧 -> 结构化 request。读方法取四个标量；写方法 05/06 补一个带缺省值的字段。
# hex2dec 手写：jq 的 tonumber 不认 "0x" 前缀。
JQ_TRANSFORM="$JQ_MIGRATED"'
def hex2dec:
  reduce (explode | map(if . < 58 then . - 48 elif . < 71 then . - 55 else . - 87 end)[]) as $d (0; . * 16 + $d);

def toFunction:
  (.request | if type != "string" then .          # 已经是结构化定义（局部迁移过）
   else
     (ascii_upcase) as $h
     | if ($h | test("^[0-9A-F]+$")) | not then error("不是合法的 16 进制帧: \($h)")
       else
         ($h[2:4]) as $fc
         | if ($fc | test("^0[1-4]$")) then       # 读：帧里的数量就是 quantity（寄存器数 / 位数）
             if ($h | length) != 16 then error("fc \($fc) 的读帧应为 8 字节: \($h)")
             else {slaveId: ($h[0:2] | hex2dec), fc: $fc, start: ($h[4:8] | hex2dec),
                   quantity: ($h[8:12] | hex2dec)} end
           elif ($fc == "05" or $fc == "06") then
             if ($h | length) != 16 then error("fc \($fc) 的写帧应为 8 字节: \($h)")
             else ($h[8:12] | hex2dec) as $data
               | {slaveId: ($h[0:2] | hex2dec), fc: $fc, start: ($h[4:8] | hex2dec),
                  fields: [{index: 1, field: "写入值",
                            format: (if $fc == "05" then "bit"
                                     elif $data > 32767 then "uint16" else "int16" end),
                            value: (if $fc == "05" then $data == 65280 else $data end)}]}   # FF00 = 合
             end
           else error("fc \($fc) 无法反解（0F/10 多值写入与未知功能码都请手工重建 request）") end
       end
   end) as $r
  | .request = $r
  # 写方法一律不许开轮询（校验器会拒），顺手摘掉旧定义里可能留着的周期
  | if ($r.fc | test("^0[1-4]$")) then . else del(.interval, .polling) end;

# 应答定义从裸数组包成对象（见 MODBUS.md 2026-09-23：response 与 request 对称）。
# 空数组是写方法的老写法，新口径下「写方法没有 response」——删键，不留 {"fields": []}。
def wrapResponse:
  if (.response | type) == "array" then
    if (.response | length) == 0 then del(.response) else .response = {fields: .response} end
  else . end;

# 不需要动：已经是新口径（version 2 + request 是对象 + 没有裸数组的 response）。
.data
| del(.id, .creator, .updater)                       # 这三个不由请求体决定
| .version = 2
| .functions = [.functions[] | toFunction | wrapResponse]
'

fail=0
for target in "$@"; do
  space="${target%%:*}"
  id="${target##*:}"
  if [ "$space" = "$target" ] || [ -z "$space" ] || [ -z "$id" ]; then
    echo "[错误] 参数应为 <spaceId>:<serviceId>，收到：$target" >&2
    fail=1
    continue
  fi

  url="$BASE_URL/matrix/v1/modbus/device/one/$space/$id"
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
    echo "[跳过] $name ($id) 已经是新口径（version 2、request 结构化、response 是对象）"
    continue
  fi

  if ! body="$(jq -c "$JQ_TRANSFORM" <<<"$resp" 2>&1)"; then
    echo "[错误] $name ($id) 转换失败：$body" >&2
    fail=1
    continue
  fi

  echo "==> $name ($id)  version $version -> 2"
  # response 只报形状（字段个数），不整段摊开：读方法的字段表很长，而它本来就不该变
  jq '.functions[] | {index, name, request,
        response: (if (.response | type) == "object" then "fields[\(.response.fields | length)]"
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
