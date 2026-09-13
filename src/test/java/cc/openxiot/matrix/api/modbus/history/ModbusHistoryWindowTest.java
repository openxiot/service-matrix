package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.api.modbus.history.ModbusHistoryWindow.Bucket;
import cc.openxiot.matrix.api.modbus.history.ModbusHistoryWindow.Window;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistory;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusHistoryWindow} 的整形规则（Modbus-History.md 第五节）。
 *
 * <p>纯函数，不需要数据库：这是读侧这套折算唯一能跑起来的地方（{@code @QuarkusTest} 要连 Mongo）。
 * 降采样之所以没做成聚合管道，就是为了能在这里把每个边界掰开断言。</p>
 */
class ModbusHistoryWindowTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    // ===== 原始点 =====

    @Test
    void smallRangeReturnsRawSamplesUntouched() {
        List<ModbusServiceHistory> rows = List.of(sample(T0, 1), sample(T0 + MINUTE, 2));
        ModbusServiceHistory carryIn = sample(T0 - HOUR, 0);

        Window window = ModbusHistoryWindow.of(carryIn, rows, T0, T0 + HOUR, 500);

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
        Window window = ModbusHistoryWindow.of(sample(T0 - HOUR, 42), List.of(), T0, T0 + HOUR, 500);

        assertFalse(window.downsampled());
        assertTrue(window.samples().isEmpty());
        assertEquals(42, window.carryIn().value);
    }

    @Test
    void sampleCountAtTheLimitStaysRaw() {
        // 边界：正好等于 maxPoints 时不折叠（判定是「超过才折」）
        List<ModbusServiceHistory> rows = List.of(sample(T0, 1), sample(T0 + MINUTE, 2));

        assertFalse(ModbusHistoryWindow.of(null, rows, T0, T0 + HOUR, 2).downsampled());
        assertTrue(ModbusHistoryWindow.of(null, rows, T0, T0 + HOUR, 1).downsampled());
    }

    // ===== 分桶 =====

    @Test
    void bucketsCoverTheRangeAndSkipEmptyOnes() {
        // 窗口 10s 折成 5 桶（每桶 2s），样本只在头尾两桶里：
        // 中间那些桶一条样本都没有，不该出点 —— 值没变的时段由前后两点之间的直线表示，
        // 而且因为中间没有样本，「值没变」这件事让那条直线是精确的
        Window window = ModbusHistoryWindow.of(null,
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
        Window window = ModbusHistoryWindow.of(null,
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
        Window window = ModbusHistoryWindow.of(null,
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
        List<ModbusServiceHistory> rows = List.of(sample(T0 + 30 * MINUTE, 100), sample(T0 + 90 * MINUTE, 60));
        long to = T0 + 2 * HOUR;

        Bucket withCarryIn = ModbusHistoryWindow.of(sample(T0 - HOUR, 50), rows, T0, to, 1).buckets().get(0);
        assertEquals(50, withCarryIn.first());
        assertEquals(60, withCarryIn.last());
        assertEquals(50.0, withCarryIn.min());
        // (50×30 + 100×60 + 60×30) / 120 分钟
        assertEquals(77.5, withCarryIn.avg());

        // 没有 carry-in 时区间开头那段是空的（值未知），均值只覆盖有值的 90 分钟：
        // 这也说明「读出来对不对」完全取决于有没有把这条带进来
        Bucket without = ModbusHistoryWindow.of(null, rows, T0, to, 1).buckets().get(0);
        assertEquals(100, without.first());
        assertEquals(60.0, without.min());
        assertEquals((100 * 60 + 60 * 30) / 90.0, without.avg());
    }

    @Test
    void averageStaysBetweenMinAndMax() {
        // min / max / avg 同源（同一段阶梯函数），所以前端画 min/max 带 + 均值线时线不会穿出带子。
        // 这是这套折算最容易破的约束 —— 一旦均值改成按条数平均，这里立刻挂。
        // 样本跨三个桶（每小时折 3 桶 × 20 分钟），每桶里都有不止一个值
        Window window = ModbusHistoryWindow.of(sample(T0 - HOUR, 50),
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
        Window window = ModbusHistoryWindow.of(null,
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
        Window window = ModbusHistoryWindow.of(null,
                List.of(sample(T0, "ON"), sample(T0 + 500, 3)), T0, T0 + 1000, 1);

        Bucket bucket = window.buckets().get(0);
        assertEquals("ON", bucket.first());
        assertEquals(3, bucket.last());
        assertEquals(3.0, bucket.avg());
    }

    // ===== 夹具 =====

    /** 只填窗口会用到的两个字段：点位身份（serviceId / functionIndex / field）由仓储的查询决定。 */
    private static ModbusServiceHistory sample(long at, Object value) {
        ModbusServiceHistory row = new ModbusServiceHistory();
        row.at = new Date(at);
        row.value = value;
        return row;
    }
}
