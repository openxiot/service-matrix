// ============================================================================
// service-matrix 点表快照迁移脚本（configId → configSnapshot）
//
// 用途：把 modbus.services 里的旧引用 `configId`（点表主键）换成**创建时快照**
// `configSnapshot`（整份点表文档），与后端 ModbusService.configSnapshot 同步。
//
//   旧：{ ..., configId: "65f0c0ffee0123456789abcd", ... }
//   新：{ ..., configSnapshot: { _id: ObjectId(...), slave: {...}, registries: [...], ... }, ... }
//   （services 里的快照与 configs 集合里的点表**同形**：主键都是 `_id`。POJO codec 读嵌套文档
//     时同样把 `_id` 解回 ObjectId —— 见 ModbusServiceSnapshotBsonTest，脚本的前提就是它。）
//
// 语义：快照是**创建时**的点表，之后点表被改/被删都不影响已建服务。脚本只是把「现在这条点表」
// 原样拍进去 —— 对旧服务来说这也是它们此前一直依赖的那一份，行为不变。
//
// 说明：
//  - 幂等：已有 configSnapshot 的服务跳过（只顺手清掉残留的 configId），可安全重跑。
//  - **找不到点表**（configId 非法 / 点表已被删）：不动该文档，逐条报出来。这些服务在新后端下
//    没有可读的点表快照：寄存器缓存为空、编辑保存会被校验层拒（`configSnapshot is required`）。
//    需要人工重建：要么重新选一条点表保存一次，要么手工把点表文档补进 configSnapshot。
//  - 没有 configId 也没有快照的服务（历史遗留）：不写任何字段，只在汇总里计数。
//  - 写库只动 configSnapshot / configId 两个字段，其余字段（name / device / functions /
//    slaveId / creator / updater）原样不碰。
//
// 执行方式（默认**只读演练**，一个字都不写）：
//   mongosh <connection-string> docs/migrations/modbus-service-config-snapshot.js
// 看过演练输出确认无误后再落库（加 APPLY=1）：
//   APPLY=1 mongosh <connection-string> docs/migrations/modbus-service-config-snapshot.js
//
// 可选环境变量：
//   APPLY=1      真正写库（缺省只演练）
//   VERBOSE=1    演练时逐条打印将被写入的服务（缺省只打印前后样例与汇总）
//   ONLY=a,b     只处理指定的服务 _id（逗号分隔，排障用）
//
// 落库前建议备份服务集合（迁移只加快照、删 configId，备份主要为了「万一」能整段还原）：
//   mongosh "<connection-string>" --quiet \
//     --eval 'print(EJSON.stringify(db.getSiblingDB("modbus").services.find().toArray()))' \
//     > modbus-services-backup.json
//   还原：EJSON.parse 读回该文件后逐条 replaceOne({_id}, doc)
//
// ⚠️ **落库前必须确认新后端已经部署**（先部署、后迁库）：
//   新后端读服务时直接读内嵌的 configSnapshot，**不再回查 configs 集合**；旧后端只认 configId，
//   迁完后它会以为这些服务「没有点表」（寄存器缓存空、统计分组落空）。部署顺序正好相反时，
//   先跑演练确认输出，等部署完成再 APPLY=1。
//   同样地，部署完成到本脚本落库之间是一个**空窗**：这期间旧服务的 configSnapshot 还是空，
//   寄存器缓存读不到东西、编辑保存会被校验层拒。所以两条命令尽量连着跑（脚本很快）。
// ============================================================================

const modbus = db.getSiblingDB("modbus");
const services = modbus.getCollection("services");
const configs = modbus.getCollection("configs");

// ---------------------------- 运行开关 ----------------------------

const env = (typeof process !== "undefined" && process.env) || {};
const APPLY = String(env.APPLY) === "1";
const VERBOSE = String(env.VERBOSE) === "1";
const ONLY = String(env.ONLY || "").split(",").map(function (s) { return s.trim(); }).filter(Boolean);

// ---------------------------- 小工具 ----------------------------

function hex(id) {
    return id == null ? "(no id)" : id.toHexString();
}

/** 24 位十六进制才算合法的 ObjectId 字符串（与后端 toObjectId 同口径）。 */
function isObjectIdString(value) {
    return typeof value === "string" && /^[0-9a-fA-F]{24}$/.test(value);
}

// ---------------------------- 主流程 ----------------------------

let scanned = 0;     // 参与判断的服务数（ONLY 过滤后）
let already = 0;     // 已有快照：跳过
let migrated = 0;    // 将被 / 已被写入快照
let noConfig = 0;    // 既没快照也没 configId
let orphans = [];    // configId 指向的点表找不到（或 id 非法）

const sample = [];

services.find({}, { configId: 1, configSnapshot: 1, name: 1 }).forEach(function (svc) {
    const id = hex(svc._id);
    if (ONLY.length > 0 && ONLY.indexOf(id) < 0) {
        return;
    }
    scanned++;

    const snapshot = svc.configSnapshot;
    const configId = svc.configId;

    // 已经迁过：若有残留 configId，顺手清掉（apply 时）
    if (snapshot != null) {
        already++;
        if (configId != null && APPLY) {
            services.updateOne({ _id: svc._id }, { $unset: { configId: "" } });
        }
        return;
    }

    if (configId == null) {
        noConfig++;
        if (VERBOSE) {
            print("  [无点表] " + id + " " + (svc.name || ""));
        }
        return;
    }

    if (!isObjectIdString(configId)) {
        orphans.push({ service: id, name: svc.name || "", configId: String(configId), reason: "非法点表 id" });
        return;
    }

    const config = configs.findOne({ _id: new ObjectId(configId) });
    if (!config) {
        orphans.push({ service: id, name: svc.name || "", configId: configId, reason: "点表不存在" });
        return;
    }

    // 快照与点表文档同形（主键 `_id`），整段拷进去即可
    if (APPLY) {
        services.updateOne(
            { _id: svc._id },
            { $set: { configSnapshot: config }, $unset: { configId: "" } }
        );
    }
    migrated++;

    if (VERBOSE) {
        print("  [迁] " + id + " " + (svc.name || "") + "  ← 点表 " + configId);
    }
    if (sample.length < 3) {
        sample.push({
            service: id,
            name: svc.name || "",
            configId: configId,
            snapshot: {
                _id: hex(config._id),
                slave: config.slave || null,
                registries: (config.registries || []).length,
                commands: (config.commands || []).length,
            },
        });
    }
});

// ---------------------------- 汇总 ----------------------------

print("");
print("=== modbus.services：configId → configSnapshot ===");
print("  模式        : " + (APPLY ? "APPLY（已写库）" : "演练（未写库，加 APPLY=1 落库）"));
print("  扫描        : " + scanned);
print("  已迁移/跳过 : " + migrated + " / " + already);
print("  无点表      : " + noConfig + "（既无快照也无 configId，未改动）");
print("  找不到点表  : " + orphans.length + "（未改动，需人工处理）");

if (sample.length > 0) {
    print("");
    print("已有快照样例：");
    sample.forEach(function (s) {
        print("  service " + s.service + " (" + s.name + ") ← config " + s.configId
            + "  slave=" + JSON.stringify(s.snapshot.slave)
            + " registries=" + s.snapshot.registries + " commands=" + s.snapshot.commands);
    });
}

if (orphans.length > 0) {
    print("");
    print("⚠️ 以下服务找不到源点表（新后端下寄存器缓存为空、编辑保存会被拒）：");
    orphans.forEach(function (o) {
        print("  service " + o.service + " (" + o.name + ")  configId=" + o.configId + "  —— " + o.reason);
    });
    print("  处理办法：重新选一条点表保存一次，或手工把点表文档补进这些服务的 configSnapshot。");
}

if (!APPLY && migrated > 0) {
    print("");
    print("演练结束。确认无误后执行：APPLY=1 mongosh <connection-string> docs/migrations/modbus-service-config-snapshot.js");
}
