/*
 * 一次性迁移：卡片展示配置的文档主键从 ObjectId 换成 did / fdid。
 *
 *   旧：matrix["device-card-configs"] { _id: ObjectId(...), spaceId, did, services, creator, updater }
 *       matrix["fd-card-configs"]     { _id: ObjectId(...), spaceId, fdid, registers, creator, updater }
 *
 *   新：_id 就是 did / fdid（字符串），**spaceId 降级为派生字段**（每次保存按设备现算覆盖，
 *       只服务「按项目列配置」）。其余字段一字不改。
 *
 * 为什么要搬 _id 而不是只把字段留着：后端实体已经把 @BsonId 打在 did / fdid 上，POJO 解码时
 * `_id` 必须是字符串 —— 旧文档的 ObjectId `_id` 会让读接口直接解码失败（不是"读不到"，是报错）。
 * 所以**迁库与新后端部署必须紧挨着**，中间那段窗口里卡片配置接口会失败：
 * 前端 `CardConfigStore.load` 失败是静默的（按缺省 2×3 / 前 4 只摆），用户只会看到默认卡片，
 * 不会看到错误页。建议顺序：备份 → 停写（或直接接受这个窗口）→ 部署新后端 → 跑本脚本。
 *
 * 顺序很要紧：**先删旧的 (spaceId, did) / (spaceId, fdid) 唯一索引，再搬文档** ——
 * 新文档与旧文档的 (spaceId, did) 完全相同，唯一索引还在的话插入必撞 E11000。
 *
 * 幂等：`_id` 已经是字符串的文档跳过；索引删除 / 创建都吞「不存在 / 已存在」。
 * 干跑：DRY_RUN=1 只打印、不落库。
 *
 * 用法：
 *   mongosh "<MONGO_URI>" --quiet --file migrate-card-config-id.js
 *   DRY_RUN=1 mongosh "<MONGO_URI>" --quiet --file migrate-card-config-id.js
 */

const DRY = !!process.env.DRY_RUN;
const db = db.getSiblingDB('matrix');

const COLLECTIONS = [
  { name: 'device-card-configs', keyField: 'did', legacyIndex: 'spaceId_1_did_1' },
  { name: 'fd-card-configs', keyField: 'fdid', legacyIndex: 'spaceId_1_fdid_1' },
];

let totalMoved = 0;
let totalSkipped = 0;
let totalErrors = 0;

for (const target of COLLECTIONS) {
  const coll = db.getCollection(target.name);
  const existing = db.getCollectionNames();
  if (!existing.includes(target.name)) {
    print(`[跳过] 集合 ${target.name} 不存在（还没有任何配置）：无需迁移。`);
    continue;
  }

  print(`==> ${target.name}：主键 -> ${target.keyField}`);

  // 1) 先删上一版的复合唯一索引（不删的话第 3 步插入必撞 E11000）
  const hasLegacyIndex = coll.getIndexes().some((i) => i.name === target.legacyIndex);
  if (hasLegacyIndex) {
    if (DRY) {
      print(`[dry] 删除索引 ${target.legacyIndex}`);
    } else {
      coll.dropIndex(target.legacyIndex);
      print(`[完成] 删除旧唯一索引 ${target.legacyIndex}`);
    }
  }

  // 2) 搬文档：_id(ObjId) -> _id = did/fdid
  const docs = coll.find({}).toArray();
  let moved = 0;
  let skipped = 0;
  let errors = 0;

  for (const doc of docs) {
    const key = doc[target.keyField];
    if (key == null || key === '') {
      print(`[err] ${target.name} _id=${doc._id} 缺 ${target.keyField}，跳过（人工处理）`);
      errors++;
      continue;
    }
    if (typeof doc._id === 'string') {
      skipped++;
      continue;
    }
    const clash = coll.findOne({ _id: key });
    if (clash != null) {
      print(`[err] ${target.name} 已存在 _id=${key}，旧文档 _id=${doc._id} 未搬（人工确认后删除旧文档）`);
      errors++;
      continue;
    }
    if (DRY) {
      print(`[dry] ${target.name} _id=${doc._id} -> ${key}`);
    } else {
      const next = Object.assign({}, doc, { _id: key });
      coll.insertOne(next);
      coll.deleteOne({ _id: doc._id });
    }
    moved++;
  }

  if (!DRY) {
    // 3) 补 spaceId 普通索引（后端启动时也会补齐，这里让库本身先自洽）
    coll.createIndex({ spaceId: 1 });
  }

  print(`[完成] ${target.name}：搬了 ${moved} 份，已是新主键 ${skipped} 份，异常 ${errors} 份`);
  totalMoved += moved;
  totalSkipped += skipped;
  totalErrors += errors;
}

print(
  `${DRY ? '[干跑结束]' : '[全部完成]'} 共搬 ${totalMoved} 份，跳过 ${totalSkipped} 份，异常 ${totalErrors} 份`
);
