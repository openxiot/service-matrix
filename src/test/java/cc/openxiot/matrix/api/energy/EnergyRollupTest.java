package cc.openxiot.matrix.api.energy;

import cc.openxiot.matrix.db.energy.EnergyStat;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link EnergyRollup}：粒度选择、本地时区对齐、密集零填充、小时桶汇总规则。
 *
 * <p>时区固定 {@code Asia/Shanghai}：桶边界必须落在本地零点，UTC 零点会让「今天」差 8 小时。</p>
 */
class EnergyRollupTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2024-01-01 00:00 +08:00 */
    private static final long JAN1 = 1_704_038_400_000L;
    private static final long HOUR = EnergyRollup.HOUR;
    private static final long DAY = EnergyRollup.DAY;

    @Test
    void granularityIsChosenBySpan() {
        assertEquals(EnergyGranularity.HOUR, EnergyRollup.granularityFor(JAN1, JAN1 + 48 * HOUR));
        assertEquals(EnergyGranularity.DAY, EnergyRollup.granularityFor(JAN1, JAN1 + 48 * HOUR + 1));
        assertEquals(EnergyGranularity.DAY, EnergyRollup.granularityFor(JAN1, JAN1 + 62 * DAY));
        assertEquals(EnergyGranularity.MONTH, EnergyRollup.granularityFor(JAN1, JAN1 + 62 * DAY + 1));
    }

    @Test
    void bucketStartsAtLocalMidnight() {
        long noon = JAN1 + 12 * HOUR;
        assertEquals(JAN1, EnergyRollup.bucketAt(noon, EnergyGranularity.DAY, ZONE));
        assertEquals(JAN1, EnergyRollup.bucketAt(noon, EnergyGranularity.MONTH, ZONE));
        assertEquals(JAN1 + 12 * HOUR, EnergyRollup.bucketAt(noon, EnergyGranularity.HOUR, ZONE));
    }

    @Test
    void startsAreDenseAndExcludeTheEndBucket() {
        List<Long> starts = EnergyRollup.starts(JAN1, JAN1 + 2 * DAY, EnergyGranularity.DAY, ZONE);
        assertEquals(List.of(JAN1, JAN1 + DAY), starts);
    }

    @Test
    void rollupFillsEmptyBucketsWithZero() {
        EnergyStat stat = stat(JAN1 + 10 * HOUR, 5.0);
        List<EnergyBucket> buckets = EnergyRollup.rollup(List.of(stat), JAN1, JAN1 + 2 * DAY,
                EnergyGranularity.DAY, ZONE);
        assertEquals(2, buckets.size());
        assertEquals(5.0, buckets.get(0).energy(), 1e-9);
        assertEquals(0.0, buckets.get(1).energy(), 1e-9);
        assertEquals(0, buckets.get(1).samples());
    }

    @Test
    void rollupSumsEnergyButKeepsLastCapacity() {
        EnergyStat morning = stat(JAN1 + 8 * HOUR, 2.0);
        morning.capacity = 100.0;
        morning.socSum = 120;
        morning.socCount = 2; // 平均 60
        morning.socMin = 55;
        morning.socMax = 65;
        EnergyStat evening = stat(JAN1 + 18 * HOUR, 3.0);
        evening.capacity = 120.0;
        evening.socSum = 50;
        evening.socCount = 1; // 平均 50
        evening.socMin = 40;
        evening.socMax = 50;

        EnergyBucket day = EnergyRollup.rollup(List.of(morning, evening), JAN1, JAN1 + DAY,
                EnergyGranularity.DAY, ZONE).getFirst();

        assertEquals(5.0, day.energy(), 1e-9);
        assertEquals(120.0, day.capacity(), 1e-9); // 最后值，不是求和
        assertEquals(170.0 / 3, day.socAvg(), 1e-9);
        assertEquals(40.0, day.socMin(), 1e-9);
        assertEquals(65.0, day.socMax(), 1e-9);
        assertEquals(2, day.samples());
    }

    @Test
    void mergingConfigsSumsCapacityAndCycles() {
        EnergyBucket a = new EnergyBucket(0, 1, 0, 0, 0, 0, 0, 0, null, null,
                0, 0, null, null, 100.0, 0, 0, 0, 0, 2.0, 1);
        EnergyBucket b = new EnergyBucket(0, 2, 0, 0, 0, 0, 0, 0, null, null,
                0, 0, null, null, 200.0, 0, 0, 0, 0, 3.0, 1);
        EnergyBucket merged = a.merge(b);
        assertEquals(3.0, merged.energy(), 1e-9);
        assertEquals(300.0, merged.capacity(), 1e-9); // 两台电池 = 总容量
        assertEquals(5.0, merged.cycles(), 1e-9);
    }

    @Test
    void mergingAverageUsesSumAndCountNotAverageOfAverages() {
        EnergyBucket many = new EnergyBucket(0, 0, 0, 0, 0, 0, 0, 0, null, null,
                1000, 10, null, null, null, 0, 0, 0, 0, null, 0); // 平均 100
        EnergyBucket one = new EnergyBucket(0, 0, 0, 0, 0, 0, 0, 0, null, null,
                10, 1, null, null, null, 0, 0, 0, 0, null, 0); // 平均 10
        assertEquals((1000.0 + 10) / 11, many.merge(one).socAvg(), 1e-9);
    }

    @Test
    void summarizeEmptyRangeHasNoState() {
        EnergyBucket total = EnergyRollup.summarize(List.of());
        assertNull(total.socAvg());
        assertNull(total.powerAvg());
        assertEquals(0.0, total.energy(), 1e-9);
    }

    private static EnergyStat stat(long bucketAt, double energy) {
        EnergyStat stat = new EnergyStat();
        stat.bucketAt = bucketAt;
        stat.energy = energy;
        stat.samples = 1;
        return stat;
    }
}
