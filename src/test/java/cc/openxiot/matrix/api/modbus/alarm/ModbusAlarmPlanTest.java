package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.matrix.api.modbus.alarm.ModbusAlarmPlan.Close;
import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarm;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusAlarmPlan} 的判据（Modbus-Alarm.md 第五节末）。
 *
 * <p>纯函数，不需要数据库。单独测它的理由：这条对账是**唯一**会去关告警行的地方，
 * 判宽了会把仍然配着告警的开行误关（用户看到一条越限告警凭空「已恢复」），
 * 判窄了那条告警会永远挂着 —— 两种都不报错。</p>
 *
 * <p>判据是**规则级**的（该出值上还有没有一条启用着、且 {@code id} 等于行上 {@code ruleId} 的规则）：
 * 停在出值级会让「删掉那条规则、同出值别的规则还在」的开行永远留着 —— 没有任何规则能再改变它。</p>
 */
class ModbusAlarmPlanTest {

    private static final String SID = "65f0c0ffee0123456789abcd";
    private static final String OTHER_SID = "65f0c0ffee0123456789abce";

    /** 夹具里那条规则的身份：定义里的规则与行上的 {@code ruleId} 都用它，即「还是这条规则」。 */
    private static final String RULE_ID = "r1";

    // ===== 还配着告警的：一个都不动 =====

    @Test
    void stillConfiguredIsLeftAlone() {
        assertTrue(plan(service(field("进水温度", alarm(true)), field("回水温度", alarm(true))),
                open(1, "进水温度"), open(1, "回水温度")).isEmpty());
    }

    @Test
    void bitKeysAreJudgedOnTheBit() {
        // 位是独立的结果键：配在位上，就按位判。父字段有没有告警与它无关
        ModbusRegistry bits = bits("进水阀位", "进水阀", alarm(true));
        assertTrue(plan(service(bits), open(1, "进水阀")).isEmpty());

        // 反过来：位上的告警被关掉 ⇒ 位那个键不再被覆盖（哪怕父寄存器还配着）
        ModbusRegistry off = bits("进水阀位", "进水阀", alarm(false));
        assertEquals(List.of("进水阀"), fields(plan(service(off), open(1, "进水阀"))));
    }

    // ===== 再也判定不到的：关掉 =====

    @Test
    void switchedOffAlarmCloses() {
        // 这个字段还在、值也还在采，但告警被关掉了 —— 那条开行再也判定不到
        assertEquals(List.of("进水温度"),
                fields(plan(service(field("进水温度", alarm(false))), open(1, "进水温度"))));
    }

    @Test
    void missingAlarmCloses() {
        assertEquals(List.of("进水温度"),
                fields(plan(service(field("进水温度")), open(1, "进水温度"))));
    }

    @Test
    void deletedRuleClosesEvenWhenASiblingRuleRemains() {
        // 同一出值上**还有别的启用规则**，但这条开行不是它们开的（ruleId 对不上）：
        // 新定义里已经没有能判定它的规则了，关掉。判据停在出值级就会漏掉这一条 ——
        // 而那条行会一直挂着，因为没有任何规则能再改变它的状态
        ModbusRegistry field = field("进水温度", alarm("r2", true));
        assertEquals(List.of("进水温度"), fields(plan(service(field), open(1, "进水温度"))));
    }

    @Test
    void rowWithoutARuleIdIsLeftAlone() {
        // 说不上是哪条规则开的（只可能来自更早的数据）：判据无从比对，而关掉才是不可逆的那个动作。
        // 采集侧的胜者比对会把它按 SUPERSEDED 接手 —— 那才是能说清原因的那条路径
        ModbusServiceAlarm row = row(SID, 1, "进水温度");
        row.ruleId = null;
        assertTrue(ModbusAlarmPlan.plan(
                List.of(service(field("进水温度", alarm(true)))), List.of(row)).isEmpty());
    }

    @Test
    void deletedFieldCloses() {
        ModbusService service = service(field("回水温度", alarm(true)));
        assertEquals(List.of("进水温度"), fields(plan(service, open(1, "进水温度"))));
    }

    @Test
    void registryAlarmCoversAnyReadBlock() {
        // 告警属寄存器（寄存器中心），不属某个读方法：点表里这条寄存器还配着告警，
        // 无论开行挂在哪一个方法序号上都说明它还被覆盖 —— 一个都不动
        ModbusService service = service(field("进水温度", alarm(true)));
        assertTrue(plan(service, open(1, "进水温度"), open(2, "进水温度")).isEmpty());
    }

    @Test
    void renamedFieldClosesTheOldName() {
        // 寄存器改名：旧名的开行再也判定不到（新名是另一条，由采集链路自己去开/关）
        ModbusService service = service(field("进水温度2", alarm(true)));
        assertEquals(List.of("进水温度"), fields(plan(service, open(1, "进水温度"))));
    }

    @Test
    void pointTableWithNoEnabledAlarmsCloses() {
        // 点表里没有任何启用告警（这里连一条规则都没有）：任何开行都判定不到 —— 全部残留
        ModbusService service = service(field("进水温度"));
        assertEquals(List.of("进水温度"), fields(plan(service, open(1, "进水温度"))));
    }

    @Test
    void rowWithoutAFunctionIndexCloses() {
        // 行里没有方法序号：没有任何定义能覆盖它（这里点表里也没有这条字段），
        // 留着就是永远判定不到。关闭仍按（serviceId, null）分组。
        assertEquals(List.of("进水温度"),
                fields(plan(service(), open(null, "进水温度"))));
    }

    // ===== 保守的那条：不认识的服务一律不动 =====

    @Test
    void unknownServiceIsNeverTouched() {
        // 「这次没拿到它的定义」不是「它的告警陈旧」的证据。这条口径让写入路径（只传一个服务）
        // 与启动路径（传全部）行为一致 —— 否则写入路径会把别的服务的开行误关一片
        ModbusServiceAlarm row = row(OTHER_SID, 1, "进水温度");
        assertTrue(ModbusAlarmPlan.plan(List.of(service(field("进水温度", alarm(true)))),
                List.of(row)).isEmpty());
        // 服务列表为空（拿不到定义）时同理：一个都不动
        assertTrue(ModbusAlarmPlan.plan(List.of(), List.of(row)).isEmpty());
    }

    @Test
    void rowWithoutCoordinatesIsSkipped() {
        // 坐标不全的行没有「定义是否还覆盖它」可言：既不是「该关」，也不是「该留」
        assertTrue(ModbusAlarmPlan.plan(List.of(service(field("进水温度", alarm(true)))),
                List.of(row(SID, 1, null))).isEmpty());
        assertTrue(ModbusAlarmPlan.plan(List.of(service(field("进水温度", alarm(true)))),
                List.of(row(null, 1, "进水温度"))).isEmpty());
    }

    @Test
    void emptyInputsDecideNothing() {
        assertTrue(ModbusAlarmPlan.plan(null, null).isEmpty());
        assertTrue(ModbusAlarmPlan.plan(List.of(service()), List.of()).isEmpty());
        assertTrue(ModbusAlarmPlan.plan(List.of(service()), null).isEmpty());
    }

    // ===== 分组与顺序 =====

    @Test
    void oneStatementPerFunctionWithAllItsStaleFields() {
        // 关闭是按整个开行标识 updateMany 的 —— 同一个方法上的多个出值合成一条语句，不是一条一个
        // （一条一个会让一轮对账的写语句数量放大成出值数倍）
        ModbusService service = service(
                field("进水温度", alarm(true)), field("回水温度"), field("压力"),
                field("出水温度"));
        List<Close> closes = ModbusAlarmPlan.plan(List.of(service), List.of(
                row(SID, 1, "回水温度"), row(SID, 1, "进水温度"), row(SID, 1, "压力"),
                row(SID, 2, "出水温度")));

        assertEquals(2, closes.size());
        // 方法 1：两个陈旧出值合成一条，字段名有序；「进水温度」还配着告警，不在里面
        assertEquals(List.of("压力", "回水温度"), closes.get(0).fields());
        assertEquals(List.of("出水温度"), closes.get(1).fields());
        assertEquals(1, closes.get(0).transactionIndex().intValue());
        assertEquals(2, closes.get(1).transactionIndex().intValue());
        assertEquals(SID, closes.get(0).serviceId());
    }

    @Test
    void orderIsDeterministic() {
        // 顺序稳定只是为了让日志与测试可读；真正要紧的是「同一批输入得到同一批输出」
        ModbusService one = service(SID, field("b"));
        ModbusService two = service(OTHER_SID, field("a"));
        List<ModbusServiceAlarm> rows = List.of(
                row(OTHER_SID, 1, "a"), row(SID, 2, "b"), row(SID, 3, "c"));

        List<Close> closes = ModbusAlarmPlan.plan(List.of(one, two), rows);
        assertEquals(List.of(SID, SID, OTHER_SID), closes.stream().map(Close::serviceId).toList());
        assertEquals(List.of(2, 3, 1), closes.stream().map(Close::transactionIndex).toList());
    }

    @Test
    void nullFunctionIndexSortsFirst() {
        // 坐标不全的行（没有方法序号）排在最前
        ModbusService service = service(field("a"));
        List<Close> closes = ModbusAlarmPlan.plan(List.of(service),
                List.of(row(SID, 1, "a"), row(SID, null, "b")));

        assertEquals(2, closes.size());
        assertNull(closes.get(0).transactionIndex());
        assertEquals(1, closes.get(1).transactionIndex().intValue());
    }

    // ===== 夹具 =====

    private static List<Close> plan(ModbusService service, ModbusServiceAlarm... rows) {
        return ModbusAlarmPlan.plan(List.of(service), List.of(rows));
    }

    private static List<String> fields(List<Close> closes) {
        return closes.stream().flatMap(close -> close.fields().stream()).toList();
    }

    private static ModbusServiceAlarm open(Integer transactionIndex, String field) {
        return row(SID, transactionIndex, field);
    }

    private static ModbusServiceAlarm row(String serviceId, Integer transactionIndex, String field) {
        ModbusServiceAlarm row = new ModbusServiceAlarm();
        row.serviceId = serviceId;
        row.transactionIndex = transactionIndex;
        row.field = field;
        row.ruleId = RULE_ID;
        row.openKey = ModbusServiceAlarm.openKeyOf(serviceId, transactionIndex, field);
        return row;
    }

    /**
     * 服务夹具：告警现在属 configSnapshot.registries[]（寄存器中心），不再属 function 应答字段，
     * 故这里用寄存器列表直接构建服务的点表快照。
     */
    private static ModbusService service(ModbusRegistry... registries) {
        return service(SID, registries);
    }

    private static ModbusService service(String serviceId, ModbusRegistry... registries) {
        ModbusService service = new ModbusService();
        service.id = new ObjectId(serviceId);
        ModbusConfig snapshot = new ModbusConfig();
        snapshot.registries = new ArrayList<>(List.of(registries));
        service.configSnapshot = snapshot;
        return service;
    }

    /** 一个带可选告警的寄存器（出值名即寄存器名）。 */
    private static ModbusRegistry field(String name, ModbusRegistryAlarm... alarms) {
        ModbusRegistry reg = new ModbusRegistry();
        reg.name = name;
        reg.alarms = alarms.length == 0 ? null : new ArrayList<>(List.of(alarms));
        return reg;
    }

    /** 位（coil/discrete）寄存器：告警挂在位上，位的 field 即出值名。 */
    private static ModbusRegistry bits(String name, String bitName, ModbusRegistryAlarm... alarms) {
        return field(bitName, alarms);
    }

    /** 一条规则：id 就是 {@link #RULE_ID}，与夹具 {@link #row} 上那个对得上（= 「还是这条规则」）。 */
    private static ModbusRegistryAlarm alarm(boolean enabled) {
        return alarm(RULE_ID, enabled);
    }

    private static ModbusRegistryAlarm alarm(String id, boolean enabled) {
        ModbusRegistryAlarm alarm = new ModbusRegistryAlarm();
        alarm.id = id;
        alarm.enabled = enabled;
        alarm.compare = ">";
        alarm.threshold = 80.0;
        alarm.level = "WARN";
        alarm.text = "温度过高";
        return alarm;
    }
}
