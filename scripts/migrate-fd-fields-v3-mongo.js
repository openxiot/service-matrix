/*
 * 数据清洗：db.fds 的 request/response 字段从「名字 + 内联格式」改成「引用寄存器 registryIndex」，
 * 并把 version 2 → 3。（与 scripts/migrate-fd-fields-registry-v3.sh 同口径，但走直连 Mongo，
 * 因为现在线上已是 v3 后端、其 GET 会把 v2 字段名丢掉，API 版脚本已套不上。）
 *
 *   旧 response.fields[i] = { index, field:<名>, bytes, format, byteOrder, scale, unit, ... }
 *   新 response.fields[i] = { registryIndex }
 *   旧 request.fields[i]  = { index, field:<名>, format, offset?, byteOrder?, value? }
 *   新 request.fields[i]  = { registryIndex, value? }        （value 用 != null 保留）
 *
 * registryIndex 由**该 fd 的 configSnapshot.registries**按名字（registry.name === field 名）解析。
 * 解析不出 → 记 [err] 并**整条 fd 跳过**（不写坏数据）。幂等：已是 registryIndex 的字段跳过。
 *
 * 前置：线上后端已是 v3 字段版（真的——v2 fds 现在就是坏的，本脚本是把它修好）。
 * 用法：
 *   mongosh "<MONGO_URI>" --file migrate-fd-fields-v3-mongo.js
 *   DRY_RUN=1 mongosh "<MONGO_URI>" --file migrate-fd-fields-v3-mongo.js
 */
const DRY = !!process.env.DRY_RUN;
const db = db.getSiblingDB('modbus');

function byNameMap(registries) {
  const m = {};
  for (const r of registries || []) {
    if (r && r.index != null) m[r.name == null ? '' : r.name] = r.index;
  }
  return m;
}

function migrateField(f, byName, errs, where) {
  if (!f) return f;
  if ('registryIndex' in f && f.registryIndex != null) return f; // 已迁
  const name = f.field == null ? '' : f.field;
  const idx = byName[name];
  if (idx == null) { errs.push(`${where} 字段名读不到寄存器: ${JSON.stringify(name)}`); return f; }
  const n = { registryIndex: idx };
  // 写字段的缺省值用 != null 判（false / 0 是有效值）
  if (f.value != null) n.value = f.value;
  return n;
}

let fdTotal = 0, fdChanged = 0, fdErr = 0;
db.fds.find({}).forEach((fd) => {
  fdTotal++;
  const byName = byNameMap(fd.configSnapshot && fd.configSnapshot.registries);
  const errs = [];
  let changed = false;

  const polls = (fd.polls || []).map((p) => {
    const np = Object.assign({}, p);
    if (p.request && Array.isArray(p.request.fields) && p.request.fields.length) {
      const rf = p.request.fields.map((f, i) => migrateField(f, byName, errs, `poll#${p.index} req[${i}]`));
      if (rf.some((x, i) => x !== p.request.fields[i])) { changed = true; np.request = Object.assign({}, p.request, { fields: rf }); }
    }
    if (p.response && Array.isArray(p.response.fields) && p.response.fields.length) {
      const rs = p.response.fields.map((f, i) => migrateField(f, byName, errs, `poll#${p.index} resp[${i}]`));
      if (rs.some((x, i) => x !== p.response.fields[i])) { changed = true; np.response = Object.assign({}, p.response, { fields: rs }); }
    }
    return np;
  });

  if (errs.length) { fdErr++; print(`[err] fd ${fd._id} (${fd.name}): ${errs.join(' , ')}`); return; } // 有未解析 → 整条跳过

  const needVersion = fd.version !== 3;
  if (changed || needVersion) {
    fdChanged++;
    if (!DRY) db.fds.updateOne({ _id: fd._id }, { $set: { polls: polls, version: 3 } });
  }
});

print(`[fds] 共 ${fdTotal} 条，需改 ${fdChanged} 条，失败 ${fdErr} 条${DRY ? '（干跑，未落库）' : ''}`);
