package cc.openxiot.matrix.api.modbus.polling;

import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.shadow.ModbusShadow;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.modbus.fd.poll.response.field.ModbusResponseField;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusShadowPlan}：定义改了之后，寄存器影子该跟着动哪些。
 *
 * <p>寄存器是**原子单位**：现有寄存器由 {@code configSnapshot.registries} 给出，唯一的陈旧形态是
 * **孤儿寄存器**（定义里已删掉的 registerIndex）必须主动删 —— 它的影子再也不会被更新，
 * 却一直出现在当前值接口里。寄存器没有「废弃字段键」，所以对非孤儿一律不改。</p>
 */
class ModbusShadowPlanTest {

    private static final String SERVICE_ID = new ObjectId().toHexString();

    /** 定义里已经没有这只寄存器了：整条影子要删（否则前端一直显示一个不存在的点位）。 */
    @Test
    void shadowOfADeletedRegisterIsAnOrphan() {
        FieldDevice fd = fd(1);
        ModbusShadow alive = shadow(SERVICE_ID, 1);
        ModbusShadow orphan = shadow(SERVICE_ID, 2);

        List<ModbusShadowPlan.Fix> fixes = ModbusShadowPlan.plan(fd, List.of(alive, orphan));

        assertEquals(1, fixes.size());
        assertTrue(fixes.get(0).orphan());
        assertEquals(2, fixes.get(0).shadow().registerIndex);
        assertTrue(fixes.get(0).staleFields().isEmpty());
    }

    /** 点表条目缺了 {@code index} 就成不了活着寄存器：有影子的它也是孤儿，一样要删。 */
    @Test
    void registryEntryWithoutAnIndexMakesItsShadowOrphan() {
        FieldDevice fd = fd(1);
        fd.configSnapshot.registries.add(new ModbusRegistry()); // index == null → 不算活寄存器
        ModbusShadow orphan = shadow(SERVICE_ID, 5);

        List<ModbusShadowPlan.Fix> fixes = ModbusShadowPlan.plan(fd, List.of(orphan));

        assertEquals(1, fixes.size());
        assertTrue(fixes.get(0).orphan());
        assertEquals(5, fixes.get(0).shadow().registerIndex);
    }

    /** 影子与定义一致时**不出现在结果里**：调用方据此零写操作（非孤儿一律不改）。 */
    @Test
    void consistentShadowsProduceNoFix() {
        FieldDevice fd = fd(1);

        assertTrue(ModbusShadowPlan.plan(fd, List.of(shadow(SERVICE_ID, 1))).isEmpty());
        assertTrue(ModbusShadowPlan.plan(fd, List.of()).isEmpty());
        assertTrue(ModbusShadowPlan.plan(fd, null).isEmpty());
    }

    /** 拿不到定义就不动任何影子：宁可留一张陈旧影子，也不要把用户的当前值删掉。 */
    @Test
    void withoutDefinitionNothingIsTouched() {
        FieldDevice noRegistries = fd(1);
        noRegistries.configSnapshot.registries = null;
        FieldDevice noSnapshot = fd(1);
        noSnapshot.configSnapshot = null;
        FieldDevice noId = fd(1);
        noId.id = null;

        List<ModbusShadow> shadows = List.of(shadow(SERVICE_ID, 9));

        assertTrue(ModbusShadowPlan.plan(null, shadows).isEmpty());
        assertTrue(ModbusShadowPlan.plan(noRegistries, shadows).isEmpty());
        assertTrue(ModbusShadowPlan.plan(noSnapshot, shadows).isEmpty());
        assertTrue(ModbusShadowPlan.plan(noId, shadows).isEmpty());
    }

    /** 影子是别的服务的：对账按服务做，串了就是 bug —— 不动它，也不要把它当孤儿删掉。 */
    @Test
    void shadowsOfAnotherServiceAreSkipped() {
        FieldDevice fd = fd(1);
        ModbusShadow other = shadow(new ObjectId().toHexString(), 9);

        assertTrue(ModbusShadowPlan.plan(fd, List.of(other)).isEmpty());
    }

    /** 多个修复按 registerIndex 升序（调用方按顺序写库，日志里也好读）；活着的不出现在结果里。 */
    @Test
    void fixesAreSortedByRegisterIndex() {
        FieldDevice fd = fd(1); // 只有 index 1 还活着
        List<ModbusShadow> shadows = new ArrayList<>(List.of(
                shadow(SERVICE_ID, 3),
                shadow(SERVICE_ID, 1),
                shadow(SERVICE_ID, 2)));

        List<ModbusShadowPlan.Fix> fixes = ModbusShadowPlan.plan(fd, shadows);

        assertEquals(2, fixes.size());
        assertEquals(2, fixes.get(0).shadow().registerIndex);
        assertTrue(fixes.get(0).orphan());
        assertEquals(3, fixes.get(1).shadow().registerIndex);
        assertTrue(fixes.get(1).orphan());
    }

    /** 读寄存器序号：把缺引用的字段滤掉；它们进不了影子，也就不该出现在「定义里有」这一侧。 */
    @Test
    void definedFieldsReadsRegistryRefs() {
        assertEquals(Set.of(1, 2),
                ModbusShadowPlan.definedFields(List.of(field(1), field(2), field(null))));
        assertTrue(ModbusShadowPlan.definedFields(null).isEmpty());
    }

    // -- helpers --

    /** 定义与影子必须指同一个服务（对账按服务做），所以 id 从 {@link #SERVICE_ID} 还原。 */
    private static FieldDevice fd(Integer... indices) {
        FieldDevice fd = new FieldDevice();
        fd.id = new ObjectId(SERVICE_ID);
        fd.name = "svc";
        List<ModbusRegistry> registers = new ArrayList<>();
        for (Integer index : indices) {
            ModbusRegistry reg = new ModbusRegistry();
            reg.index = index;
            reg.name = "r" + index;
            registers.add(reg);
        }
        ModbusConfig config = new ModbusConfig();
        config.registries = registers;
        fd.configSnapshot = config;
        return fd;
    }

    private static ModbusShadow shadow(String fdid, Integer registerIndex) {
        ModbusShadow shadow = new ModbusShadow();
        shadow.id = new ObjectId();
        shadow.fdid = fdid;
        shadow.registerIndex = registerIndex;
        return shadow;
    }

    private static ModbusResponseField field(Integer registryIndex) {
        ModbusResponseField field = new ModbusResponseField();
        field.registryIndex = registryIndex;
        return field;
    }
}