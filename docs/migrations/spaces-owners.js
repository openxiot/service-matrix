// ============================================================================
// service-matrix SpaceOwner 迁移脚本（第二阶段）
// 用途：将 spaces 集合的单对象 owner 迁移为 owners 数组，并把虚拟组织（项目）
//       成员关系并入 owners。
//       旧结构：{ owner: { id, type } }
//       新结构：{ owners: [ { id, type, role, name, remark }, ... ] }
//
// 规则：
//  - type = organization → { id, type:"organization", role:"admin" }（组织是根空间管理员）
//  - type = user         → { id, type:"user", role:"admin" }（创建者即管理员）
//  - 虚拟组织（account.user-organizations 中 virtual=true 且 code = 根空间 id）：
//    其 members 逐个并入 owners（type:"user"，角色沿用 member.role，默认 member）
//  - name：尽力而为从 account 解析（组织名 / 用户名），解析不到为 null
//
// 幂等：只处理"尚无 owners 且存在 owner"的文档，可安全重跑。
// 说明：迁移后 account 中的虚拟组织文档成为死数据（不再被读取），本脚本不删除，
//       如需清理可另行处理。
//
// 执行：mongosh <connection-string> spaces-owners.js
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

const pending = matrix.spaces.find({ owner: { $exists: true }, owners: { $exists: false } }).toArray();
print("待迁移文档数 = " + pending.length);

let migrated = 0;
for (const s of pending) {
    const owners = [];
    const owner = s.owner;
    if (owner && owner.id) {
        if (owner.type === "user") {
            owners.push({ id: owner.id, type: "user", role: "admin", name: userName(owner.id), remark: null });
        } else {
            owners.push({ id: owner.id, type: "organization", role: "admin", name: orgName(owner.id), remark: null });
        }
    }

    // 根空间：把虚拟组织（项目）成员并入 owners
    if (!s.parentId) {
        const vo = account["user-organizations"].findOne({ _id: s._id.toString(), virtual: true });
        if (vo && Array.isArray(vo.members)) {
            for (const m of vo.members) {
                if (!m || !m.userId) continue;
                if (owners.some(o => o.type === "user" && o.id === m.userId)) continue;
                owners.push({
                    id: m.userId,
                    type: "user",
                    role: m.role || "member",
                    name: m.name || userName(m.userId),
                    remark: null
                });
            }
        }
    }

    matrix.spaces.updateOne({ _id: s._id }, { $set: { owners }, $unset: { owner: "" } });
    migrated++;
}

print("已迁移文档数 = " + migrated);

// 验证
print("迁移后：仍带 owner 单对象字段的文档数（应为 0）= " +
    matrix.spaces.countDocuments({ owner: { $exists: true } }));
print("迁移后：带 owners 数组的文档数 = " +
    matrix.spaces.countDocuments({ owners: { $type: "array" } }));

const sample = matrix.spaces.findOne({ owners: { $exists: true } }, { owners: 1, name: 1 });
if (sample) {
    print("示例文档 owners = " + JSON.stringify(sample.owners));
} else {
    print("spaces 集合没有带 owners 的文档");
}
