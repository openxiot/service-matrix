package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.api.modbus.history.ModbusHistoryPolicy.Decision;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusHistoryPolicy} 的落库规则（Modbus-History.md 第二节）。
 *
 * <p>纯函数，不需要数据库：这是这套规则唯一能跑起来的地方（{@code @QuarkusTest} 要连 Mongo）。</p>
 */
class ModbusHistoryPolicyTest {

    /** 保活周期：10 分钟。 */
    private static final long KEEP_ALIVE = 600_000L;
    private static final Date NOW = new Date(1_700_000_000_000L);

    // ===== 变位 =====

    @Test
    void firstSampleWritesEveryField() {
        Decision decision = decide(null, null, sample("温度", 61.5, "开关", "ON"), 0);

        assertEquals(List.of("温度", "开关"), decision.changedFields());
        assertTrue(decision.keepaliveFields().isEmpty());
        assertTrue(decision.hasRows());
    }

    @Test
    void unchangedWritesNothing() {
        // 95% 的采集走这条：一个字段都不落，调用方一次数据库操作都不做
        Decision decision = decide(sample("温度", 61.5, "开关", "ON"), ago(1000),
                sample("温度", 61.5, "开关", "ON"), 0);

        assertFalse(decision.hasRows());
    }

    @Test
    void changedFieldWritesOnlyThatField() {
        Decision decision = decide(sample("温度", 61.5, "开关", "ON"), ago(1000),
                sample("温度", 62.5, "开关", "ON"), 0);

        assertEquals(List.of("温度"), decision.changedFields());
        assertTrue(decision.keepaliveFields().isEmpty());
    }

    @Test
    void missingBaselineFieldCountsAsChange() {
        // 定义里新增了一个字段：它在基准里不存在，必须落下第一条，否则这个点位永远是空的
        Decision decision = decide(sample("温度", 61.5), ago(1000),
                sample("温度", 61.5, "新增", 1), 0);

        assertEquals(List.of("新增"), decision.changedFields());
    }

    @Test
    void droppedFieldIsIgnored() {
        // 定义里删了字段：本次采集没有它，就不该为它落任何东西（影子会在写入时被整体换掉）
        Decision decision = decide(sample("温度", 61.5, "已删除", 1), ago(1000),
                sample("温度", 61.5), 0);

        assertFalse(decision.hasRows());
    }

    /**
     * 影子从库里读回来可能是 Integer / Long / Double，与本次采集算出来的类型未必一致：
     * 按类型比会把「没变」误判成「变了」，压缩率就没了。
     */
    @Test
    void numbersCompareByValueAcrossTypes() {
        assertFalse(decide(sample("n", 5), ago(1000), sample("n", 5L), 0).hasRows());
        assertFalse(decide(sample("n", 5L), ago(1000), sample("n", 5.0d), 0).hasRows());
        assertFalse(decide(sample("n", 61.5f), ago(1000), sample("n", 61.5d), 0).hasRows());
    }

    // ===== 死区 =====

    @Test
    void deadbandSwallowsSmallDrift() {
        // 模拟量的常态：61.5 → 61.55 这种漂移不该每次都落库
        assertFalse(decide(sample("温度", 61.5), ago(1000), sample("温度", 61.55), 0.1).hasRows());
        assertTrue(decide(sample("温度", 61.5), ago(1000), sample("温度", 61.55), 0).hasRows());

        // 越过死区照落
        assertEquals(List.of("温度"),
                decide(sample("温度", 61.5), ago(1000), sample("温度", 61.7), 0.1).changedFields());
    }

    @Test
    void deadbandDoesNotApplyToStrings() {
        // value-list 的 description、string 格式：只能精确比，死区再大也不该把 ON → OFF 吞掉
        assertEquals(List.of("开关"),
                decide(sample("开关", "ON"), ago(1000), sample("开关", "OFF"), 100).changedFields());
    }

    // ===== 保活打点 =====

    @Test
    void keepAliveWritesUnchangedFields() {
        // 值没变但挂太久了：落一条锚点，否则「值稳定」和「采集挂了」在数据里长得一模一样
        Decision decision = decide(sample("温度", 61.5, "开关", "ON"), ago(KEEP_ALIVE + 1),
                sample("温度", 61.5, "开关", "ON"), 0);

        assertTrue(decision.changedFields().isEmpty());
        assertEquals(List.of("温度", "开关"), decision.keepaliveFields());
        assertTrue(decision.hasRows());
    }

    @Test
    void keepAliveDoesNotDuplicateChangedFields() {
        Decision decision = decide(sample("温度", 61.5, "开关", "ON"), ago(KEEP_ALIVE + 1),
                sample("温度", 62.5, "开关", "ON"), 0);

        assertEquals(List.of("温度"), decision.changedFields());
        assertEquals(List.of("开关"), decision.keepaliveFields());
    }

    @Test
    void keepAliveCanBeDisabled() {
        // keepAliveMillis <= 0 = 关闭保活：只按变位 / 死区落库
        Decision decision = ModbusHistoryPolicy.decide(sample("温度", 61.5), ago(KEEP_ALIVE * 100),
                sample("温度", 61.5), NOW, 0, 0);

        assertFalse(decision.hasRows());
    }

    @Test
    void noShadowMeansKeepAliveIsDue() {
        // 影子缺失（首次采集）时时间基准是空的，不该因此抛错
        Decision decision = decide(null, null, sample("温度", 61.5), 0);

        assertEquals(List.of("温度"), decision.changedFields());
    }

    // ===== 边界 =====

    @Test
    void emptyValuesIsNoOp() {
        assertFalse(decide(sample("温度", 61.5), null, Map.of(), 0).hasRows());
        assertFalse(ModbusHistoryPolicy.decide(null, null, null, NOW, 0, KEEP_ALIVE).hasRows());
    }

    // ===== 夹具 =====

    private static Decision decide(Map<String, Object> baseline, Date recordedAt,
                                   Map<String, Object> values, double deadband) {
        return ModbusHistoryPolicy.decide(baseline, recordedAt, values, NOW, deadband, KEEP_ALIVE);
    }

    private static Date ago(long millis) {
        return new Date(NOW.getTime() - millis);
    }

    /** 用 LinkedHashMap 保留书写顺序，断言才能按列表比（{@code Map.of} 的迭代顺序是未定义的）。 */
    private static Map<String, Object> sample(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
