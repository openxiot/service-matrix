// ============================================================================
// service-matrix SpaceOwner 迁移脚本
// 用途：将 spaces 集合的 organizationId 字段 迁移为内嵌 owner 对象。
//       SpaceEntity.organizationId -> SpaceEntity.owner { id, type }
//       旧结构：{ organizationId: "xxxx" }
//       新结构：{ owner: { id: "xxxx", type: "organization" } }
//
// 说明：
//  - type 固定写死小写 "organization"（与 Java 常量 SpaceOwnerType.ORGANIZATION 一致）。
//  - 幂等：只处理"存在非空 organizationId 且尚无 owner"的文档，可安全重跑。
//  - 在 mongosh 中执行，例如：mongosh <connection-string> spaces-owner.js
//    （matrix 与 account 在同一个 MongoDB 实例上，用默认库连接即可）
// ============================================================================

const matrix = db.getSiblingDB("matrix");

print("迁移前：存在 organizationId 的文档数 = " +
    matrix.spaces.countDocuments({ organizationId: { $type: "string", $nin: [""] } }));

const result = matrix.spaces.updateMany(
    // 幂等条件：尚无 owner，且 organizationId 是非空字符串
    { owner: { $exists: false }, organizationId: { $type: "string", $nin: [""] } },
    [
        // 用聚合管道内计算 owner.id（读取旧字段值），避免 JS 逐文档回读
        { $set: { owner: { id: "$organizationId", type: "organization" } } },
        { $unset: "organizationId" }
    ]
);

print("已迁移文档数 = " + result.modifiedCount);

// 验证：不应再存在带 organizationId 的文档；owner 对象结构正确
const leftover = matrix.spaces.countDocuments({ organizationId: { $exists: true } });
print("迁移后：仍带 organizationId 的文档数（应为 0）= " + leftover);

const sample = matrix.spaces.findOne({}, { owner: 1, name: 1 });
if (sample) {
    print("示例文档 owner = " + JSON.stringify(sample.owner));
} else {
    print("spaces 集合为空，无需迁移");
}
