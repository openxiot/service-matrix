// ============================================================================
// service-matrix SpaceAccess 迁移脚本
// 用途：把 spaces 集合的历史访问控制数据统一为 accesses 数组（方案 B，字段重命名）。
//       旧结构（三种历史状态，取其一）：
//         1) { organizationId: "<org>" }                              —— 从未迁移
//         2) { owner: { id, type } }                                  —— 第一阶段（spaces-owner.js）
//         3) { owners: [ { id, type, role, name, remark }, ... ] }    —— 第二阶段（spaces-owners.js）
//       新结构：
//         { accesses: [ { id, type, role, name, remark }, ... ] }
//
// 规则：
//  - type = organization → { id, type:"organization", role:"admin" }（组织是根空间管理员）
//  - type = user         → { id, type:"user", role:"admin" }（创建者即管理员）
//  - 虚拟组织（account.user-organizations 中 virtual=true 且 code = 根空间 id）：
//    其 members 逐个并入 accesses（type:"user"，角色沿用 member.role，默认 member）
//  - name：尽力而为从 account 解析（组织名 / 用户名），解析不到为 null
//
// 幂等：只处理"尚无 accesses"的文档；已带 accesses 的文档跳过。三种历史状态
//       互斥，分别处理后可安全重跑。处理顺序：owners 数组 → 单对象 owner → organizationId。
//
// 执行：mongosh <connection-string> spaces-accesses.js
//       （matrix 与 account 在同一个 MongoDB 实例上，用默认库连接即可）
// ============================================================================

const matrix = db.getSiblingDB("matrix");
const account = db.getSiblingDB("account");

function userName(userId) {
    if (typeof userId !== "string" || !userId) return null;
    if (!/^[0-9a-fA-F]{24}$/.test(userId)) return null;
    try {
        const u = account["account-users"].findOne({ _id: ObjectId(userId) });
        return u && u.username ? u.username : null;
    } catch (e) {
        return null;
    }
}

function orgName(orgId) {
    if (typeof orgId !== "string" || !orgId) return null;
    try {
        const o = account["user-organizations"].findOne({ _id: orgId });
        return o && o.name ? o.name : null;
    } catch (e) {
        return null;
    }
}

/** 根空间：把虚拟组织（项目）成员并入 accesses */
function mergeVirtualOrgMembers(s, accesses) {
    if (s.parentId) return;
    const vo = account["user-organizations"].findOne({ _id: s._id.toString(), virtual: true });
    if (vo && Array.isArray(vo.members)) {
        for (const m of vo.members) {
            if (!m || !m.userId) continue;
            if (accesses.some(o => o.type === "user" && o.id === m.userId)) continue;
            accesses.push({
                id: m.userId,
                type: "user",
                role: m.role || "member",
                name: m.name || userName(m.userId),
                remark: null
            });
        }
    }
}

// ---- 状态 3：owners 数组 → accesses（$set + $unset，字段改名） ----------------
let renamed = 0;
{
    const pending = matrix.spaces.find({ owners: { $type: "array" }, accesses: { $exists: false } }).toArray();
    for (const s of pending) {
        matrix.spaces.updateOne(
            { _id: s._id },
            { $set: { accesses: s.owners }, $unset: { owners: "", owner: "", organizationId: "" } }
        );
        renamed++;
    }
    print("状态3（owners 数组 → accesses）：" + renamed);
}

// ---- 状态 2：单对象 owner → accesses（并合并虚拟组织成员） -------------------
let fromOwner = 0;
{
    const pending = matrix.spaces.find({
        owner: { $exists: true },
        owners: { $exists: false },
        accesses: { $exists: false }
    }).toArray();
    for (const s of pending) {
        const accesses = [];
        const owner = s.owner;
        if (owner && owner.id) {
            if (owner.type === "user") {
                accesses.push({ id: owner.id, type: "user", role: "admin", name: userName(owner.id), remark: null });
            } else {
                accesses.push({ id: owner.id, type: "organization", role: "admin", name: orgName(owner.id), remark: null });
            }
        }
        mergeVirtualOrgMembers(s, accesses);
        matrix.spaces.updateOne(
            { _id: s._id },
            { $set: { accesses }, $unset: { owner: "", organizationId: "" } }
        );
        fromOwner++;
    }
    print("状态2（单对象 owner → accesses）：" + fromOwner);
}

// ---- 状态 1：organizationId → accesses（并合并虚拟组织成员） ------------------
let fromOrgId = 0;
{
    const pending = matrix.spaces.find({
        organizationId: { $exists: true, $type: "string", $ne: "" },
        owner: { $exists: false },
        owners: { $exists: false },
        accesses: { $exists: false }
    }).toArray();
    for (const s of pending) {
        const accesses = [{ id: s.organizationId, type: "organization", role: "admin", name: orgName(s.organizationId), remark: null }];
        mergeVirtualOrgMembers(s, accesses);
        matrix.spaces.updateOne(
            { _id: s._id },
            { $set: { accesses }, $unset: { owner: "", organizationId: "" } }
        );
        fromOrgId++;
    }
    print("状态1（organizationId → accesses）：" + fromOrgId);
}

// ---- 验证 --------------------------------------------------------------------
print("迁移后：仍带 owners 数组的文档数（应为 0）= " +
    matrix.spaces.countDocuments({ owners: { $type: "array" } }));
print("迁移后：仍带单对象 owner 的文档数（应为 0）= " +
    matrix.spaces.countDocuments({ owner: { $exists: true } }));
print("迁移后：仍带 organizationId 的文档数（应为 0）= " +
    matrix.spaces.countDocuments({ organizationId: { $exists: true } }));
print("迁移后：带 accesses 数组的文档数 = " +
    matrix.spaces.countDocuments({ accesses: { $type: "array" } }));

const sample = matrix.spaces.findOne({ accesses: { $exists: true } }, { accesses: 1, name: 1 });
if (sample) {
    print("示例文档 accesses = " + JSON.stringify(sample.accesses));
} else {
    print("spaces 集合没有带 accesses 的文档");
}
