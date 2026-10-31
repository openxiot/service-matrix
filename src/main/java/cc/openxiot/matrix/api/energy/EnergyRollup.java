package cc.openxiot.matrix.api.energy;

import cc.openxiot.matrix.db.energy.stat.EnergyStat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小时桶 → 展示桶的汇总（纯函数，可单测）。
 *
 * <p>存储层永远是小时桶；这一层负责三件事：</p>
 *
 * <ol>
 *   <li><b>选粒度</b>：{@link #granularityFor} 按跨度自动选小时/天/月；</li>
 *   <li><b>对齐本地时间</b>：桶边界按请求时区取整点 / 零点 / 月初 —— 「今天」必须从本地 00:00 起，
 *       不能用 UTC 零点；</li>
 *   <li><b>密集零填充</b>：窗口内每个桶都在（没数据的给 0 / null），前端不必猜「这段是没数据还是没请求」，
 *       与看板接口的整点桶同一条。</li>
 * </ol>
 *
 * <p>跨配置合并见 {@link EnergyBucket#merge}：先在每条配置上滚动（容量取最后），再把各配置的桶相加
 * （容量相加）。顺序反过来会把「每台电池的容量」滚成一台的。</p>
 */
public final class EnergyRollup {

    /**
     * 两天以内按小时看；超过两天按天看。
     */
    public static final long HOUR = 3_600_000L;
    public static final long DAY = 24 * HOUR;

    /**
     * 天气/月度报表的桶数上限：自定义范围再长，也不会画出几万个点。
     */
    public static final int MAX_BUCKETS = 1_500;

    private EnergyRollup() {
    }

    /**
     * 按跨度自动选粒度：≤48h 小时、≤62 天按天、再长按月。
     */
    public static EnergyGranularity granularityFor(long from, long to) {
        long span = Math.max(0, to - from);
        if (span <= 48 * HOUR) {
            return EnergyGranularity.HOUR;
        }
        if (span <= 62 * DAY) {
            return EnergyGranularity.DAY;
        }
        return EnergyGranularity.MONTH;
    }

    /**
     * 时刻所在的展示桶起点（本地时区）。
     */
    public static long bucketAt(long millis, EnergyGranularity granularity, ZoneId zone) {
        ZonedDateTime time = Instant.ofEpochMilli(millis).atZone(zone);
        ZonedDateTime start = switch (granularity) {
            case HOUR -> time.truncatedTo(ChronoUnit.HOURS);
            case DAY -> time.toLocalDate().atStartOfDay(zone);
            case MONTH -> time.toLocalDate().withDayOfMonth(1).atStartOfDay(zone);
        };
        return start.toInstant().toEpochMilli();
    }

    /**
     * 下一个展示桶的起点（跨月/跨夏令时都由 {@link LocalDate} 推，不靠加固定毫秒）。
     */
    public static long next(long bucketAt, EnergyGranularity granularity, ZoneId zone) {
        ZonedDateTime time = Instant.ofEpochMilli(bucketAt).atZone(zone);
        ZonedDateTime next = switch (granularity) {
            case HOUR -> time.plusHours(1);
            case DAY -> time.toLocalDate().plusDays(1).atStartOfDay(zone);
            case MONTH -> time.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone);
        };
        return next.toInstant().toEpochMilli();
    }

    /**
     * 覆盖 {@code [from, to)} 的展示桶起点（升序、密集）。
     */
    public static List<Long> starts(long from, long to, EnergyGranularity granularity, ZoneId zone) {
        List<Long> starts = new ArrayList<>();
        if (from >= to) {
            return starts;
        }
        long at = bucketAt(from, granularity, zone);
        long last = bucketAt(to - 1, granularity, zone);
        while (at <= last && starts.size() < MAX_BUCKETS) {
            starts.add(at);
            at = next(at, granularity, zone);
        }
        return starts;
    }

    /**
     * 一条配置（或一个窗口）内的小时桶 → 展示桶。
     *
     * <p>{@code stats} 必须按 {@code bucketAt} 升序（仓库的 {@code findRange} 就是这个顺序），
     * 否则容量/循环次数这类「最后值」会取错。</p>
     */
    public static List<EnergyBucket> rollup(
            List<EnergyStat> stats,
            long from,
            long to,
            EnergyGranularity granularity,
            ZoneId zone
    ) {
        List<Long> starts = starts(from, to, granularity, zone);
        Map<Long, EnergyBucket> buckets = new LinkedHashMap<>();
        for (long at : starts) {
            buckets.put(at, EnergyBucket.empty(at));
        }
        if (stats != null) {
            for (EnergyStat stat : stats) {
                if (stat == null) {
                    continue;
                }
                long at = bucketAt(stat.bucketAt, granularity, zone);
                EnergyBucket bucket = buckets.get(at);
                if (bucket != null) {
                    buckets.put(at, bucket.plus(stat));
                }
            }
        }
        return new ArrayList<>(buckets.values());
    }

    /**
     * 一个窗口的合计（不做展示分桶）。
     */
    public static EnergyBucket summarize(List<EnergyStat> stats) {
        EnergyBucket total = EnergyBucket.empty(0);
        if (stats != null) {
            for (EnergyStat stat : stats) {
                total = total.plus(stat);
            }
        }
        return total;
    }

    /**
     * 多条配置的同一展示桶 / 合计相加。
     */
    public static EnergyBucket mergeAll(List<EnergyBucket> buckets) {
        EnergyBucket total = EnergyBucket.empty(0);
        if (buckets != null) {
            for (EnergyBucket bucket : buckets) {
                total = total.merge(bucket);
            }
        }
        return total;
    }
}
