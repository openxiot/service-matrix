// ============================================================================
// service-matrix ModbusConfig 归属迁移脚本
// 用途：将 modbus.configs 集合的 orgId 字段迁移为内嵌 owner 对象。
//       ModbusConfig.orgId -> ModbusConfig.owner { id, type }
//       旧结构：{ orgId: "xxxx", ... }
//       新结构：{ owner: { id: "xxxx", type: "organization" }, ... }
//
// 说明：
//  - 旧数据里 orgId 存的就是「归属组织编码」，故 type 固定写死小写
//    "organization"（与 Java 常量 ModbusConfigOwner.TYPE_ORGANIZATION 一致）。
//  - owner.name 不在此脚本设置：它是展示用快照，Web 侧查组织名录时找不到
//    会回退到名称快照或 owner.id（见 modbus.component 的 ownerLabel），可留空。
//  - 幂等：只处理「尚无 owner 且 orgId 是非空字符串」的文档，可安全重跑；
//    已带 owner（手动迁移过或新写入）的文档不受影响。
//  - 在 mongosh 中执行，例如：mongosh <connection-string> modbus-config-owner.js
//    （用连接串里指定的库即可，脚本用 getSiblingDB 固定到 modbus 库）
// ============================================================================

const modbus = db.getSiblingDB("modbus");

print("迁移前：存在 orgId 的文档数 = " +
    modbus.configs.countDocuments({ orgId: { $type: "string", $nin: [""] } }));

const result = modbus.configs.updateMany(
    // 幂等条件：尚无 owner，且 orgId 是非空字符串
    { owner: { $exists: false }, orgId: { $type: "string", $nin: [""] } },
    [
        // 用聚合管道内计算 owner.id（读取旧字段值），避免 JS 逐文档回读
        { $set: { owner: { id: "$orgId", type: "organization" } } },
        { $unset: "orgId" }
    ]
);

print("已迁移文档数 = " + result.modifiedCount);

// 验证：不应再存在带 orgId 的文档；owner 对象结构正确
const leftover = modbus.configs.countDocuments({ orgId: { $exists: true } });
print("迁移后：仍带 orgId 的文档数（应为 0）= " + leftover);

const sample = modbus.configs.findOne({}, { owner: 1, slave: 1 });
if (sample) {
    print("示例文档 owner = " + JSON.stringify(sample.owner));
} else {
    print("configs 集合为空，无需迁移");
}