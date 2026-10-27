#!/usr/bin/env bash
#
# 一次性迁移：ModbusDevice -> FieldDevice 重命名的数据库侧。
#
# 背景（对齐 service-matrix 本次修改）：
#   * 实体/资源/变量：ModbusDevice -> FieldDevice（fd / fds / fdid / fdids）；
#   * 存储字段：serviceId -> fdid（影子/告警/两类历史/轮询任务表）；
#   * 集合名：services -> fds，service-shadows -> fd-shadows，
#     service-alarms -> fd-alarms，service-registry-histories -> fd-registry-histories，
#     service-polling-histories -> fd-polling-histories（polls / configs 不变）。
#
# 只做「键名 + 集合名」改名，值原样保留（serviceId 存的是十六进制字符串，
# 与 FieldDevice 的 _id hex 一致，无需类型转换；已核对 staging 数据）。
#
# 顺序：先拷贝一份 bak 备份（库内同名 *_bak_fdrename，只备一次，重复跑跳过），
# 再字段改名，最后 renameCollection。幂等：源不存在跳过、目标已存在且有数据则中止不覆盖
# （目标为空——新后端启动时 ensureIndexes 建了空集合——先删空集合再让位）。
#
# 说明：脚本**不**处理告警里 pollingIndex -> pollIndex 与旧集合 service-failures /
# service-last-polling 的清理——那是早于本次重命名的告警形状漂移与已停写的历史集合，
# 与 ModbusDevice->FieldDevice 改名无关，留给你另行决定（见文末备注）。
#
# 用法：
#   MONGOSH_URI='mongodb+srv://.../openxiot-staging...' ./scripts/migrate-modbus-fd-rename.sh
# 缺省连本机 localhost:27017/modbus。
set -euo pipefail

URI="${MONGOSH_URI:-mongodb://localhost:27017/modbus}"
DRY_RUN="${DRY_RUN:-}"

echo "==> 迁移 $URI 的 modbus 库：ModbusDevice -> FieldDevice 集合/字段改名"
[ -n "$DRY_RUN" ] && echo "==> DRY_RUN=1：只看计划，不改任何数据"

mongosh "$URI" --quiet --norc <<'EOF'
const DB = db.getSiblingDB('modbus');
const BAK_SUFFIX = '_bak_fdrename';
const DRY = process.env.DRY_RUN === '1';
const did = DRY ? (msg => print('[DRY] ' + msg)) : (msg => print(msg));

print(`[库] ${DB.getName()}`);

// 备份一份（幂等：同名 bak 已存在则跳过）。
function backup(coll) {
  const bak = coll + BAK_SUFFIX;
  if (DB.getCollectionNames().includes(bak)) {
    print(`[跳过备份] ${bak} 已存在`);
    return;
  }
  const n = DB.getCollection(coll).countDocuments();
  if (n === 0) { print(`[跳过备份] ${coll} 为空`); return; }
  if (!DRY) DB.getCollection(coll).aggregate([{ $out: bak }]).toArray();
  did(`[备份] ${coll} -> ${bak} (${n} 条)`);
}

// 字段改名（若干集合内 batch）：默认 serviceId -> fdid，留参以便个别集合改别的字段。
function renameField(coll, from = 'serviceId', to = 'fdid') {
  const c = DB.getCollection(coll);
  const n = c.countDocuments({ [from]: { $exists: true } });
  if (n === 0) { print(`[跳过字段] ${coll} 无 ${from}`); return; }
  if (!DRY) {
    const r = c.updateMany({}, { $rename: { [from]: to } });
    print(`[字段] ${coll}.${from} -> ${to} (${r.modifiedCount} 条)`);
  } else {
    did(`[字段] ${coll}.${from} -> ${to} (${n} 条)`);
  }
}

// 集合改名（幂等：源不存在跳过；目标存在且有数据则中止；空目标先删让位）。
function renameColl(src, dst) {
  const names = DB.getCollectionNames();
  if (!names.includes(src)) { print(`[跳过] 源 ${src} 不存在`); return; }
  if (names.includes(dst)) {
    const n = DB.getCollection(dst).countDocuments();
    if (n === 0) {
      if (!DRY) DB.getCollection(dst).drop();
      print(`[处理] 目标 ${dst} 存在但为空，${DRY ? '将删' : '已删'}以让位`);
    } else {
      print(`[中止] 目标 ${dst} 已存在且有 ${n} 条，不覆盖。`);
      process.exit(1);
    }
  }
  const n = DB.getCollection(src).countDocuments();
  if (!DRY) DB.getCollection(src).renameCollection(dst);
  did(`[完成] ${src} -> ${dst} (${n} 条)`);
}

// ---- 派生集合：备份 -> 字段改名 -> 集合改名 ----
// 在旧名上先改名（DRY 下集合还没真搬，必须在改名集合前做字段改名，两种模式才都能验证）。
for (const src of ['service-shadows', 'service-alarms',
                   'service-registry-histories', 'service-polling-histories']) {
  const dst = src.replace(/^service-/, 'fd-');
  backup(src);
  renameField(src, 'serviceId', 'fdid');
  // 告警的存量形状漂移（早于本次改名，见文末备注）：pollingIndex -> pollIndex，
  // 新代码按 pollIndex 查，旧文档是 pollingIndex，改齐才能读到。
  if (src === 'service-alarms') renameField(src, 'pollingIndex', 'pollIndex');
  renameColl(src, dst);
}

// ---- polls 保持集合名，只改字段 ----
backup('polls');
renameField('polls');

// ---- 主集合：services -> fds（无 serviceId 字段，只改集合名）----
backup('services');
renameColl('services', 'fds');

if (!DRY) {
  print('\n[核对 modbus]');
  const n_fds = DB.getCollection('fds').countDocuments();
  const n_poll = DB.getCollection('polls').countDocuments({ 'fdid': { $exists: true } });
  print(`  fds = ${n_fds} 条`);
  print(`  polls 里 fdid 字段条数 = ${n_poll}`);
  print('  全部完成（新集合名见下）：');
  DB.getCollectionNames().filter(n => n.includes('fd-') || n === 'fds' || /bak_fdrename/.test(n)).forEach(n => print(`    - ${n} (${DB.getCollection(n).countDocuments()} 条)`));
}

// =====================================================================
// 第二段：dashboard 布局（matrix 库）里 widget config 的 serviceId -> fdid
// 后端 DashboardWidget* 也随 fd 重命名把「卡片引用哪个 field device」的键改成了 fdid
// （source 仍是 serviceField / field 不变）。存量布局要做同键名迁移，否则部署后
// 引用 field device 的卡片取不到数、也过不了结构/归属校验。
// 布局文档只有个位数，用 JS 递归改名最直白，也天然深拷贝出新文档（不浅改原对象）。
// ---------------------------------------------------------------------
const MATRIX = db.getSiblingDB('matrix');
const LAYOUT_COLLS = ['web-dashboard-layouts', 'mobile-dashboard-layouts'];

// 递归把对象里所有名为 from 的键改成 to（值深拷贝，不改原对象）。
function renameKey(o, from, to) {
  if (Array.isArray(o)) return o.map(v => renameKey(v, from, to));
  if (o === null || typeof o !== 'object') return o;
  const out = {};
  for (const k of Object.keys(o)) {
    const nk = (k === from) ? to : k;
    out[nk] = renameKey(o[k], from, to);
  }
  return out;
}

for (const coll of LAYOUT_COLLS) {
  const c = MATRIX.getCollection(coll);
  if (!MATRIX.getCollectionNames().includes(coll)) { print(`[跳过] matrix.${coll} 不存在`); continue; }
  const bak = coll + BAK_SUFFIX;
  const total = c.countDocuments();
  let hit = 0;
  c.find({}).forEach(doc => {
    if (JSON.stringify(doc).includes('"serviceId"')) hit++;
  });
  print(`[布局] ${coll}: ${total} 份，含 serviceId 的 ${hit} 份`);
  if (hit === 0) continue;
  if (!DRY) {
    const bakHit = MATRIX.getCollectionNames().includes(bak);
    if (!bakHit) {
      c.aggregate([{ $out: bak }]).toArray();
      print(`[备份] matrix.${coll} -> ${bak} (${total} 份)`);
    } else {
      print(`[跳过备份] ${bak} 已存在`);
    }
    c.find({}).forEach(doc => {
      const nw = renameKey(doc, 'serviceId', 'fdid');
      c.replaceOne({ _id: doc._id }, nw);
    });
    print(`[布局] ${coll}.widgets[*].config.serviceId -> fdid`);
  } else {
    did(`[布局] matrix.${coll}: ${hit} 份的 widgets[*].config.serviceId -> fdid`);
  }
}
EOF
echo "==> 迁移脚本执行完毕"

# 备注（另行决定）：
#   1. 告警形状漂移：本脚本已把 fd-alarms 的 pollingIndex -> pollIndex，让存量告警能被新后端读到。
#      但存量还缺 state/recoveredAt/closeType/handledBy 等新字段——旧文档这些会是 null/缺省，
#      属告警模块自身的旧形状，不影响本改名迁移的完成度，可另行评估是否补齐/重建。
#   2. 停写的历史集合 service-failures / service-last-polling：新代码不再读写，可留着或
#      择机删除存档。本次未动。