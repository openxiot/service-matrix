package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.api.modbus.history.ModbusRegistryHistoryWindow.Bucket;
import cc.openxiot.matrix.api.modbus.history.ModbusRegistryHistoryWindow.Window;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusRegistryHistoryWindow} 的整形规则（Modbus-History.md 第五节）。
 *
 * <p>纯函数，不需要数据库：这是读侧这套折算唯一能跑起来的地方（{@code @QuarkusTest} 要连 Mongo）。
 * 降采样之所以没做成聚合管道，就是为了能在这里把每个边界掰开断言。</p>
 */
class ModbusRegistryHistoryWindowTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    // ===== 原始点 =====

    @Test
    void smallRangeReturnsRawSamplesUntouched() {
        List<ModbusRegistryHistorySample> rows = List.of(sample(T0, 1), sample(T0 + MINUTE, 2));
        ModbusRegistryHistorySample carryIn = sample(T0 - HOUR, 0);

        Window window = ModbusRegistryHistoryWindow.of(carryIn, rows, T0, T0 + HOUR, 500);

        assertFalse(window.downsampled());
        assertSame(rows, window.samples());
        assertTrue(window.buckets().isEmpty());
        // carry-in 两种模式下都给：它是区间起点的状态，不是这个区间的数据
        assertSame(carryIn, window.carryIn());
    }

    @Test
    void noSamplesStillCarriesTheStateIn() {
        // 这段时间值一直没变、也没到保活点：区间内一条样本都没有。
        // 不是错误 —— 前端拿 carry-in 画一条直线就对了
        Window window = ModbusRegistryHistoryWindow.of(sample(T0 - HOUR, 42), List.of(), T0, T0 + HOUR, 500);

        assertFalse(window.downsampled());
        assertTrue(window.samples().isEmpty());
        assertEquals(42, window.carryIn().value);
    }

    @Test
    void sampleCountAtTheLimitStaysRaw() {
        // 边界：正好等于 maxPoints 时不折叠（判定是「超过才折」）
        List<ModbusRegistryHistorySample> rows = List.of(sample(T0, 1), sample(T0 + MINUTE, 2));

        assertFalse(ModbusRegistryHistoryWindow.of(null, rows, T0, T0 + HOUR, 2).downsampled());
        assertTrue(ModbusRegistryHistoryWindow.of(null, rows, T0, T0 + HOUR, 1).downsampled());
    }

    // ===== 分桶 =====

    @Test
    void bucketsCoverTheRangeAndSkipEmptyOnes() {
        // 窗口 10s 折成 5 桶（每桶 2s），样本只在头尾两桶里：
        // 中间那些桶一条样本都没有，不该出点 —— 值没变的时段由前后两点之间的直线表示，
        // 而且因为中间没有样本，「值没变」这件事让那条直线是精确的
        Window window = ModbusRegistryHistoryWindow.of(null,
                List.of(sample(T0, 1), sample(T0 + 100, 2), sample(T0 + 200, 3), sample(T0 + 300, 4),
                        sample(T0 + 9000, 5), sample(T0 + 9100, 6)),
                T0, T0 + 10_000, 5);

        assertTrue(window.downsampled());
        assertEquals(2, window.buckets().size());

        // 头一桶：桶内 4 条样本，4 从 300ms 一直持续到本桶结束
        Bucket head = window.buckets().get(0);
        assertEquals(T0, head.at());
        assertEquals(T0 + 2000, head.until());
        assertEquals(4, head.count());
        assertEquals(1, head.first());
        assertEquals(4, head.last());
        assertEquals(1.0, head.min());
        assertEquals(4.0, head.max());
        assertEquals((1 * 100 + 2 * 100 + 3 * 100 + 4 * 1700) / 2000.0, head.avg());

        // 尾桶：桶内只有 2 条样本，但它的首值是**桶起点那一刻的状态**（4，来自 7 秒前的那条样本），
        // 不是「桶内第一条样本」（5）—— 后者会把那次变化提前 1 秒。
        // 均值同理按持续时间加权：4 占 1s、5 占 100ms、6 占 900ms
        Bucket tail = window.buckets().get(1);
        assertEquals(T0 + 8000, tail.at());
        assertEquals(T0 + 10_000, tail.until());
        assertEquals(2, tail.count());
        assertEquals(4, tail.first());
        assertEquals(6, tail.last());
        assertEquals(4.0, tail.min());
        assertEquals(6.0, tail.max());
        assertEquals((4 * 1000 + 5 * 100 + 6 * 900) / 2000.0, tail.avg());
    }

    @Test
    void bucketBoundariesAreInclusiveAtTheWindowEnd() {
        // 恰好落在 to 上的那条样本算在最后一桶里（区间是闭的）。
        // 它在该桶里持续时间为 0：进 last 与极值（那一刻的值确实到过），但不进加权均值
        Window window = ModbusRegistryHistoryWindow.of(null,
                List.of(sample(T0, 1), sample(T0 + 500, 2), sample(T0 + 1000, 3)), T0, T0 + 1000, 2);

        assertEquals(2, window.buckets().size());
        Bucket last = window.buckets().get(1);
        assertEquals(T0 + 500, last.at());
        assertEquals(T0 + 1000, last.until());
        assertEquals(2, last.count());
        // 末值与原始点最后一个值一致：降采样前后对得上，前端不会看到「末值比原始数据旧一步」
        assertEquals(3, last.last());
        assertEquals(2.0, last.avg());
        assertEquals(3.0, last.max());
    }

    // ===== 时间加权 =====

    @Test
    void averageIsWeightedByTimeNotByRowCount() {
        // Modbus-History.md 里那个例子：23 小时是 0、1 小时是 100。
        // 按条数平均得 50（差一个量级），按时间加权是 4.17 —— 这才是这条曲线真实的平均水平
        Window window = ModbusRegistryHistoryWindow.of(null,
                List.of(sample(T0, 100), sample(T0 + HOUR, 0)), T0, T0 + 24 * HOUR, 1);

        Bucket bucket = window.buckets().get(0);
        assertEquals(100, bucket.first());
        assertEquals(0, bucket.last());
        assertEquals(0.0, bucket.min());
        assertEquals(100.0, bucket.max());
        assertEquals(100.0 * HOUR / (24 * HOUR), bucket.avg(), 1e-9);
    }

    @Test
    void carryInJoinsTheStepFunction() {
        // 区间起点之前最近的那条样本，是区间开头那一刻的状态：不带它，第一个桶的均值会漏掉开头那一段
        List<ModbusRegistryHistorySample> rows = List.of(sample(T0 + 30 * MINUTE, 100), sample(T0 + 90 * MINUTE, 60));
        long to = T0 + 2 * HOUR;

        Bucket withCarryIn = ModbusRegistryHistoryWindow.of(sample(T0 - HOUR, 50), rows, T0, to, 1).buckets().get(0);
        assertEquals(50, withCarryIn.first());
        assertEquals(60, withCarryIn.last());
        assertEquals(50.0, withCarryIn.min());
        // (50×30 + 100×60 + 60×30) / 120 分钟
        assertEquals(77.5, withCarryIn.avg());

        // 没有 carry-in 时区间开头那段是空的（值未知），均值只覆盖有值的 90 分钟：
        // 这也说明「读出来对不对」完全取决于有没有把这条带进来
        Bucket without = ModbusRegistryHistoryWindow.of(null, rows, T0, to, 1).buckets().get(0);
        assertEquals(100, without.first());
        assertEquals(60.0, without.min());
        assertEquals((100 * 60 + 60 * 30) / 90.0, without.avg());
    }

    @Test
    void averageStaysBetweenMinAndMax() {
        // min / max / avg 同源（同一段阶梯函数），所以前端画 min/max 带 + 均值线时线不会穿出带子。
        // 这是这套折算最容易破的约束 —— 一旦均值改成按条数平均，这里立刻挂。
        // 样本跨三个桶（每小时折 3 桶 × 20 分钟），每桶里都有不止一个值
        Window window = ModbusRegistryHistoryWindow.of(sample(T0 - HOUR, 50),
                List.of(sample(T0 + MINUTE, 100), sample(T0 + 21 * MINUTE, 20),
                        sample(T0 + 41 * MINUTE, 80), sample(T0 + 59 * MINUTE, 3)),
                T0, T0 + HOUR, 3);

        assertEquals(3, window.buckets().size());
        for (Bucket bucket : window.buckets()) {
            assertTrue(bucket.min() <= bucket.avg(), "min ≤ avg: " + bucket);
            assertTrue(bucket.avg() <= bucket.max(), "avg ≤ max: " + bucket);
        }
    }

    // ===== 非数值 =====

    @Test
    void nonNumericValuesHaveNoStatistics() {
        // value-list 的 description、string 格式：没有大小可言，只有首末值
        Window window = ModbusRegistryHistoryWindow.of(null,
                List.of(sample(T0, "ON"), sample(T0 + 500, "OFF")), T0, T0 + 1000, 1);

        Bucket bucket = window.buckets().get(0);
        assertEquals(2, bucket.count());
        assertEquals("ON", bucket.first());
        assertEquals("OFF", bucket.last());
        assertNull(bucket.min());
        assertNull(bucket.max());
        assertNull(bucket.avg());
    }

    @Test
    void statisticsCoverTheNumericStretchesOnly() {
        // 同一个桶里先是非数值、后是数值：统计只算数值那一段的持续时间
        Window window = ModbusRegistryHistoryWindow.of(null,
                List.of(sample(T0, "ON"), sample(T0 + 500, 3)), T0, T0 + 1000, 1);

        Bucket bucket = window.buckets().get(0);
        assertEquals("ON", bucket.first());
        assertEquals(3, bucket.last());
        assertEquals(3.0, bucket.avg());
    }

    // ===== 增量（ofDeltas） =====

    @Test
    void deltasStartFromCarryIn() {
        // carry-in 是窗口起点的值，正好是首点的「上次」：不拿它当基准，第一条增量就丢了
        Window window = ModbusRegistryHistoryWindow.ofDeltas(sample(T0 - HOUR, 50),
                List.of(sample(T0 + MINUTE, 55), sample(T0 + 2 * MINUTE, 60)), T0, T0 + HOUR, 500);

        assertFalse(window.downsampled());
        assertEquals(2, window.samples().size());
        assertEquals(T0 + MINUTE, window.samples().get(0).at.getTime());
        assertEquals(5.0, window.samples().get(0).value);
        // 第二点的「上次」是窗口内的第一条（55），不是 carry-in：增量是相邻两次读数之差
        assertEquals(5.0, window.samples().get(1).value);

        // carry-in 已经折进首点，响应里不能再给：前端会以为还要再减一次
        assertNull(window.carryIn());
        assertTrue(window.buckets().isEmpty());
    }

    @Test
    void withoutCarryInTheFirstSampleHasNoDelta() {
        // 窗口之前没有样本：首个读数没有「上次」可减，不出点（0 会是一条假的「这段时间没变化」）
        Window window = ModbusRegistryHistoryWindow.ofDeltas(null,
                List.of(sample(T0 + MINUTE, 55), sample(T0 + 2 * MINUTE, 60)), T0, T0 + HOUR, 500);

        assertEquals(1, window.samples().size());
        assertEquals(5.0, window.samples().get(0).value);
    }

    @Test
    void negativeDeltaKeepsItsMomentAsABreak() {
        // 计数器回零：100 → 120 → 5 → 25。第二条的差是 −115，画出来是个深谷；
        // 要保留时刻、把值置空当断点，并且以 5 为新基准继续算 +20
        Window window = ModbusRegistryHistoryWindow.ofDeltas(sample(T0 - MINUTE, 100),
                List.of(sample(T0 + MINUTE, 120), sample(T0 + 2 * MINUTE, 5), sample(T0 + 3 * MINUTE, 25)),
                T0, T0 + HOUR, 500);

        List<ModbusRegistryHistorySample> points = window.samples();
        assertEquals(3, points.size());
        assertEquals(20.0, points.get(0).value);
        assertNull(points.get(1).value);
        assertEquals(T0 + 2 * MINUTE, points.get(1).at.getTime());
        assertEquals(20.0, points.get(2).value);
    }

    @Test
    void nonNumericSampleBreaksAndClearsTheBaseline() {
        // 字符串没有大小：这一刻留断点，基准清空；下一个数值样本重新建基准（不跨过它求差）
        Window window = ModbusRegistryHistoryWindow.ofDeltas(sample(T0 - MINUTE, 10),
                List.of(sample(T0 + MINUTE, "ON"), sample(T0 + 2 * MINUTE, 15), sample(T0 + 3 * MINUTE, 20)),
                T0, T0 + HOUR, 500);

        List<ModbusRegistryHistorySample> points = window.samples();
        assertEquals(2, points.size());
        assertNull(points.get(0).value);
        assertEquals(T0 + MINUTE, points.get(0).at.getTime());
        assertEquals(5.0, points.get(1).value);
    }

    @Test
    void downsampledDeltasAreSummedPerBucket() {
        // 4 个增量点折成 2 个桶：sum 才是「这段时间累计增加了多少」，
        // avg 是「每次采样的平均增量」—— 两个都出，前端按口径选
        Window window = ModbusRegistryHistoryWindow.ofDeltas(sample(T0 - MINUTE, 0),
                List.of(sample(T0 + 10_000, 1),
                        sample(T0 + 30_000, 3),
                        sample(T0 + 2 * MINUTE, 6),
                        sample(T0 + 2 * MINUTE + 30_000, 7)),
                T0, T0 + 4 * MINUTE, 2);

        assertTrue(window.downsampled());
        assertEquals(2, window.buckets().size());

        Bucket first = window.buckets().get(0);
        assertEquals(3.0, first.sum());
        assertEquals(1.5, first.avg());
        assertEquals(1.0, first.min());
        assertEquals(2.0, first.max());
        // 增量是求和出来的量，没有「桶起点的读数」可言：不让前端拿 first/last 当读数画
        assertNull(first.first());
        assertNull(first.last());

        Bucket second = window.buckets().get(1);
        assertEquals(4.0, second.sum());
        assertEquals(2.0, second.avg());
        assertEquals(1.0, second.min());
        assertEquals(3.0, second.max());
    }

    @Test
    void bucketSumSkipsBreaksButCountIncludesThem() {
        // 桶里 2 条点：+5 和一个负增量断点。sum 只算 +5（断点不是 0，而是「不知道」）
        Window window = ModbusRegistryHistoryWindow.ofDeltas(sample(T0 - MINUTE, 100),
                List.of(sample(T0 + 10_000, 105), sample(T0 + 20_000, 5)), T0, T0 + MINUTE, 1);

        Bucket bucket = window.buckets().get(0);
        assertEquals(2, bucket.count());
        assertEquals(5.0, bucket.sum());
        assertEquals(5.0, bucket.avg());
        assertEquals(5.0, bucket.min());
        assertEquals(5.0, bucket.max());
    }

    // ===== 夹具 =====

    /** 只填窗口会用到的两个字段；点位身份由数据源的查询决定，窗口不关心。 */
    private static ModbusRegistryHistorySample sample(long at, Object value) {
        return new ModbusRegistryHistorySample(new Date(at), value);
    }
}
