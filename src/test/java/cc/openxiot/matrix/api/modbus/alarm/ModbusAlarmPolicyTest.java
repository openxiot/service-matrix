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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusAlarmPolicy} 的判定规则（Modbus-Alarm.md 第二、三节）。
 *
 * <p>纯函数，不需要数据库：这是这套规则唯一能跑起来的地方（{@code @QuarkusTest} 要连 Mongo）。</p>
 *
 * <p><b>一个出值是一组规则</b>，故这里的夹具一律用数组 + 规则标识（{@code alarm(...)} 的 id
 * 由内容定，两条参数相同的规则算同一条）：判定看的是「开着的行是哪条规则开的」，
 * 测试里也必须能表达这件事。</p>
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
        ModbusServiceFieldAlarm rule = alarm(">", 80.0, null);
        ModbusServiceFunction function = function(field("进水温度", rule));

        // 点表 scale=0.1：寄存器 805 解析出来是 80.5，用户的阈值 80 是工程单位（曲线画的也是解析值）
        assertEquals(1, ModbusAlarmPolicy.decide(function, sample("进水温度", 80.5), open()).opens().size());
        // 寄存器 750 ⇒ 解析值 75.0 ⇒ 没越限。比原始值的话 750 > 80 会误报，这条把它钉住
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 75.0), open()).isEmpty());
    }

    // ===== 四种边沿格 =====

    @Test
    void fourEdgeCells() {
        ModbusServiceFieldAlarm rule = alarm(">", 80.0, null);
        ModbusServiceFunction function = function(field("进水温度", rule));

        // 无 → HIT：开一条
        Decision opened = ModbusAlarmPolicy.decide(function, sample("进水温度", 81.0), open());
        assertEquals(List.of("进水温度"), opened.opens().stream().map(Open::field).toList());
        assertEquals(81.0, ((Number) opened.opens().get(0).sample()).doubleValue());
        assertSame(rule, opened.opens().get(0).alarm());

        // 有 → HIT 且**还是那条规则**：什么都不做 —— 稳态零写，这是「一次越限一条」的落点
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 81.0), open("进水温度", rule)).isEmpty());

        // 有 → MISS：按值关闭
        Decision closed = ModbusAlarmPolicy.decide(function, sample("进水温度", 79.0), open("进水温度", rule));
        assertEquals(List.of("进水温度"), closed.valueCloses());

        // 无 → MISS：什么都不做
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 79.0), open()).isEmpty());
    }

    @Test
    void oneSampleCanOpenAndCloseAtOnce() {
        ModbusServiceFieldAlarm high = alarm(">", 80.0, null);
        ModbusServiceFieldAlarm low = alarm("<", 5.0, null);
        ModbusServiceFunction function = function(field("进水温度", high), field("回水温度", low));

        Decision decision = ModbusAlarmPolicy.decide(function,
                sample("进水温度", 85.0, "回水温度", 9.0), open("回水温度", low));

        assertEquals(List.of("进水温度"), decision.opens().stream().map(Open::field).toList());
        assertEquals(List.of("回水温度"), decision.valueCloses());
        assertFalse(decision.isEmpty());
    }

    // ===== 一个出值一组规则：选举、取代 =====

    @Test
    void onlyTheMostSevereRuleOpens() {
        // 用户配的那一组：低于 20 提示 / 超过 26 警告 / 超过 30 严重。31℃ 同时命中后两条，
        // 但只产生**一条**告警 —— 严重的那条
        ModbusServiceFieldAlarm info = level(alarm("<", 20.0, null), "INFO");
        ModbusServiceFieldAlarm warn = level(alarm(">", 26.0, null), "WARN");
        ModbusServiceFieldAlarm critical = level(alarm(">", 30.0, null), "CRITICAL");
        ModbusServiceFunction function = function(field("进水温度", info, warn, critical));

        Decision decision = ModbusAlarmPolicy.decide(function, sample("进水温度", 31.0), open());

        assertEquals(1, decision.opens().size());
        assertSame(critical, decision.opens().get(0).alarm());
        // 没有开着的行，就没有「被取代」这回事
        assertTrue(decision.supersededCloses().isEmpty());
        assertTrue(decision.valueCloses().isEmpty());
    }

    @Test
    void escalationSupersedesTheOpenRow() {
        ModbusServiceFieldAlarm warn = level(alarm(">", 26.0, null), "WARN");
        ModbusServiceFieldAlarm critical = level(alarm(">", 30.0, null), "CRITICAL");
        ModbusServiceFunction function = function(field("进水温度", warn, critical));

        // 27℃ 时按「警告」开着，值升到 31℃ ⇒ 那条关闭（**不是**值恢复）、严重那条接手
        Decision escalated = ModbusAlarmPolicy.decide(function, sample("进水温度", 31.0),
                open("进水温度", warn));

        assertEquals(List.of("进水温度"), escalated.supersededCloses());
        assertEquals(1, escalated.opens().size());
        assertSame(critical, escalated.opens().get(0).alarm());
        assertTrue(escalated.valueCloses().isEmpty());
        assertTrue(escalated.definitionCloses().isEmpty());
    }

    @Test
    void deescalationIsSupersededNotRecovery() {
        // 降级方向的真实原因**不是**值恢复正常：27℃ 仍然越限，记成 VALUE 是谎话
        ModbusServiceFieldAlarm warn = level(alarm(">", 26.0, null), "WARN");
        ModbusServiceFieldAlarm critical = level(alarm(">", 30.0, null), "CRITICAL");
        ModbusServiceFunction function = function(field("进水温度", warn, critical));

        Decision deescalated = ModbusAlarmPolicy.decide(function, sample("进水温度", 27.0),
                open("进水温度", critical));

        assertEquals(List.of("进水温度"), deescalated.supersededCloses());
        assertEquals(1, deescalated.opens().size());
        assertSame(warn, deescalated.opens().get(0).alarm());
        assertTrue(deescalated.valueCloses().isEmpty());
    }

    @Test
    void sameLevelTieGoesToTheLaterRule() {
        // 同级并列是个说不出什么道理、但必须确定的裁决：取**声明顺序里靠后**的那条。
        // 选它的理由是它由用户掌控 —— 想换就把另一条往下挪。不为此加「同级去重」校验：
        // 低侧 + 高侧那种配置（低于 20 严重 / 超过 30 严重）永远不可能同时命中
        ModbusServiceFieldAlarm first = level(alarm(">", 30.0, null), "CRITICAL");
        ModbusServiceFieldAlarm second = level(alarm(">", 28.0, null), "CRITICAL");

        assertSame(second, firstOpen(decision(field("进水温度", first, second), 31.0, open())));
        // 换个顺序就换一条赢：裁决完全由用户的排列决定
        assertSame(first, firstOpen(decision(field("进水温度", second, first), 31.0, open())));
    }

    @Test
    void retuningTheSameRuleDoesNotChurn() {
        // 规则身份是 id，改阈值不动它 —— 于是「把阈值从 80 改成 60」不会把开着的告警关掉重开
        ModbusServiceFieldAlarm rule = withId(alarm(">", 80.0, null), "r1");
        ModbusServiceFieldAlarm retuned = withId(alarm(">", 60.0, null), "r1");

        // 80 到 81、60 到 81 都命中同一条规则 ⇒ 一次写操作都不该有
        Decision decision = ModbusAlarmPolicy.decide(function(field("进水温度", retuned)),
                sample("进水温度", 81.0), open("进水温度", rule));
        assertTrue(decision.isEmpty());

        // 反过来：标识变了就是**另一条**规则（删掉旧规则、加一条新的），那条开着的一样要让位
        ModbusServiceFieldAlarm rebuilt = withId(alarm(">", 60.0, null), "r2");
        Decision replaced = ModbusAlarmPolicy.decide(function(field("进水温度", rebuilt)),
                sample("进水温度", 81.0), open("进水温度", rule));
        assertEquals(List.of("进水温度"), replaced.supersededCloses());
        assertEquals(1, replaced.opens().size());
    }

    @Test
    void aHitBeatsASkipInTheSameField() {
        // 同一出值的规则之间可能一条 HIT、另一条判不了（定义与值形态对不上；校验器挡新数据、老数据可能出现）。
        // 有胜者就按胜者走，判不了的那条只进日志
        ModbusServiceFieldAlarm hit = alarm(">", 80.0, null);
        ModbusServiceFieldAlarm broken = withId(alarm("??", 80.0, null), "broken");
        ModbusServiceFunction function = function(field("进水温度", hit, broken));

        Decision decision = ModbusAlarmPolicy.decide(function, sample("进水温度", 85.0), open());

        assertEquals(1, decision.opens().size());
        assertSame(hit, decision.opens().get(0).alarm());
        assertEquals(1, decision.skips().size());
        assertSame(broken, decision.skips().get(0).alarm());
    }

    @Test
    void rankIsLowToHighAndUnknownIsLowest() {
        assertEquals(0, ModbusAlarmPolicy.rank("INFO"));
        assertEquals(1, ModbusAlarmPolicy.rank("WARN"));
        assertEquals(2, ModbusAlarmPolicy.rank("CRITICAL"));
        // 不认识的级别（老数据、或后端将来加了新级别）一律最低：它照样能开出告警来，只是选不赢
        assertEquals(-1, ModbusAlarmPolicy.rank("FATAL"));
        assertEquals(-1, ModbusAlarmPolicy.rank(null));
        // 白名单与次序同源：不会是「一个收、另一个不收」
        assertEquals(ModbusAlarmPolicy.LEVEL_ORDER.size(), ModbusAlarmPolicy.LEVELS.size());
        assertTrue(ModbusAlarmPolicy.LEVELS.containsAll(ModbusAlarmPolicy.LEVEL_ORDER));
    }

    // ===== 不做防抖（明确的选择，钉住它） =====

    @Test
    void noDebounceSoEveryCrossingIsItsOwnAlarm() {
        // 「不回差、不持续时长」是明确的选择：在阈值附近抖动，每次穿越各记一条永不删除的告警。
        // 将来要加回差，必须先改掉这条测试 —— 让它成为有意为之的决定，而不是顺手加的补丁。
        ModbusServiceFieldAlarm rule = alarm(">", 80.0, null);
        ModbusServiceFunction function = function(field("进水温度", rule));
        Map<String, String> open = new HashMap<>();
        List<Boolean> opened = new ArrayList<>();

        for (double value : new double[]{81.0, 79.0, 81.0, 79.0, 81.0}) {
            Decision decision = ModbusAlarmPolicy.decide(function, sample("进水温度", value), open);
            opened.add(!decision.opens().isEmpty());
            apply(decision, open);
        }

        assertEquals(List.of(true, false, true, false, true), opened);
        assertEquals(Map.of("进水温度", rule.id), open);
    }

    @Test
    void restartDoesNotDuplicateAnOpenAlarm() {
        // 重启或换副本后的第一条样本：库里已有一条开着的告警（还是这条规则开的），值仍越限 ⇒ 不产生第二条
        ModbusServiceFieldAlarm rule = alarm(">", 80.0, null);
        ModbusServiceFunction function = function(field("进水温度", rule));
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 95.0), open("进水温度", rule)).isEmpty());

        // 值已正常 ⇒ 关闭
        assertEquals(List.of("进水温度"),
                ModbusAlarmPolicy.decide(function, sample("进水温度", 20.0), open("进水温度", rule))
                        .valueCloses());
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
        ModbusServiceFieldAlarm rule = alarm(">", 80.0, null);
        ModbusServiceFunction function = function(field("进水温度", rule));

        // 键不在本次采样里（这一帧截断了）
        Decision truncated = ModbusAlarmPolicy.decide(function, sample("别的字段", 1), open("进水温度", rule));
        assertTrue(truncated.isEmpty());
        assertEquals(1, truncated.skips().size());

        // 值是 NaN
        Decision nan = ModbusAlarmPolicy.decide(function, sample("进水温度", Double.NaN),
                open("进水温度", rule));
        assertTrue(nan.isEmpty());
        assertEquals(1, nan.skips().size());

        // 键在、值为 null
        Decision nullValue = ModbusAlarmPolicy.decide(function, sample("进水温度", null),
                open("进水温度", rule));
        assertTrue(nullValue.isEmpty());
        assertEquals(1, nullValue.skips().size());
    }

    @Test
    void notComparableIsReportedNotSilent() {
        // 判不了必须留下痕迹：它意味着**这条告警配置当前不工作**，而「配了却不工作、还没有声音」
        // 正是这个特性要消灭的失效模式。判定器只管把材料交出来，怎么记是调用方的事。
        ModbusServiceFieldAlarm rule = alarm(">", 80.0, null);
        ModbusServiceFunction function = function(field("进水温度", rule));

        // 这一帧里没有这个出值：sample 为 null
        Decision truncated = ModbusAlarmPolicy.decide(function, sample("别的字段", 1), open());
        assertEquals(1, truncated.skips().size());
        assertEquals("进水温度", truncated.skips().get(0).field());
        assertNull(truncated.skips().get(0).sample());
        assertSame(rule, truncated.skips().get(0).alarm());
        // 但「判不了」不算「有事要写」：稳态零写这条纪律不能被日志需求破坏
        assertTrue(truncated.isEmpty());

        // 定义比数、运行期给了字符串（老数据 / 设备固件与点表不符）
        Decision mismatched = ModbusAlarmPolicy.decide(
                function(field("进水温度", alarm(">", 80.0, null))), sample("进水温度", "ON"), open());
        assertEquals(1, mismatched.skips().size());
        assertEquals("ON", mismatched.skips().get(0).sample());

        // 没配告警、配了但关掉、判得出来：都不是「判不了」，一条都不报
        assertTrue(ModbusAlarmPolicy.decide(function(field("进水温度")), sample("进水温度", "ON"), open())
                .skips().isEmpty());
        assertTrue(ModbusAlarmPolicy.decide(function(field("进水温度", disabled())), sample("进水温度", "ON"), open())
                .skips().isEmpty());
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 81.0), open()).skips().isEmpty());
    }

    @Test
    void everyRuleReportsItsOwnSkip() {
        // 一个出值可以配一组规则，所以「哪一条判不了」也要分别报出来 ——
        // 日志的去重键因此细到规则，否则同一出值的第二条坏规则永远不会被报出来
        ModbusServiceFieldAlarm first = withId(alarm("??", 80.0, null), "broken-1");
        ModbusServiceFieldAlarm second = withId(alarm("??", 90.0, null), "broken-2");
        ModbusServiceFunction function = function(field("进水温度", first, second));

        Decision decision = ModbusAlarmPolicy.decide(function, sample("进水温度", 85.0), open());

        assertTrue(decision.isEmpty());
        assertEquals(List.of("broken-1", "broken-2"),
                decision.skips().stream().map(skip -> skip.alarm().id).toList());
        // 全是 SKIP：一条开着的行都不许动（一次截断/畸形的应答不能把告警凭空关掉）
        assertTrue(decision.valueCloses().isEmpty());
        assertTrue(decision.definitionCloses().isEmpty());
        assertTrue(decision.supersededCloses().isEmpty());
    }

    @Test
    void openCarriesTheUnitSnapshotAndBitsHaveNone() {
        ModbusServiceFieldAlarm rule = alarm(">", 80.0, null);
        ModbusServiceField withUnit = field("进水温度", rule);
        withUnit.unit = "℃";
        assertEquals("℃", ModbusAlarmPolicy.decide(function(withUnit), sample("进水温度", 81.0), open())
                .opens().get(0).unit());

        // 位没有自己的单位：父字段那个（比如 ℃）贴到 0/1 的位上只会让列表里出现「1 ℃」
        ModbusServiceField bits = bits("进水阀位", "进水阀", alarm("=", 1.0, null));
        bits.unit = "℃";
        assertNull(ModbusAlarmPolicy.decide(function(bits), sample("进水阀", 1), open()).opens().get(0).unit());
    }

    // ===== 定义变更的对账 =====

    @Test
    void definitionCloseOnlyForKeysPresentInTheSample() {
        ModbusServiceFieldAlarm rule = alarm(">", 80.0, null);

        // 采样里没有这个键：分不清是「定义删了它」还是「这一帧截断了」，宁可留着也不能凭空恢复
        Decision truncated = ModbusAlarmPolicy.decide(function(field("进水温度", rule)),
                sample("别的字段", 1), open("进水温度", rule));
        assertTrue(truncated.isEmpty());

        // 采样里有、但新定义已不再给它配告警（关掉了，或字段被删了）⇒ 定义不再覆盖它了，关掉
        Decision switchedOff = ModbusAlarmPolicy.decide(function(field("进水温度")),
                sample("进水温度", 20.0), open("进水温度", rule));
        assertEquals(List.of("进水温度"), switchedOff.definitionCloses());
        assertTrue(switchedOff.valueCloses().isEmpty());

        // 字段被整个删掉：新定义里连这个字段都没有，同样关掉
        Decision fieldGone = ModbusAlarmPolicy.decide(function(field("回水温度")),
                sample("进水温度", 20.0), open("进水温度", rule));
        assertEquals(List.of("进水温度"), fieldGone.definitionCloses());
    }

    @Test
    void allRulesDisabledIsADefinitionCloseNotAValueClose() {
        // 规则全停用了：配置留着、但没有任何规则参与判定 ⇒ 这条已开着的行再也判定不到，按定义关掉。
        // 与「值回到正常」分开，是因为这一帧的值可能仍然越限 —— 记 VALUE 是谎话
        ModbusServiceFieldAlarm rule = alarm(">", 80.0, null);
        ModbusServiceFieldAlarm off = disabled();
        ModbusServiceFunction function = function(field("进水温度", off));

        Decision decision = ModbusAlarmPolicy.decide(function, sample("进水温度", 95.0), open("进水温度", rule));

        assertEquals(List.of("进水温度"), decision.definitionCloses());
        assertTrue(decision.valueCloses().isEmpty());
        // 停用的规则既不判定、也不报「判不了」
        assertTrue(decision.skips().isEmpty());
    }

    @Test
    void definitionCloseDoesNotTouchValueJudgedKeys() {
        // 配了告警、采样里有、但本次既没开也没关：不能因为「没动作」被 DEFINITION 逻辑误关掉
        ModbusServiceFieldAlarm rule = alarm(">", 80.0, null);
        ModbusServiceFunction function = function(field("进水温度", rule));
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 79.0), open("进水温度", rule))
                .valueCloses().contains("进水温度"));
        assertTrue(ModbusAlarmPolicy.decide(function, sample("进水温度", 85.0), open("进水温度", rule)).isEmpty());
    }

    // ===== 位 =====

    @Test
    void bitAlarmIsZeroOrOne() {
        ModbusServiceFieldAlarm rule = alarm("=", 1.0, null);
        ModbusServiceFunction function = function(bits("进水阀位", "进水阀", rule));

        Decision opened = ModbusAlarmPolicy.decide(function, sample("进水阀", 1), open());
        assertEquals(List.of("进水阀"), opened.opens().stream().map(Open::field).toList());

        // 位是独立的结果键：父字段那份整段掩码与它无关
        assertEquals(List.of("进水阀"),
                ModbusAlarmPolicy.decide(function, sample("进水阀", 0), open("进水阀", rule)).valueCloses());
    }

    @Test
    void bitsCarryTheirOwnRuleGroup() {
        // 一位一组规则：= 0 提示、= 1 严重 —— 与字段上的那组彼此独立
        ModbusServiceFieldAlarm off = level(alarm("=", 0.0, null), "INFO");
        ModbusServiceFieldAlarm on = level(alarm("=", 1.0, null), "CRITICAL");
        ModbusServiceFunction function = function(bits("进水阀位", "进水阀", off, on));

        // 0 只命中「= 0」那条：同时命中才轮得到选举，单单命中一条时级别高低无所谓
        Decision zero = ModbusAlarmPolicy.decide(function, sample("进水阀", 0), open());
        assertEquals(1, zero.opens().size());
        assertSame(off, zero.opens().get(0).alarm());

        // 变成 1：另一条接手，那条开着的按 SUPERSEDED 关（值没有回到正常，是换了一条规则）
        Decision one = ModbusAlarmPolicy.decide(function, sample("进水阀", 1), open("进水阀", off));
        assertEquals(List.of("进水阀"), one.supersededCloses());
        assertSame(on, one.opens().get(0).alarm());
    }

    // ===== 免费的闸门 =====

    @Test
    void hasEnabledAlarmIsTheFreeGate() {
        assertFalse(ModbusAlarmPolicy.hasEnabledAlarm(null));
        assertFalse(ModbusAlarmPolicy.hasEnabledAlarm(function()));
        assertFalse(ModbusAlarmPolicy.hasEnabledAlarm(function(field("进水温度"))));
        assertFalse(ModbusAlarmPolicy.hasEnabledAlarm(function(field("进水温度", disabled()))));
        assertTrue(ModbusAlarmPolicy.hasEnabledAlarm(function(field("进水温度", alarm(">", 80.0, null)))));

        // 一组里只要有一条启用着就算
        assertTrue(ModbusAlarmPolicy.hasEnabledAlarm(
                function(field("进水温度", disabled(), alarm(">", 80.0, null)))));

        // 位上的告警也算：位是独立的结果键，配在位上
        assertTrue(ModbusAlarmPolicy.hasEnabledAlarm(
                function(bits("进水阀位", "进水阀", alarm("=", 1.0, null)))));
    }

    @Test
    void emptyResponseDecidesNothing() {
        assertTrue(ModbusAlarmPolicy.decide(function(), sample("进水温度", 99.0), open()).isEmpty());
        assertTrue(ModbusAlarmPolicy.decide(null, sample("进水温度", 99.0), open()).isEmpty());
        // 空定义也就没有「判不了」：skips 只能来自配了告警的出值
        assertTrue(ModbusAlarmPolicy.decide(function(), sample("进水温度", 99.0), open()).skips().isEmpty());
        assertTrue(ModbusAlarmPolicy.decide(null, sample("进水温度", 99.0), open()).skips().isEmpty());
    }

    // ===== 夹具 =====

    /** 把判定结果落到「开着的行」上 —— 调用方（告警表）就是这么把它当状态的。 */
    private static void apply(Decision decision, Map<String, String> open) {
        // 先关后开，与写序同口径：同一个出值被取代时，先落的那条要让位给新开的
        open.keySet().removeAll(decision.valueCloses());
        open.keySet().removeAll(decision.supersededCloses());
        open.keySet().removeAll(decision.definitionCloses());
        decision.opens().forEach(opened -> open.put(opened.field(), opened.alarm().id));
    }

    private static Decision decision(ModbusServiceField field, double value, Map<String, String> open) {
        return ModbusAlarmPolicy.decide(function(field), sample(field.field, value), open);
    }

    private static ModbusServiceFieldAlarm firstOpen(Decision decision) {
        assertEquals(1, decision.opens().size());
        return decision.opens().get(0).alarm();
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
    private static ModbusServiceField field(String name, ModbusServiceFieldAlarm... alarms) {
        ModbusServiceField field = new ModbusServiceField();
        field.field = name;
        field.alarms = new ArrayList<>(List.of(alarms));
        return field;
    }

    private static ModbusServiceField bits(String name, String bitName, ModbusServiceFieldAlarm... alarms) {
        ModbusServiceFieldBit bit = new ModbusServiceFieldBit();
        bit.offset = 0;
        bit.field = bitName;
        bit.alarms = new ArrayList<>(List.of(alarms));

        ModbusServiceField field = new ModbusServiceField();
        field.field = name;
        field.bitList = new ArrayList<>(List.of(bit));
        return field;
    }

    /**
     * 一条规则。<b>标识由内容生成</b>：参数相同的两条规则算同一条 ——
     * 测试里「这条行还是那条规则开的」因此不需要到处手写 id。要别的身份用 {@link #withId}。
     */
    private static ModbusServiceFieldAlarm alarm(String compare, Double threshold, String state) {
        ModbusServiceFieldAlarm alarm = new ModbusServiceFieldAlarm();
        alarm.id = compare + threshold + "/" + state;
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

    /** 改标识：用来表达「还是那条规则，只是配置改了」，或者「这是另一条规则」。 */
    private static ModbusServiceFieldAlarm withId(ModbusServiceFieldAlarm alarm, String id) {
        alarm.id = id;
        return alarm;
    }

    /** 改级别（选举看它）。 */
    private static ModbusServiceFieldAlarm level(ModbusServiceFieldAlarm alarm, String level) {
        alarm.level = level;
        return alarm;
    }

    private static Map<String, Object> sample(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }

    /** 开着的行：{@code 出值名, 那条规则} 成对给；不给就是「一条开着的都没有」。 */
    private static Map<String, String> open(Object... pairs) {
        Map<String, String> open = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            open.put((String) pairs[i], ((ModbusServiceFieldAlarm) pairs[i + 1]).id);
        }
        return open;
    }
}
