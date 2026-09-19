#!/usr/bin/env bash
#
# 一次性迁移：矩阵库里的看板布局集合 dashboard-layouts -> web-dashboard-layouts
#
# 背景：Web 看板的类与数据集名加了 WebDashboard 前缀后，Mongo 集合名同步改为
# web-dashboard-layouts（见 db/dashboard/web/WebDashboardLayoutEntity 的
# @MongoEntity(collection = "web-dashboard-layouts")）。移动端看板将另用
# mobile-dashboard-layouts，二者不再混用一个集合。
#
# 这个脚本把已存在的旧集合"改名"（renameCollection 在同一个库里移动整个集合，
# 连同文档与索引一起过去，原子完成）。新集合的 spaceId 唯一索引由 WebDashboardLayoutRepository
# 的 ensureIndexes() 在服务启动时自动补建，无需本脚本处理。
#
# 用法：
#   MONGOSH_URI='mongodb://<host>:27017/matrix' ./scripts/migrate-dashboard-layouts.sh
# 缺省连本机 localhost:27017/matrix。
set -euo pipefail

URI="${MONGOSH_URI:-mongodb://localhost:27017/matrix}"

echo "==> 迁移 $URI 的 dashboard-layouts -> web-dashboard-layouts"

mongosh "$URI" --quiet --norc <<'EOF'
const SRC = 'dashboard-layouts';
const DST = 'web-dashboard-layouts';
const names = db.getCollectionNames();

if (names.includes(DST)) {
  print(`[跳过] 目标集合 ${DST} 已存在：很可能是之前已经迁移过。`);
  quit(1);
}
if (!names.includes(SRC)) {
  print(`[跳过] 源集合 ${SRC} 不存在（还没有任何看板布局）：无需迁移。`);
  quit(0);
}
const n = db.getCollection(SRC).countDocuments();
db.getCollection(SRC).renameCollection(DST);
print(`[完成] ${SRC} -> ${DST}，迁移 ${n} 份布局。`);
EOF