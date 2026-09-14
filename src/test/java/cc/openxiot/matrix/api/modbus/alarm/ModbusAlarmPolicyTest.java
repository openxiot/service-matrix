package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.matrix.api.modbus.alarm.ModbusAlarmPolicy.Decision;
import cc.openxiot.matrix.api.modbus.alarm.ModbusAlarmPolicy.Open;
import cc.openxiot.matrix.api.modbus.alarm.ModbusAlarmPolicy.Verdict;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusAlarmPolicy} 的判定规则（Modbus-Alarm.md 第二、三节）。
 *
 * <p>纯函数，不需要数据库：这是这套规则唯一能跑起来的地方（{@code @QuarkusTest} 要连 Mongo）。</p>
 */
class ModbusAlarmPolicyTest {

    // ===== 比较语义 =====

    @Test
    void numericOperators() {
        assertEquals(Verdict.HIT, ModbusAlarmPolicy.hit(81, alarm(">", 80.0, null)));
        assertEquals(Verdict.MISS, ModbusAlarmPolicy.hit(80, alarm(">", 80.0, null)));
        assertEquals(Verdict.HIT, ModbusAlarmPolicy.hit(80, alarm(">=", 80.0, null)));
        assertEquals(Verdict.MISS, ModbusAlarmPolicy.hit(79, alarm(">=", 80.0, null)));
        assertEquals(Verdict.HIT, ModbusAlarmPolicy.hit(79, alarm("<", 80.0, null)));
        assertEquals(Verdict.MISS, ModbusAlarmPolicy.hit(80, alarm("<", 80.0, null)));
        assertEquals(Verdict.HIT, ModbusAlarmPolicy.hit(80, alarm("<=", 80.0, null)));
        assertEquals(Verdict.MISS, ModbusAlarmPolicy.hit(81, alarm("<=", 80.0, null)));
        assertEquals(Verdict.HIT, ModbusAlarmPolicy.hit(80, alarm("=", 80.0, null)));
        assertEquals(Verdict.MISS, ModbusAlarmPolicy.hit(80.5, alarm("=", 80.0, null)));
    }

    @Test
    void equalsComparesTheValueListName() {
        ModbusServiceFieldAlarm byName = alarm("=", null, "ON");

        assertEquals(Verdict.HIT, ModbusAlarmPolicy.hit("ON", byName));
        assertEquals(Verdict.MISS, ModbusAlarmPolicy.hit("OFF", byName));
        // 原始值没命中取值表时 parser 出的是个数：我们明确知道它不是那个命名状态 ⇒ MISS，不是 SKIP
        assertEquals(Verdict.MISS, ModbusAlarmPolicy.hit(7, byName));
        // 反向：配置比数、值却是描述字符串 —— 定义对不上（老数据），判不了
        assertEquals(Verdict.SKIP, ModbusAlarmPolicy.hit("ON", alarm("=", 1.0, null)));
    }

    @Test
    void comparesTheParsedValueNotTheRawRegister() {
        ModbusServiceFunction function = function(field("进水温度", alarm(">", 80.0, null)));

        // 点表 scale=0.1：寄存器 805 解析出来是 80.5，用户的阈值 80 是工程单位（曲线画的也是解析值）
        assertEquals(1, ModbusAlarmPolicy.decide(function, sample("进水温度", 80.5), Set.of()).opens().size());
        // 寄存器 750 ⇒ 解析值 75.0 ⇒ 没越限。比原始值的话 750 > 80 会误报，这条把它钉住
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 75.0), Set.of()).isEmpty());
    }

    // ===== 四种边沿格 =====

    @Test
    void fourEdgeCells() {
        ModbusServiceFunction function = function(field("进水温度", alarm(">", 80.0, null)));

        // 无 → HIT：开一条
        Decision opened = ModbusAlarmPolicy.decide(function, sample("进水温度", 81.0), Set.of());
        assertEquals(List.of("进水温度"), opened.opens().stream().map(Open::field).toList());
        assertEquals(81.0, ((Number) opened.opens().get(0).sample()).doubleValue());
        assertEquals(">", opened.opens().get(0).alarm().compare);

        // 有 → HIT：什么都不做 —— 稳态零写，这是「一次越限一条」的落点
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 81.0), open("进水温度")).isEmpty());

        // 有 → MISS：按值关闭
        Decision closed = ModbusAlarmPolicy.decide(function, sample("进水温度", 79.0), open("进水温度"));
        assertEquals(List.of("进水温度"), closed.valueCloses());

        // 无 → MISS：什么都不做
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 79.0), Set.of()).isEmpty());
    }

    @Test
    void oneSampleCanOpenAndCloseAtOnce() {
        ModbusServiceFunction function = function(
                field("进水温度", alarm(">", 80.0, null)),
                field("回水温度", alarm("<", 5.0, null)));

        Decision decision = ModbusAlarmPolicy.decide(function,
                sample("进水温度", 85.0, "回水温度", 9.0), open("回水温度"));

        assertEquals(List.of("进水温度"), decision.opens().stream().map(Open::field).toList());
        assertEquals(List.of("回水温度"), decision.valueCloses());
        assertFalse(decision.isEmpty());
    }

    // ===== 不做防抖（明确的选择，钉住它） =====

    @Test
    void noDebounceSoEveryCrossingIsItsOwnAlarm() {
        // 「不回差、不持续时长」是明确的选择：在阈值附近抖动，每次穿越各记一条永不删除的告警。
        // 将来要加回差，必须先改掉这条测试 —— 让它成为有意为之的决定，而不是顺手加的补丁。
        ModbusServiceFunction function = function(field("进水温度", alarm(">", 80.0, null)));
        Set<String> open = new HashSet<>();
        List<Boolean> opened = new ArrayList<>();

        for (double value : new double[]{81.0, 79.0, 81.0, 79.0, 81.0}) {
            Decision decision = ModbusAlarmPolicy.decide(function, sample("进水温度", value), open);
            opened.add(!decision.opens().isEmpty());
            apply(decision, open);
        }

        assertEquals(List.of(true, false, true, false, true), opened);
        assertEquals(Set.of("进水温度"), open);
    }

    @Test
    void restartDoesNotDuplicateAnOpenAlarm() {
        // 重启或换副本后的第一条样本：库里已有一条开着的告警，值仍越限 ⇒ 不产生第二条
        ModbusServiceFunction function = function(field("进水温度", alarm(">", 80.0, null)));
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 95.0), open("进水温度")).isEmpty());

        // 值已正常 ⇒ 关闭
        assertEquals(List.of("进水温度"),
                ModbusAlarmPolicy.decide(function, sample("进水温度", 20.0), open("进水温度")).valueCloses());
    }

    // ===== 判不了 ≠ 正常 =====

    @Test
    void notComparableIsSkip() {
        assertEquals(Verdict.SKIP, ModbusAlarmPolicy.hit(null, alarm(">", 80.0, null)));
        assertEquals(Verdict.SKIP, ModbusAlarmPolicy.hit(Double.NaN, alarm(">", 80.0, null)));
        assertEquals(Verdict.SKIP, ModbusAlarmPolicy.hit("ON", alarm(">", 80.0, null)));
        assertEquals(Verdict.SKIP, ModbusAlarmPolicy.hit(81, alarm("??", 80.0, null)));
        assertEquals(Verdict.SKIP, ModbusAlarmPolicy.hit(81, alarm(">", null, null)));
        assertEquals(Verdict.SKIP, ModbusAlarmPolicy.hit(81, disabled()));
        assertEquals(Verdict.SKIP, ModbusAlarmPolicy.hit(81, null));
    }

    @Test
    void skipNeverClosesAnOpenAlarm() {
        ModbusServiceFunction function = function(field("进水温度", alarm(">", 80.0, null)));

        // 键不在本次采样里（这一帧截断了）
        assertTrue(ModbusAlarmPolicy.decide(function, sample("别的字段", 1), open("进水温度")).isEmpty());
        // 值是 NaN
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", Double.NaN), open("进水温度")).isEmpty());
        // 键在、值为 null
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", null), open("进水温度")).isEmpty());
    }

    // ===== 定义变更的对账 =====

    @Test
    void definitionCloseOnlyForKeysPresentInTheSample() {
        // 采样里没有这个键：分不清是「定义删了它」还是「这一帧截断了」，宁可留着也不能凭空恢复
        Decision truncated = ModbusAlarmPolicy.decide(function(field("进水温度", alarm(">", 80.0, null))),
                sample("别的字段", 1), open("进水温度"));
        assertTrue(truncated.isEmpty());

        // 采样里有、但新定义已不再给它配告警（关掉了，或字段被删了）⇒ 定义不再覆盖它了，关掉
        Decision switchedOff = ModbusAlarmPolicy.decide(function(field("进水温度", null)),
                sample("进水温度", 20.0), open("进水温度"));
        assertEquals(List.of("进水温度"), switchedOff.definitionCloses());
        assertTrue(switchedOff.valueCloses().isEmpty());

        // 字段被整个删掉：新定义里连这个字段都没有，同样关掉
        Decision fieldGone = ModbusAlarmPolicy.decide(function(field("回水温度", null)),
                sample("进水温度", 20.0), open("进水温度"));
        assertEquals(List.of("进水温度"), fieldGone.definitionCloses());
    }

    @Test
    void definitionCloseDoesNotTouchValueJudgedKeys() {
        // 配了告警、采样里有、但本次既没开也没关：不能因为「没动作」被 DEFINITION 逻辑误关掉
        ModbusServiceFunction function = function(field("进水温度", alarm(">", 80.0, null)));
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 79.0), open("进水温度")).valueCloses()
                .contains("进水温度"));
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 85.0), open("进水温度")).isEmpty());
    }

    // ===== 位 =====

    @Test
    void bitAlarmIsZeroOrOne() {
        ModbusServiceFunction function = function(bits("进水阀位", "进水阀", alarm("=", 1.0, null)));

        Decision opened = ModbusAlarmPolicy.decide(function, sample("进水阀", 1), Set.of());
        assertEquals(List.of("进水阀"), opened.opens().stream().map(Open::field).toList());

        // 位是独立的结果键：父字段那份整段掩码与它无关
        assertEquals(List.of("进水阀"),
                ModbusAlarmPolicy.decide(function, sample("进水阀", 0), open("进水阀")).valueCloses());
    }

    // ===== 免费的闸门 =====

    @Test
    void hasEnabledAlarmIsTheFreeGate() {
        assertFalse(ModbusAlarmPolicy.hasEnabledAlarm(null));
        assertFalse(ModbusAlarmPolicy.hasEnabledAlarm(function()));
        assertFalse(ModbusAlarmPolicy.hasEnabledAlarm(function(field("进水温度", null))));
        assertFalse(ModbusAlarmPolicy.hasEnabledAlarm(function(field("进水温度", disabled()))));
        assertTrue(ModbusAlarmPolicy.hasEnabledAlarm(function(field("进水温度", alarm(">", 80.0, null)))));

        // 位上的告警也算：位是独立的结果键，配在位上
        assertTrue(ModbusAlarmPolicy.hasEnabledAlarm(
                function(bits("进水阀位", "进水阀", alarm("=", 1.0, null)))));
    }

    @Test
    void emptyResponseDecidesNothing() {
        assertTrue(ModbusAlarmPolicy.decide(function(), sample("进水温度", 99.0), Set.of()).isEmpty());
        assertTrue(ModbusAlarmPolicy.decide(null, sample("进水温度", 99.0), Set.of()).isEmpty());
    }

    // ===== 夹具 =====

    /** 把判定结果落到「未恢复集合」上 —— 调用方（告警表）就是这么把它当状态的。 */
    private static void apply(Decision decision, Set<String> open) {
        decision.opens().forEach(opened -> open.add(opened.field()));
        open.removeAll(decision.valueCloses());
        open.removeAll(decision.definitionCloses());
    }

    private static ModbusServiceFunction function(ModbusServiceField... fields) {
        ModbusServiceFunction function = new ModbusServiceFunction();
        function.index = 1;
        function.name = "读进水温度";
        function.request = "010300000001840A";
        function.response = new ArrayList<>(List.of(fields));
        return function;
    }

    /** 只带告警的字段：policy 不看 index / format / scale，那些是 parser 与校验器的事。 */
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

    private static ModbusServiceFieldAlarm alarm(String compare, Double threshold, String state) {
        ModbusServiceFieldAlarm alarm = new ModbusServiceFieldAlarm();
        alarm.enabled = true;
        alarm.compare = compare;
        alarm.threshold = threshold;
        alarm.state = state;
        alarm.level = "WARN";
        alarm.text = "温度过高";
        return alarm;
    }

    private static ModbusServiceFieldAlarm disabled() {
        ModbusServiceFieldAlarm alarm = alarm(">", 80.0, null);
        alarm.enabled = false;
        return alarm;
    }

    private static Map<String, Object> sample(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }

    private static Set<String> open(String... fields) {
        return new HashSet<>(List.of(fields));
    }
}
