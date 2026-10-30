#!/usr/bin/env bash
#
# 一次性迁移：卡片展示配置的主键 ObjectId -> did / fdid（spaceId 降级为派生字段）。
# 覆盖 matrix 库的 device-card-configs 与 fd-card-configs。详见 migrate-card-config-id.js 顶部注释。
#
# 前置：**与新后端部署紧挨着跑**（旧后端读不了新文档、新后端读不了旧文档）。
#      建议顺序：备份 → 部署新后端 → 跑本脚本；中间窗口里卡片退回缺省（前端静默兜底）。
#
# 用法：
#   MONGO_URI='mongodb+srv://...' ./scripts/migrate-card-config-id.sh
#
# 干跑（只打印要改多少、不落库）：
#   DRY_RUN=1 MONGO_URI='mongodb+srv://...' ./scripts/migrate-card-config-id.sh
set -euo pipefail

MONGO_URI="${MONGO_URI:?MONGO_URI is required (openxiot-staging 连接串)}"
HERE="$(cd "$(dirname "$0")" && pwd)"

if ! command -v mongosh >/dev/null 2>&1; then
  echo "[错误] 未找到 mongosh" >&2
  exit 2
fi

if [ -n "${DRY_RUN:-}" ]; then
  echo "== 干跑（DRY_RUN=1，不落库）=="
  DRY_RUN=1 mongosh "$MONGO_URI" --quiet --file "$HERE/migrate-card-config-id.js"
else
  echo "== 正式执行（会改写 matrix.device-card-configs / matrix.fd-card-configs 的主键）=="
  mongosh "$MONGO_URI" --quiet --file "$HERE/migrate-card-config-id.js"
fi
