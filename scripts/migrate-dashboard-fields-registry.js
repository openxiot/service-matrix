/*
 * 数据清洗：matrix.web-dashboard-layouts 里卡片引用的「寄存器字段」从**名字**改成**registryIndex**。
 *
 *   现场设备卡（type:'fd'）：config.fields: string[]（寄存器名）→ number[]（registryIndex 列表）
 *   曲线卡（type:'line' && config.source==='fdField'）：config.field: string（名）→ number（registryIndex）
 *
 * registryIndex 由卡片所属服务（config.fdid）的 configSnapshot.registries 按名字解析
 * （modbus.fds）。名字命中多只寄存器（重名）→ 取**第一只**并记一条 [dup]；一只都命中不了 → 记 [err]
 * 并**跳过该卡**（不写坏数据，留给人工在编辑器里重选）。
 *
 * 幂等：config.fields 已是数字数组 / config.field 已是数字 → 跳过。
 * 干跑：DRY_RUN=1 只打印、不落库。
 *
 * 用法：
 *   mongosh "<MONGO_URI>" --file migrate-dashboard-fields-registry.js
 *   DRY_RUN=1 mongosh "<MONGO_URI>" --file migrate-dashboard-fields-registry.js
 */
const DRY = !!process.env.DRY_RUN;
const matrix = db.getSiblingDB('matrix');
const modbus = db.getSiblingDB('modbus');

// fdid → { name → [index...] }
const byFdid = {};
modbus.fds.find({ 'configSnapshot.registries': { $exists: true } }).forEach((fd) => {
  const map = {};
  for (const r of fd.configSnapshot.registries || []) {
    if (r && r.index != null) {
      const name = r.name == null ? '' : r.name;
      (map[name] = map[name] || []).push(r.index);
    }
  }
  byFdid[String(fd._id)] = map;
});

function resolveIndex(fdid, name) {
  const map = byFdid[fdid];
  if (!map) return { idx: null, why: 'no-service' };
  const list = map[name == null ? '' : name];
  if (!list || list.length === 0) return { idx: null, why: 'no-name' };
  return { idx: list[0], why: list.length > 1 ? 'dup' : 'ok' };
}

let layouts = 0, widgetChanged = 0, errCards = 0, dupCards = 0;
matrix['web-dashboard-layouts'].find({}).forEach((doc) => {
  layouts++;
  let docChanged = false;
  const widgets = (doc.widgets || []).map((w) => {
    const cfg = w.config;
    if (!cfg) return w;
    const fdid = cfg.fdid;

    if (w.type === 'fd' && Array.isArray(cfg.fields) && cfg.fields.some((f) => typeof f === 'string')) {
      const out = [];
      let err = false;
      for (const name of cfg.fields) {
        if (typeof name === 'number') { out.push(name); continue; }
        const { idx, why } = resolveIndex(fdid, name);
        if (idx == null) { print(`[err] layout ${doc._id} widget ${w._id} (fd) 字段名解析不到: ${JSON.stringify(name)} (${why})`); err = true; break; }
        if (why === 'dup') print(`[dup] layout ${doc._id} widget ${w._id} 字段名 ${JSON.stringify(name)} 命中多只寄存器，取 #${idx}`);
        out.push(idx);
      }
      if (err) { errCards++; return w; }
      if (dupCards === 0) {} // noop
      widgetChanged++; docChanged = true;
      return Object.assign({}, w, { config: Object.assign({}, cfg, { fields: out }) });
    }

    if (w.type === 'line' && cfg.source === 'fdField' && typeof cfg.field === 'string') {
      const { idx, why } = resolveIndex(fdid, cfg.field);
      if (idx == null) { print(`[err] layout ${doc._id} widget ${w._id} (line) 字段名解析不到: ${JSON.stringify(cfg.field)} (${why})`); errCards++; return w; }
      if (why === 'dup') { dupCards++; print(`[dup] layout ${doc._id} widget ${w._id} 曲线字段名 ${JSON.stringify(cfg.field)} 命中多只寄存器，取 #${idx}`); }
      widgetChanged++; docChanged = true;
      return Object.assign({}, w, { config: Object.assign({}, cfg, { field: idx }) });
    }

    return w;
  });

  if (docChanged && !DRY) {
    matrix['web-dashboard-layouts'].updateOne({ _id: doc._id }, { $set: { widgets: widgets } });
  }
});

print(`[dashboard] 布局 ${layouts} 份，改卡 ${widgetChanged} 张，解析失败 ${errCards} 张，重名取首 ${dupCards} 处${DRY ? '（干跑，未落库）' : ''}`);
