/*
 * 数据清洗：把 modbus 库里的「点表自定义命令 ModbusCommand」从旧的内联形状改成寄存器中心形状。
 *
 *   旧（读）: { name, fc:'03'|'04', index, start, quantity, dataType, byteOrder, fields:[{name,scale,unit}] }
 *   旧（位读）: { …, bitNames:[{offset,name}] }
 *   旧（写）: fc 05 coilState / 06 registerValue / 0F coils:[{offset,on}] / 10 registers:[{address,dataType,byteOrder,value}]
 *
 *   新（读）: { name, fc, index, start, quantity }                 —— 去内联字段，寄存器按 start+quantity 地址区间推导
 *   新（写）: { name, fc, index, start, writeItems:[{registryIndex, value}] }
 *              —— 05/0F value 布尔、06/10 value 数值；地址/类型/字节序由被引用寄存器推
 *
 * 覆盖两处：
 *   1) db.configs[].commands
 *   2) db.fds[].configSnapshot.commands（服务快照里内嵌的整份点表，也带旧命令）
 *
 * 写命令的 registryIndex 由**同文档的 registries**（area+address 命中）解析出来；
 * 解析不出说明该写目标不在寄存器表里 —— 记一条 [err] 并**原样跳过该文档**（不写坏数据）。
 *
 * 幂等：已迁移的命令（读只剩 quantity、写已有 writeItems 且无旧键）跳过。
 * 干跑：DRY_RUN=1 只打印、不落库。
 *
 * 用法：
 *   mongosh "<MONGO_URI>" --file migrate-command-writeitems.js
 *   DRY_RUN=1 mongosh "<MONGO_URI>" --file migrate-command-writeitems.js
 */

const DRY = !!process.env.DRY_RUN;
const READ_FCS = new Set(['01', '02', '03', '04']);
const db = db.getSiblingDB('modbus');

function resolveIndex(registries, area, address) {
  if (!Array.isArray(registries)) return null;
  const r = registries.find((x) => x && x.area === area && x.address === address);
  return r && r.index != null ? r.index : null;
}

function oldReadKeys(c) {
  return (
    'fields' in c || 'bitNames' in c || 'dataType' in c || 'byteOrder' in c ||
    'fieldNames' in c || 'scale' in c || 'unit' in c
  );
}
function oldWriteKeys(c) {
  return 'coilState' in c || 'registerValue' in c || 'coils' in c || 'registers' in c;
}

/** 一条命令 → 新形状；未变则返回原对象引用（便于 changed 判定）。解析失败记 errs 并原样返回。 */
function transformCommand(c, registries, errs) {
  const fc = (c.fc || '').toUpperCase();
  if (READ_FCS.has(fc)) {
    if (!oldReadKeys(c)) return c;
    const n = Object.assign({}, c);
    delete n.fields;
    delete n.bitNames;
    delete n.dataType;
    delete n.byteOrder;
    delete n.fieldNames;
    delete n.scale;
    delete n.unit;
    return n;
  }

  // 写：已有 writeItems 且无旧键 → 视为已迁移
  if ('writeItems' in c && !oldWriteKeys(c)) return c;

  const w = [];
  const start = c.start || 0;
  if (fc === '05') {
    const idx = resolveIndex(registries, 'coil', start);
    if (idx == null) { errs.push(`${c.name}#${c.index} fc05 coil@${start}`); return c; }
    w.push({ registryIndex: idx, value: c.coilState === 'on' });
  } else if (fc === '06') {
    const idx = resolveIndex(registries, 'holding', start);
    if (idx == null) { errs.push(`${c.name}#${c.index} fc06 holding@${start}`); return c; }
    w.push({ registryIndex: idx, value: c.registerValue });
  } else if (fc === '0F') {
    for (const co of (c.coils || [])) {
      const addr = start + (co.offset || 0);
      const idx = resolveIndex(registries, 'coil', addr);
      if (idx == null) { errs.push(`${c.name}#${c.index} fc0F coil@${addr}`); return c; }
      w.push({ registryIndex: idx, value: co.on === true });
    }
    if (w.length === 0) { errs.push(`${c.name}#${c.index} fc0F 无 coils`); return c; }
  } else if (fc === '10') {
    for (const r of (c.registers || [])) {
      const idx = resolveIndex(registries, 'holding', r.address);
      if (idx == null) { errs.push(`${c.name}#${c.index} fc10 holding@${r.address}`); return c; }
      w.push({ registryIndex: idx, value: r.value });
    }
    if (w.length === 0) { errs.push(`${c.name}#${c.index} fc10 无 registers`); return c; }
  } else {
    errs.push(`${c.name}#${c.index} 未知 fc=${fc}`);
    return c;
  }

  const n = Object.assign({}, c);
  delete n.coilState;
  delete n.registerValue;
  delete n.coils;
  delete n.registers;
  delete n.dataType;
  delete n.byteOrder;
  n.writeItems = w;
  return n;
}

/** 一个含 commands+registries 的对象（config 或 configSnapshot）→ {changed, next, errs} */
function transformOwner(owner) {
  const errs = [];
  if (!owner || !Array.isArray(owner.commands)) return { changed: false, next: null, errs };
  let changed = false;
  const next = owner.commands.map((c) => {
    const t = transformCommand(c, owner.registries, errs);
    if (t !== c) changed = true;
    return t;
  });
  return { changed, next, errs };
}

function run(label, cursor, pickOwner, buildSet) {
  let total = 0, changed = 0, errDocs = 0;
  cursor.forEach((doc) => {
    total++;
    const owner = pickOwner(doc);
    const { changed: ch, next, errs } = transformOwner(owner);
    if (errs.length) {
      errDocs++;
      print(`[err] ${label} ${doc._id}: ${errs.join(' , ')}`);
    }
    if (ch) {
      changed++;
      if (!DRY) {
        db.getCollection(label).updateOne({ _id: doc._id }, { $set: buildSet(next) });
      }
    }
  });
  print(`[${label}] 共 ${total} 条，需改 ${changed} 条，解析失败 ${errDocs} 条${DRY ? '（干跑，未落库）' : ''}`);
  return { total, changed, errDocs };
}

const c = run(
  'configs',
  db.configs.find({}),
  (d) => d,
  (next) => ({ commands: next }),
);

const f = run(
  'fds',
  db.fds.find({ 'configSnapshot.commands': { $exists: true } }),
  (d) => d.configSnapshot,
  (next) => ({ 'configSnapshot.commands': next }),
);

print(`[summary] configs 需改 ${c.changed}/${c.total}（失败 ${c.errDocs}）；fds 快照需改 ${f.changed}/${f.total}（失败 ${f.errDocs}）${DRY ? ' · DRY_RUN' : ''}`);
