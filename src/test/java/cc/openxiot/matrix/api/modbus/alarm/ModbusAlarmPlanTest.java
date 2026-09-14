package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.matrix.api.modbus.alarm.ModbusAlarmPlan.Close;
import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
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
 */
class ModbusAlarmPlanTest {

    private static final String SID = "65f0c0ffee0123456789abcd";
    private static final String OTHER_SID = "65f0c0ffee0123456789abce";

    // ===== 还配着告警的：一个都不动 =====

    @Test
    void stillConfiguredIsLeftAlone() {
        assertTrue(plan(service(function(field("进水温度", alarm(true)), field("回水温度", alarm(true)))),
                open(1, "进水温度"), open(1, "回水温度")).isEmpty());
    }

    @Test
    void bitKeysAreJudgedOnTheBit() {
        // 位是独立的结果键：配在位上，就按位判。父字段有没有告警与它无关
        ModbusServiceField bits = bits("进水阀位", "进水阀", alarm(true));
        assertTrue(plan(service(function(bits)), open(1, "进水阀")).isEmpty());

        // 反过来：位上的告警被关掉 ⇒ 位那个键不再被覆盖（哪怕父字段还配着）
        ModbusServiceField off = bits("进水阀位", "进水阀", alarm(false));
        assertEquals(List.of("进水阀"), fields(plan(service(function(off)), open(1, "进水阀"))));
    }

    // ===== 再也判定不到的：关掉 =====

    @Test
    void switchedOffAlarmCloses() {
        // 这个字段还在、值也还在采，但告警被关掉了 —— 那条开行再也判定不到
        assertEquals(List.of("进水温度"),
                fields(plan(service(function(field("进水温度", alarm(false)))), open(1, "进水温度"))));
    }

    @Test
    void missingAlarmCloses() {
        assertEquals(List.of("进水温度"),
                fields(plan(service(function(field("进水温度", null))), open(1, "进水温度"))));
    }

    @Test
    void deletedFieldCloses() {
        ModbusService service = service(function(field("回水温度", alarm(true))));
        assertEquals(List.of("进水温度"), fields(plan(service, open(1, "进水温度"))));
    }

    @Test
    void deletedFunctionCloses() {
        // 整个方法没了：它上面的开行一条也留不住
        ModbusService service = service(function(2, field("进水温度", alarm(true))));
        assertEquals(List.of("进水温度"), fields(plan(service, open(1, "进水温度"))));
    }

    @Test
    void renamedFieldClosesTheOldName() {
        // 字段改名：旧名的开行再也判定不到（新名是另一条，由采集链路自己去开/关）
        ModbusService service = service(function(field("进水温度2", alarm(true))));
        assertEquals(List.of("进水温度"), fields(plan(service, open(1, "进水温度"))));
    }

    @Test
    void writeMethodClosesEverything() {
        // 写方法的应答是请求回显、没有读值，告警只许配在读方法上 —— 它上面任何开行都是残留
        ModbusServiceFunction write = new ModbusServiceFunction();
        write.index = 1;
        write.request = "010600000001";
        assertEquals(List.of("进水温度"), fields(plan(service(write), open(1, "进水温度"))));
    }

    @Test
    void rowWithoutAFunctionIndexCloses() {
        // 行里没有方法序号：没有任何定义能覆盖它，留着就是永远判定不到
        assertEquals(List.of("进水温度"),
                fields(plan(service(function(field("进水温度", alarm(true)))), open(null, "进水温度"))));
    }

    // ===== 保守的那条：不认识的服务一律不动 =====

    @Test
    void unknownServiceIsNeverTouched() {
        // 「这次没拿到它的定义」不是「它的告警陈旧」的证据。这条口径让写入路径（只传一个服务）
        // 与启动路径（传全部）行为一致 —— 否则写入路径会把别的服务的开行误关一片
        ModbusServiceAlarm row = row(OTHER_SID, 1, "进水温度");
        assertTrue(ModbusAlarmPlan.plan(List.of(service(function(field("进水温度", alarm(true))))),
                List.of(row)).isEmpty());
        // 服务列表为空（拿不到定义）时同理：一个都不动
        assertTrue(ModbusAlarmPlan.plan(List.of(), List.of(row)).isEmpty());
    }

    @Test
    void rowWithoutCoordinatesIsSkipped() {
        // 坐标不全的行没有「定义是否还覆盖它」可言：既不是「该关」，也不是「该留」
        assertTrue(ModbusAlarmPlan.plan(List.of(service(function(field("进水温度", alarm(true))))),
                List.of(row(SID, 1, null))).isEmpty());
        assertTrue(ModbusAlarmPlan.plan(List.of(service(function(field("进水温度", alarm(true))))),
                List.of(row(null, 1, "进水温度"))).isEmpty());
    }

    @Test
    void emptyInputsDecideNothing() {
        assertTrue(ModbusAlarmPlan.plan(null, null).isEmpty());
        assertTrue(ModbusAlarmPlan.plan(List.of(service(function())), List.of()).isEmpty());
        assertTrue(ModbusAlarmPlan.plan(List.of(service(function())), null).isEmpty());
    }

    // ===== 分组与顺序 =====

    @Test
    void oneStatementPerFunctionWithAllItsStaleFields() {
        // 关闭是按整个开行标识 updateMany 的 —— 同一个方法上的多个出值合成一条语句，不是一条一个
        // （一条一个会让一轮对账的写语句数量放大成出值数倍）
        ModbusService service = service(
                function(1, field("进水温度", alarm(true)), field("回水温度", null), field("压力", null)),
                function(2, field("出水温度", null)));
        List<Close> closes = ModbusAlarmPlan.plan(List.of(service), List.of(
                row(SID, 1, "回水温度"), row(SID, 1, "进水温度"), row(SID, 1, "压力"),
                row(SID, 2, "出水温度")));

        assertEquals(2, closes.size());
        // 方法 1：两个陈旧出值合成一条，字段名有序；「进水温度」还配着告警，不在里面
        assertEquals(List.of("压力", "回水温度"), closes.get(0).fields());
        assertEquals(List.of("出水温度"), closes.get(1).fields());
        assertEquals(1, closes.get(0).functionIndex().intValue());
        assertEquals(2, closes.get(1).functionIndex().intValue());
        assertEquals(SID, closes.get(0).serviceId());
    }

    @Test
    void orderIsDeterministic() {
        // 顺序稳定只是为了让日志与测试可读；真正要紧的是「同一批输入得到同一批输出」
        ModbusService one = service(SID, function(2, field("b", null)));
        ModbusService two = service(OTHER_SID, function(1, field("a", null)));
        List<ModbusServiceAlarm> rows = List.of(
                row(OTHER_SID, 1, "a"), row(SID, 2, "b"), row(SID, 3, "c"));

        List<Close> closes = ModbusAlarmPlan.plan(List.of(one, two), rows);
        assertEquals(List.of(SID, SID, OTHER_SID), closes.stream().map(Close::serviceId).toList());
        assertEquals(List.of(2, 3, 1), closes.stream().map(Close::functionIndex).toList());
    }

    @Test
    void nullFunctionIndexSortsFirst() {
        // 坐标不全的行（没有方法序号）排在最前
        ModbusService service = service(function(1, field("a", null)));
        List<Close> closes = ModbusAlarmPlan.plan(List.of(service),
                List.of(row(SID, 1, "a"), row(SID, null, "b")));

        assertEquals(2, closes.size());
        assertNull(closes.get(0).functionIndex());
        assertEquals(1, closes.get(1).functionIndex().intValue());
    }

    // ===== 夹具 =====

    private static List<Close> plan(ModbusService service, ModbusServiceAlarm... rows) {
        return ModbusAlarmPlan.plan(List.of(service), List.of(rows));
    }

    private static List<String> fields(List<Close> closes) {
        return closes.stream().flatMap(close -> close.fields().stream()).toList();
    }

    private static ModbusServiceAlarm open(Integer functionIndex, String field) {
        return row(SID, functionIndex, field);
    }

    private static ModbusServiceAlarm row(String serviceId, Integer functionIndex, String field) {
        ModbusServiceAlarm row = new ModbusServiceAlarm();
        row.serviceId = serviceId;
        row.functionIndex = functionIndex;
        row.field = field;
        row.openKey = ModbusServiceAlarm.openKeyOf(serviceId, functionIndex, field);
        return row;
    }

    private static ModbusService service(ModbusServiceFunction... functions) {
        return service(SID, functions);
    }

    private static ModbusService service(String serviceId, ModbusServiceFunction... functions) {
        ModbusService service = new ModbusService();
        service.id = new ObjectId(serviceId);
        service.functions = new ArrayList<>(List.of(functions));
        return service;
    }

    private static ModbusServiceFunction function(ModbusServiceField... fields) {
        return function(1, fields);
    }

    private static ModbusServiceFunction function(int index, ModbusServiceField... fields) {
        ModbusServiceFunction function = new ModbusServiceFunction();
        function.index = index;
        function.request = "010300000001840A";
        function.response = new ArrayList<>(List.of(fields));
        return function;
    }

    private static ModbusServiceField field(String name, ModbusServiceFieldAlarm alarm) {
        ModbusServiceField field = new ModbusServiceField();
        field.field = name;
        field.alarm = alarm;
        return field;
    }

    private static ModbusServiceField bits(String name, String bitName, ModbusServiceFieldAlarm alarm) {
        ModbusServiceFieldBit bit = new ModbusServiceFieldBit();
        bit.offset = 0;
        bit.field = bitName;
        bit.alarm = alarm;

        ModbusServiceField field = new ModbusServiceField();
        field.field = name;
        field.bitList = new ArrayList<>(List.of(bit));
        return field;
    }

    private static ModbusServiceFieldAlarm alarm(boolean enabled) {
        ModbusServiceFieldAlarm alarm = new ModbusServiceFieldAlarm();
        alarm.enabled = enabled;
        alarm.compare = ">";
        alarm.threshold = 80.0;
        alarm.level = "WARN";
        alarm.text = "温度过高";
        return alarm;
    }
}
