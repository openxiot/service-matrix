#!/usr/bin/env bash
#
# 数据清洗：modbus 库命令形状 → 寄存器中心（读=start+quantity、写=writeItems[{registryIndex,value}]）。
# 覆盖 db.configs[].commands 与 db.fds[].configSnapshot.commands。详见 migrate-command-writeitems.js 顶部注释。
#
# 前置：**目标环境的后端已部署「命令迁移」版**（新形状读得懂；且新前端依赖 writeItems）。
#      建议顺序：备份 → 部署后端 → 跑本脚本 → 部署前端。
#
# 用法：
#   MONGO_URI='mongodb+srv://...' ./scripts/migrate-command-writeitems.sh
#
# 干跑（只打印要改多少、不落库）：
#   DRY_RUN=1 MONGO_URI='mongodb+srv://...' ./scripts/migrate-command-writeitems.sh
set -euo pipefail

MONGO_URI="${MONGO_URI:?MONGO_URI is required (openxiot-staging 连接串)}"
HERE="$(cd "$(dirname "$0")" && pwd)"

if ! command -v mongosh >/dev/null 2>&1; then
  echo "[错误] 未找到 mongosh" >&2
  exit 2
fi

if [ -n "${DRY_RUN:-}" ]; then
  echo "== 干跑（DRY_RUN=1，不落库）=="
  DRY_RUN=1 mongosh "$MONGO_URI" --quiet --file "$HERE/migrate-command-writeitems.js"
else
  echo "== 正式执行（会改写 modbus.configs / modbus.fds）=="
  mongosh "$MONGO_URI" --quiet --file "$HERE/migrate-command-writeitems.js"
fi
