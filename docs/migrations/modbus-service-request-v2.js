// ============================================================================
// service-matrix ModbusService 定义迁移脚本（v1 → v2）
//
// 用途：把 modbus.services 集合里仍是 v1 口径的服务定义升到 v2。
//
//   v1：functions[].request  = 一整帧 Modbus RTU 的 16 进制字符串（含 CRC16）
//       functions[].response = 裸数组
//   v2：functions[].request  = 结构化定义 { slaveId, fc, start, quantity? | fields[] }
//       functions[].response = { fields: [...] }（写方法整段没有该键）
//       version              = 2
//
// 反解规则（与 scripts/migrate-service-request-v2.sh 同一套口径，两边不要各改各的）：
//   01/02/03/04 读 → request.quantity = 帧里的字面数量（01/02 是位数、03/04 是寄存器数）
//   05 写单线圈    → 一个 bit 字段，帧值 FF00/0000 → true/false
//   06 写单寄存器  → 一个字段，帧值 > 32767 记 uint16、否则 int16（值必须落在格式的范围内）
//   0F 写多线圈    → 逐位还原：一位一个 bit 字段，offset 0..n-1
//   10 写多寄存器  → 逐寄存器还原：一个寄存器一个 uint16 字段，值取寄存器原值
//   （05/06 的字段不带 offset；0F/10 按 index 升序、offset 首尾相接 —— 结构化组帧对这两件事有硬要求）
//
// 说明：
//  - **帧逐字节不变**是本次迁移唯一的验收口径：写库前脚本会把反解出来的 request 重新组帧，
//    与库里原来那串 hex 的**帧体**（CRC 之前的部分）逐字节比对，不一致就拒绝迁移这条方法
//    （连同它所在的那条服务一起跳过，报错里带原帧）。CRC 也重新算一遍：不一致只告警
//    （原帧 CRC 本来就是错的 —— 那样发出去设备不会认，迁移后由后端重算，等于顺手修好）。
//  - 0F/10 的还原是「保帧不保语义」：原来的写法若是一条 int32 占两个寄存器，
//    这里会拆成两个 uint16 字段 —— 发出去的帧一模一样，但界面上会看到两个字段。
//  - 写方法上的 interval / polling 会被删掉（v2 校验层不允许写方法配轮询）。
//  - 服务内各方法的 slaveId 不一致、读方法 quantity 与应答字节数对不上、quantity 超出
//    Modbus 上限、start/slaveId 越界：都只**告警**不阻断 —— 这些定义在 v1 下本来就不自洽，
//    迁移后调用照旧，但下次在界面上保存时会被 v2 校验层拦下（那正是想要的效果）。
//  - 幂等：只处理「version ≠ 2 或还有方法的 request 是字符串」的文档，可安全重跑。
//  - 写库只动 version 与 functions 两个字段（$set），其余字段（device / configId /
//    creator / updater / name）原样不碰。
//
// 执行方式（默认**只读演练**，一个字都不写）：
//   mongosh <connection-string> docs/migrations/modbus-service-request-v2.js
// 看过演练输出确认无误后再落库（加 APPLY=1）：
//   APPLY=1 mongosh <connection-string> docs/migrations/modbus-service-request-v2.js
//
// 可选环境变量：
//   APPLY=1      真正写库（缺省只演练）
//   QUIET=1      演练时不打印每条服务迁移后的完整 functions JSON
//   ONLY=a,b     只处理指定的服务 _id（逗号分隔，排障用）
//
// 落库前建议备份（本机没有 mongodump，用 EJSON 导出同样可还原）：
//   mongosh "<connection-string>" --quiet \
//     --eval 'print(EJSON.stringify(db.getSiblingDB("modbus").services.find().toArray()))' \
//     > modbus-services-backup.json
//   还原：EJSON.parse 读回该文件后逐条 replaceOne({_id}, doc)
//
// ⚠️ **落库前必须确认 v2 后端已经部署**（本脚本是「库先行」，与 HTTP 那条路相反）：
//   库里的文档是**滚动更新的服务端**在读，旧后端把 request 当字符串解，遇到 object 直接炸：
//     Failed to decode 'ModbusServiceFunction'. Decoding 'request' errored with:
//     readString can only be called when CurrentBSONType is STRING, not when CurrentBSONType is OBJECT
//   （`ModbusServiceFunction` 就是 v1 的类名，v2 已改名 `ModbusFunction` —— 报错里看到它即说明后端是旧的。）
//   症状：那条服务的轮询任务每个周期都 FAILED（见 modbus.polls.lastError / lastStatus）、
//   服务详情页与空间清单一起报错（按空间列服务是整批解码，一条坏文档会带走整个查询）。
//   中招了不用慌：把备份读回去就恢复原状，等新后端上线再重跑本脚本（幂等，随时可重跑）。
//   保守做法：先用 ONLY=<一条服务 id> 只迁一条，盯一个轮询周期（polls.lastStatus 应为 OK），
//   确认后端确实认得新口径，再放开整库。
// ============================================================================

const modbus = db.getSiblingDB("modbus");
const services = modbus.getCollection("services");

// ---------------------------- 运行开关 ----------------------------

const env = (typeof process !== "undefined" && process.env) || {};
const APPLY = String(env.APPLY) === "1";
const QUIET = String(env.QUIET) === "1";
const ONLY = String(env.ONLY || "").split(",").map(function (s) { return s.trim(); }).filter(Boolean);

// ------------------- 常量：与后端两处源码对齐 -------------------
// 见 cc.openxiot.matrix.api.modbus.service.validator.ModbusServiceValidator
// 与 cc.openxiot.matrix.typedef.modbus.frame.ModbusFrameCodec
const READ_FCS = ["01", "02", "03", "04"];
const WRITE_FCS = ["05", "06", "0F", "10"];
const MAX_READ_BITS = 2000;
const MAX_READ_REGISTERS = 125;
const CRC_SIZE = 2;
/** 05/06 只有一个字段时的字段名（与 migrate-service-request-v2.sh 一致；多个字段时后缀序号）。 */
const DEFAULT_FIELD_NAME = "写入值";

// ---------------------------- 小工具 ----------------------------

function toHex2(value) {
    const hex = (value & 0xFF).toString(16).toUpperCase();
    return hex.length === 1 ? "0" + hex : hex;
}

function toHex(bytes) {
    return bytes.map(toHex2).join("");
}

/** 帧字符串 → 字节数组；空白与大小写不敏感。 */
function parseFrame(hex) {
    const clean = String(hex).replace(/\s+/g, "").toUpperCase();
    if (clean.length === 0 || clean.length % 2 !== 0 || !/^[0-9A-F]+$/.test(clean)) {
        throw new Error("不是合法的 16 进制帧：" + hex);
    }
    const bytes = [];
    for (let i = 0; i < clean.length; i += 2) {
        bytes.push(parseInt(clean.substring(i, i + 2), 16));
    }
    return { bytes: bytes, hex: clean };
}

/** Modbus CRC16（多项式 0xA001，初值 0xFFFF）——只用于比对，不落库。 */
function crc16(bytes) {
    let crc = 0xFFFF;
    for (let i = 0; i < bytes.length; i++) {
        crc ^= bytes[i] & 0xFF;
        for (let bit = 0; bit < 8; bit++) {
            crc = (crc & 1) ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1);
        }
    }
    return crc & 0xFFFF;
}

/** 反解出来的 request 重新组帧（帧体 + CRC），用来和库里的原帧对照。 */
function frameOf(request, isRead) {
    const fc = String(request.fc).trim().toUpperCase();
    const body = [];
    const push16 = function (value) {
        body.push((value >> 8) & 0xFF, value & 0xFF);
    };

    body.push(request.slaveId & 0xFF, parseInt(fc, 16));
    push16(request.start);

    if (isRead) {
        push16(request.quantity);
    } else if (fc === "05") {
        body.push(request.fields[0].value === true ? 0xFF : 0x00, 0x00);
    } else if (fc === "06") {
        push16(request.fields[0].value & 0xFFFF);
    } else if (fc === "0F") {
        const quantity = request.fields.length;
        const byteCount = Math.ceil(quantity / 8);
        const bits = [];
        for (let i = 0; i < byteCount; i++) {
            bits.push(0);
        }
        request.fields.forEach(function (field, i) {
            if (field.value === true) {
                bits[i >> 3] |= 1 << (i % 8);
            }
        });
        push16(quantity);
        body.push(byteCount);
        Array.prototype.push.apply(body, bits);
    } else if (fc === "10") {
        push16(request.fields.length);
        body.push(request.fields.length * 2);
        request.fields.forEach(function (field) {
            push16(field.value & 0xFFFF);
        });
    } else {
        throw new Error("不认识的功能码：" + fc);
    }

    const crc = crc16(body);
    // CRC 低字节在前，与 ModbusFrameCodec#withCrc 一致
    return { body: body, frame: body.concat([crc & 0xFF, (crc >> 8) & 0xFF]) };
}

function fieldName(index, total) {
    return total === 1 ? DEFAULT_FIELD_NAME : DEFAULT_FIELD_NAME + " " + index;
}

// ------------------- 单条方法的反解（v1 帧 → v2 request） -------------------

/**
 * 反解一条 v1 方法。
 *
 * @returns {{request: Object, isRead: boolean, notes: string[], warnings: string[]}}
 * @throws 定义不可反解时抛错，错误里带原帧
 */
function toRequest(fn, where) {
    const frame = parseFrame(fn.request);
    const b = frame.bytes;
    const notes = [];
    const warnings = [];

    if (b.length < 8) {
        // 最短的 Modbus 请求帧就是 8 字节（从站 + 功能码 + 地址 + 值 + CRC）
        throw new Error(where + " 的帧短于 8 字节：" + frame.hex);
    }

    const slaveId = b[0];
    const fc = toHex2(b[1]);
    const start = (b[2] << 8) | b[3];

    if (slaveId < 1 || slaveId > 247) {
        warnings.push(where + " 的从站地址 " + slaveId + " 不在 1..247 内（帧里就是这样）");
    }
    if (READ_FCS.indexOf(fc) < 0 && WRITE_FCS.indexOf(fc) < 0) {
        throw new Error(where + " 的功能码 " + fc + " 不是 01/02/03/04/05/06/0F/10，无法反解：" + frame.hex);
    }

    // 读方法：帧定长 8 字节，数量是帧里的字面值
    if (READ_FCS.indexOf(fc) >= 0) {
        if (b.length !== 8) {
            throw new Error(where + " 是读帧但长度不是 8 字节：" + frame.hex);
        }
        const quantity = (b[4] << 8) | b[5];
        const max = (fc === "01" || fc === "02") ? MAX_READ_BITS : MAX_READ_REGISTERS;
        if (quantity < 1 || quantity > max) {
            warnings.push(where + " 的数量 " + quantity + " 超出 Modbus 上限 1.." + max);
        }
        return {
            request: { slaveId: slaveId, fc: fc, start: start, quantity: quantity },
            isRead: true,
            notes: notes,
            warnings: warnings
        };
    }

    // 写方法：数据区在帧里，逐字节还原成字段。
    // 路数分两种：05/06 是 8 字节定长（第 4..5 字节就是那个单值，帧里没有数量与字节数），
    // 0F/10 才是「数量 + 字节数 + 数据区」。
    if (fc === "05" || fc === "06") {
        if (b.length !== 8) {
            throw new Error(where + " 是 " + fc + " 帧但长度不是 8 字节：" + frame.hex);
        }
        const raw = (b[4] << 8) | b[5];
        if (fc === "05") {
            if (raw !== 0xFF00 && raw !== 0x0000) {
                throw new Error(where + " 是 05 帧但线圈值不是 FF00/0000（" + toHex(b.slice(4, 6))
                    + "）：" + frame.hex);
            }
            return {
                request: {
                    slaveId: slaveId,
                    fc: fc,
                    start: start,
                    fields: [{ index: 1, field: fieldName(1, 1), format: "bit", value: raw === 0xFF00 }]
                },
                isRead: false,
                notes: notes,
                warnings: warnings
            };
        }
        // 与迁移脚本同口径：值 > 32767 记 uint16（int16 装不下，校验层会按格式查范围）
        return {
            request: {
                slaveId: slaveId,
                fc: fc,
                start: start,
                fields: [{
                    index: 1,
                    field: fieldName(1, 1),
                    format: raw > 32767 ? "uint16" : "int16",
                    value: raw
                }]
            },
            isRead: false,
            notes: notes,
            warnings: warnings
        };
    }

    const quantity = (b[4] << 8) | b[5];
    const byteCount = b[6];
    if (b.length !== 9 + byteCount) {
        throw new Error(where + " 的帧长度与字节数 " + byteCount + " 对不上：" + frame.hex);
    }
    const data = b.slice(7, 7 + byteCount);

    if (fc === "0F") {
        if (quantity < 1) {
            throw new Error(where + " 是 0F 帧但线圈数为 0：" + frame.hex);
        }
        if (byteCount !== Math.ceil(quantity / 8)) {
            throw new Error(where + " 是 0F 帧但字节数 " + byteCount + " 与线圈数 " + quantity
                + " 对不上：" + frame.hex);
        }
        const fields = [];
        for (let i = 0; i < quantity; i++) {
            fields.push({
                index: i + 1,
                field: fieldName(i + 1, quantity),
                offset: i,
                format: "bit",
                value: ((data[i >> 3] >> (i % 8)) & 1) === 1
            });
        }
        notes.push(where + " 由 0F 帧逐位还原：" + quantity + " 个 bit 字段，帧不变");
        return { request: { slaveId: slaveId, fc: fc, start: start, fields: fields },
            isRead: false, notes: notes, warnings: warnings };
    }

    // fc 10：数量是寄存器数，数据区按寄存器切
    if (byteCount !== quantity * 2 || quantity < 1) {
        throw new Error(where + " 是 10 帧但字节数 " + byteCount + " 与寄存器数 " + quantity
            + " 对不上：" + frame.hex);
    }
    const fields = [];
    for (let i = 0; i < quantity; i++) {
        fields.push({
            index: i + 1,
            field: fieldName(i + 1, quantity),
            offset: i,
            format: "uint16",
            value: (data[i * 2] << 8) | data[i * 2 + 1]
        });
    }
    notes.push(where + " 由 10 帧逐寄存器还原：" + quantity + " 个 uint16 字段，帧不变"
        + "（原来若是一条 int32/float32 占两个寄存器，界面上会看到两行）");
    return { request: { slaveId: slaveId, fc: fc, start: start, fields: fields },
        isRead: false, notes: notes, warnings: warnings };
}

/** v1 的裸数组 → v2 的 { fields: [...] }；写方法返回 null（整段没有这个键）。 */
function toResponse(fn, isRead, where, warnings) {
    if (!isRead) {
        return null;
    }
    const raw = fn.response;
    let fields = null;
    if (Array.isArray(raw)) {
        fields = raw;
    } else if (raw && typeof raw === "object" && Array.isArray(raw.fields)) {
        fields = raw.fields;   // 已经包过一层：原样带走
    }
    if (fields === null || fields.length === 0) {
        warnings.push(where + " 是读方法却没有应答字段（v2 校验层要求读方法必须有 response.fields）");
        return null;
    }
    return { fields: fields };
}

/** 读方法的请求数量 ↔ 应答字节数对账（与校验层同一条算式）。 */
function checkReconcile(request, response, where, warnings) {
    if (!response) {
        return;
    }
    let declared = 0;
    response.fields.forEach(function (field) {
        declared += (field && field.bytes) ? field.bytes : 0;
    });
    const expected = (request.fc === "01" || request.fc === "02")
        ? Math.ceil(request.quantity / 8)
        : request.quantity * 2;
    if (declared !== expected) {
        warnings.push(where + " 应答声明 " + declared + " 字节，但 fc " + request.fc + " 数量 "
            + request.quantity + " 读回 " + expected + " 字节（迁移后能调用，但下次保存会被校验层拦下）");
    }
}

/** 一条方法是不是已经是 v2 口径。 */
function isV2Function(fn) {
    return !!fn && !!fn.request && typeof fn.request === "object";
}

// ------------------- 单条服务的迁移 -------------------

/**
 * 迁移一条服务文档。
 *
 * @returns {{functions: Array, notes: string[], warnings: string[], problems: string[], migrated: number}}
 */
function migrateService(doc) {
    const problems = [];
    const warnings = [];
    const notes = [];
    const functions = [];
    const slaveIds = {};
    let migrated = 0;

    const list = doc.functions;
    if (!Array.isArray(list) || list.length === 0) {
        problems.push("functions 不是非空数组");
        return { functions: functions, notes: notes, warnings: warnings, problems: problems, migrated: 0 };
    }

    list.forEach(function (fn, i) {
        const where = "_id=" + doc._id + " functions[" + i + "]"
            + (fn && fn.name ? "（" + fn.name + "）" : "");
        if (!fn) {
            problems.push(where + " 是 null");
            return;
        }
        let built;
        if (isV2Function(fn)) {
            // 已经是 v2 的方法原样保留（服务整体是 v1，但个别方法被手工改过）
            built = { request: fn.request, isRead: READ_FCS.indexOf(String(fn.request.fc || "").trim().toUpperCase()) >= 0,
                notes: [], warnings: [] };
        } else {
            try {
                built = toRequest(fn, where);
            } catch (e) {
                problems.push(String(e.message || e));
                return;
            }
            migrated++;
        }

        const response = toResponse(fn, built.isRead, where, built.warnings);
        checkReconcile(built.request, response, where, built.warnings);

        // 帧逐字节自检：反解出来的 request 重新组帧，必须与库里的原帧一致
        if (typeof fn.request === "string") {
            const original = parseFrame(fn.request);
            const rebuilt = frameOf(built.request, built.isRead);
            const originalBody = original.bytes.slice(0, original.bytes.length - CRC_SIZE);
            if (toHex(originalBody) !== toHex(rebuilt.body)) {
                problems.push(where + " 还原后组帧不一致：原帧体 " + toHex(originalBody)
                    + "，还原后 " + toHex(rebuilt.body) + "（拒绝迁移）");
                return;
            }
            if (toHex(original.bytes) !== toHex(rebuilt.frame)) {
                built.warnings.push(where + " 原帧 CRC 与算式不符（原帧 " + original.hex
                    + "，应为 " + toHex(rebuilt.frame) + "），迁移后由后端重算");
            }
        }

        // index / name 等原有字段原样保留，只换 request / response
        const out = Object.assign({}, fn);
        out.request = built.request;
        if (response === null) {
            delete out.response;
        } else {
            out.response = response;
        }
        if (!built.isRead) {
            // v2 校验层不允许写方法配轮询：周期调用等于定时往寄存器里写值
            delete out.interval;
            delete out.polling;
        }

        Array.prototype.push.apply(notes, built.notes);
        Array.prototype.push.apply(warnings, built.warnings);
        if (built.request.slaveId !== undefined) {
            slaveIds[built.request.slaveId] = true;
        }
        functions.push(out);
    });

    if (Object.keys(slaveIds).length > 1) {
        warnings.push("服务内各方法的从站地址不一致（" + Object.keys(slaveIds).join(" / ")
            + "），v2 校验层要求同一服务共用一个从站");
    }

    return { functions: functions, notes: notes, warnings: warnings, problems: problems, migrated: migrated };
}

/** v1 口径的判定：version ≠ 2 或还有方法的 request 是字符串。 */
const V1_FILTER = {
    $or: [
        { version: { $ne: 2 } },
        { "functions.request": { $type: "string" } }
    ]
};

// ------------------------------- 主流程 -------------------------------

print("=== ModbusService v1 → v2 迁移 " + (APPLY ? "（落库模式）" : "（只读演练，不写库）") + " ===");
if (APPLY) {
    print("⚠ 落库前请确认 v2 后端**已经部署**：旧后端读到结构化 request 会解码失败，"
        + "那条服务的轮询与服务清单会一起报错（详见脚本头部）。");
}
if (ONLY.length > 0) {
    print("只处理指定的服务：" + ONLY.join(", "));
}

const filter = ONLY.length > 0
    ? { $and: [V1_FILTER, { _id: { $in: ONLY.map(function (id) { return ObjectId(id); }) } }] }
    : V1_FILTER;

const total = services.countDocuments({});
const pending = services.find(filter).toArray();
print("迁移前：services 共 " + total + " 条，其中待迁移（v1 口径）" + pending.length + " 条");
print("");

let written = 0;
let refused = 0;
let failed = 0;

pending.forEach(function (doc) {
    const result = migrateService(doc);
    print("--- " + doc._id + "  " + (doc.name === undefined ? "" : doc.name)
        + "  version=" + doc.version + "  方法 " + (doc.functions ? doc.functions.length : 0) + " 条");

    if (result.problems.length > 0) {
        refused++;
        print("  ✗ 拒绝迁移（这条服务保持原样，需要人工处理）：");
        result.problems.forEach(function (p) { print("      " + p); });
        print("");
        return;
    }

    if (!QUIET) {
        print("  迁移后 functions：" + JSON.stringify(result.functions, null, 2)
            .split("\n").join("\n  "));
    }
    result.notes.forEach(function (n) { print("  · " + n); });
    result.warnings.forEach(function (w) { print("  ! " + w); });

    if (APPLY) {
        const r = services.updateOne(
            { _id: doc._id, $or: V1_FILTER.$or },
            { $set: { version: 2, functions: result.functions } }
        );
        if (r.modifiedCount === 1) {
            written++;
            print("  ✓ 已落库（方法 " + result.migrated + " 条反解自 v1 帧）");
        } else {
            failed++;
            print("  ✗ 未落库：matched=" + r.matchedCount + " modified=" + r.modifiedCount
                + "（可能已被别处改过）");
        }
    } else {
        print("  ✓ 演练通过（方法 " + result.migrated + " 条可反解自 v1 帧）");
    }
    print("");
});

// ------------------------------- 收尾核对 -------------------------------

if (APPLY) {
    print("已落库文档数 = " + written);
    if (failed > 0) {
        print("落库失败 " + failed + " 条（见上）");
    }
}
if (refused > 0) {
    print("拒绝迁移 " + refused + " 条（见上，需人工处理）");
}

const leftover = services.countDocuments(V1_FILTER);
print("迁移后：仍为 v1 口径的文档数" + (APPLY ? "（应为 0）" : "（演练未写库，与「迁移前」一致）")
    + " = " + leftover);

const sample = services.findOne({ version: 2 });
if (sample) {
    print("");
    print("样例（version=2 的第一条）：_id=" + sample._id + " name=" + sample.name
        + " 方法数=" + (sample.functions ? sample.functions.length : 0));
    print("  functions[0].request = " + JSON.stringify(sample.functions[0].request));
    print("  functions[0].response = " + JSON.stringify(sample.functions[0].response));
} else if (!APPLY) {
    print("");
    print("（演练未写库，库中尚无 version=2 的样例）");
}
