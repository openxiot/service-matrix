package cc.openxiot.matrix.api.modbus.history;

import java.util.ArrayList;
import java.util.List;

/**
 * 区间查询的结果整形：carry-in 点 + 采样点，点要么是原始样本、要么是降采样后的桶
 * （规则见 Modbus-History.md 第五节）。
 *
 * <p>纯函数、不碰数据库：与 {@link ModbusHistoryPolicy} 同风格 —— 读侧的逻辑只有放在这里才跑得了单测
 * （{@code @QuarkusTest} 要连 Mongo，本仓库跑不起来）。也正因为要能单测，降采样没做成 Mongo 聚合管道：
 * 那样算子支持只能在线上试错，而这套东西的正确性恰恰全在「怎么折算」上。</p>
 *
 * <h3>carry-in</h3>
 * <p>区间起点之前最近的那一条**必须一起返回**：变化点存储里，某点最后一次变化可能在 {@code from} 之前，
 * 不带它前端就不知道 {@code from} 时刻的值，曲线从起点开始就是空白
 * —— 这是变化点存储最经典的第一坑。它由调用方（{@code ModbusRegistryHistoryService}）单独查一条，
 * 不混进 {@code samples}：它不是这个区间的数据，是区间起点的**状态**。</p>
 *
 * <h3>一个桶描述的是「这段时间里值是怎么走的」</h3>
 * <p>这份数据不是均匀采样序列：相邻两条样本之间的时间差，就是前一条的值持续了多久。
 * 所以桶里的每个量都按**阶梯函数**算 —— 每条样本从自己的 {@code at} 起一直有效到下一条样本的
 * {@code at}（最后一条延到窗口末尾），落在桶内的时间算数：</p>
 *
 * <ul>
 *   <li>{@code first} / {@code last}：桶的起止时刻的值（不是「桶内第一条/最后一条样本」——
 *       那会在两次变化之间把一个值提前或推后，桶内一条样本都没有时时更是完全算不出来）。</li>
 *   <li>{@code min} / {@code max}：同一段阶梯函数上的最小、最大值 —— 也就是「这段时间里这个点位
 *       到过的最值和最低值」，与 min/max 带在时间轴上的本义一致。</li>
 *   <li>{@code avg}：同一段阶梯函数上的**时间加权**均值。按条数平均会差一个量级：
 *       一个点 23 小时是 0、1 小时是 100，按条数平均得 50，按时间加权是 4.17。
 *       min / max 与它同源（只是多收了一个零时长的端点样本，见下），所以
 *       {@code min ≤ avg ≤ max} 恒成立 —— 前端画 min/max 带 + 均值线，线不会穿出带子。</li>
 *   <li>{@code count}：桶内的**样本条数**（阶梯函数与它无关），只用来决定这个桶出不出点。</li>
 * </ul>
 *
 * <p>区间是**闭**的：时刻正好等于 {@code to} 的那条样本算在最后一桶里（{@code count} 含它）。
 * 它在该桶里持续时间为 0，因此只进 {@code last} 与 min/max（那一刻的值确实到过），
 * 不改变加权均值 —— 否则「窗口右端刚好有一次变化」时，最后一桶的末值会比原始点的末值旧一步，
 * 降采样前后对不上。</p>
 *
 * <p>carry-in 也参与这段阶梯函数：它正是区间开头那一刻的状态（因此也是第一个桶的 {@code first}）。
 * 不带上它，第一个桶的均值会漏掉区间开头那一段。</p>
 *
 * <p>非数值点位（{@code value-list} 的 description、{@code string}）只有 {@code first} / {@code last}：
 * 它们没有大小可言，{@code min} / {@code max} / {@code avg} 为 null（与 {@code ModbusHistoryPolicy}
 * 「非数值精确比较」同一口径）。</p>
 *
 * <p>空桶不出点：值没变的时段由前后两点之间的直线表示 —— 那些时刻本来就没有样本，
 * 补一堆空桶既不增加信息，又正好把降采样省下的流量还回去。</p>
 */
public final class ModbusHistoryWindow {

    private ModbusHistoryWindow() {
    }

    /**
     * 一个降采样桶：{@code [at, until)} 内样本的折叠结果。{@code at} 与原始点的时刻同义，都是横轴坐标。
     */
    public record Bucket(
            long at,
            long until,
            int count,
            Object first,
            Object last,
            Double min,
            Double max,
            Double avg
    ) {
    }

    /**
     * 整形结果。
     *
     * @param carryIn     区间起点之前最近的一条样本（没有则为 null）；两种模式下都有
     * @param samples     区间内的原始样本（{@code downsampled} 为 false 时才是要返回的点）
     * @param buckets     降采样后的桶，按时间升序、已去掉空桶（{@code downsampled} 为 true 时才是要返回的点）
     * @param downsampled true = 用 {@code buckets}，false = 用 {@code samples}
     */
    public record Window(
            ModbusHistorySample carryIn,
            List<ModbusHistorySample> samples,
            List<Bucket> buckets,
            boolean downsampled
    ) {
    }

    /**
     * 阶梯函数的一段：从 {@code at} 起，值一直是 {@code value}，直到下一段的 {@code at}。
     */
    private record Step(long at, Object value) {
    }

    /**
     * 整形一次区间查询的结果。{@code samples} 必须是**区间内、按 {@code at} 升序**的全部样本
     * （升序由仓储的查询保证，这里不再排序 —— 排序口径只该有一处）。
     *
     * @param carryIn   区间起点之前最近的一条样本，没有传 null
     * @param samples   区间 [from, to] 内的样本，升序
     * @param from      区间起点（epoch 毫秒）
     * @param to        区间终点（epoch 毫秒），必须大于 from
     * @param maxPoints 返回的点数上限；样本数不超过它时返回原始点，超过则降采样
     */
    public static Window of(
            ModbusHistorySample carryIn,
            List<ModbusHistorySample> samples,
            long from,
            long to,
            int maxPoints
    ) {
        List<ModbusHistorySample> rows = samples == null ? List.of() : samples;
        // 点不多就直接给原始点：这一段本来就是「每个拐点都要看见」的精细区间，折叠只会让前端丢掉拐点
        if (rows.size() <= maxPoints) {
            return new Window(carryIn, rows, List.of(), false);
        }

        long bucketMillis = Math.max(1L, ceilDiv(to - from, maxPoints));
        int bucketCount = (int) Math.max(1L, Math.min(maxPoints, ceilDiv(to - from, bucketMillis)));

        int[] count = new int[bucketCount];
        for (ModbusHistorySample row : rows) {
            if (row != null && row.at != null) {
                count[bucketOf(row.at.getTime(), from, bucketMillis, bucketCount)]++;
            }
        }

        Fold fold = fold(steps(carryIn, rows), from, to, bucketMillis, bucketCount);
        List<Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < bucketCount; i++) {
            if (count[i] == 0) {
                continue;
            }
            long start = from + (long) i * bucketMillis;
            buckets.add(new Bucket(start, Math.min(start + bucketMillis, to), count[i],
                    fold.first[i], fold.last[i], fold.min[i], fold.max[i],
                    fold.numericMillis[i] > 0 ? fold.weighted[i] / fold.numericMillis[i] : null));
        }
        return new Window(carryIn, rows, List.copyOf(buckets), true);
    }

    /**
     * 阶梯函数各段折叠到各桶的结果：桶的四个量 + 累加用的中间量。
     */
    private static final class Fold {

        private final Object[] first;
        private final Object[] last;
        private final Double[] min;
        private final Double[] max;
        private final double[] weighted;
        private final long[] numericMillis;
        /**
         * 该桶是否已被阶梯函数覆盖过：用来判「这是覆盖到它的第一段」。不能用 first[b] != null —— 值本身可能是 null。
         */
        private final boolean[] covered;

        private Fold(int bucketCount) {
            first = new Object[bucketCount];
            last = new Object[bucketCount];
            min = new Double[bucketCount];
            max = new Double[bucketCount];
            weighted = new double[bucketCount];
            numericMillis = new long[bucketCount];
            covered = new boolean[bucketCount];
        }

        /**
         * 把「值 {@code value} 在桶 {@code bucket} 里持续了 {@code millis}」记进桶。
         * 段是按时间升序走的，所以第一次记到某桶的那一段，值就是该桶起点的状态。
         */
        private void add(int bucket, Object value, long millis) {
            if (!covered[bucket]) {
                covered[bucket] = true;
                first[bucket] = value;
            }
            last[bucket] = value;
            if (!(value instanceof Number number)) {
                return;
            }
            double rate = number.doubleValue();
            if (millis > 0) {
                weighted[bucket] += rate * millis;
                numericMillis[bucket] += millis;
            }
            min[bucket] = min[bucket] == null ? rate : Math.min(min[bucket], rate);
            max[bucket] = max[bucket] == null ? rate : Math.max(max[bucket], rate);
        }
    }

    /**
     * 阶梯函数的各段：carry-in 在最前（它的起点在窗口之前，会被裁到窗口内）。
     */
    private static List<Step> steps(ModbusHistorySample carryIn, List<ModbusHistorySample> rows) {
        List<Step> steps = new ArrayList<>(rows.size() + 1);
        if (carryIn != null && carryIn.at != null) {
            steps.add(new Step(carryIn.at.getTime(), carryIn.value));
        }
        for (ModbusHistorySample row : rows) {
            if (row != null && row.at != null) {
                steps.add(new Step(row.at.getTime(), row.value));
            }
        }
        return steps;
    }

    /**
     * 把每一段按其值与和桶的重叠长度累加进桶里。
     *
     * <p>代价是 O(桶数 + 段数)：段之间互不重叠且铺满窗口，所以所有段跨过的桶数之和也是这个量级，
     * 不会退化成「段数 × 桶数」（那才是 20 万行 × 2000 桶的量级）。</p>
     */
    private static Fold fold(List<Step> steps, long from, long to, long bucketMillis, int bucketCount) {
        Fold fold = new Fold(bucketCount);
        for (int i = 0; i < steps.size(); i++) {
            // 一段从自己的时刻起，到下一条样本的时刻为止；最后一段延到窗口末尾
            long start = Math.max(steps.get(i).at(), from);
            long end = Math.min(i + 1 < steps.size() ? steps.get(i + 1).at() : to, to);
            if (end < start) {
                continue;
            }
            Object value = steps.get(i).value();
            int startBucket = bucketOf(start, from, bucketMillis, bucketCount);
            if (end == start) {
                // 零时长的一段：只可能是时刻正好等于 to 的那条样本（或同刻重复的样本）。
                // 它仍然是那一刻的值，所以进该桶的 last 与极值，但不进加权均值
                fold.add(startBucket, value, 0);
                continue;
            }
            for (int bucket = startBucket; bucket <= bucketOf(end - 1, from, bucketMillis, bucketCount); bucket++) {
                long bucketStart = from + (long) bucket * bucketMillis;
                long bucketEnd = Math.min(bucketStart + bucketMillis, to);
                long overlap = Math.min(end, bucketEnd) - Math.max(start, bucketStart);
                if (overlap > 0) {
                    fold.add(bucket, value, overlap);
                }
            }
        }
        return fold;
    }

    private static int bucketOf(long at, long from, long bucketMillis, int bucketCount) {
        long index = (at - from) / bucketMillis;
        if (index < 0) {
            return 0;
        }
        // at == to 时正好落在最后一个桶的右边界外，收进最后一桶（区间是闭的）
        return (int) Math.min(index, bucketCount - 1);
    }

    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
