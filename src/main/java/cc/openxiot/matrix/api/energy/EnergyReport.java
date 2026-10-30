package cc.openxiot.matrix.api.energy;

import java.util.List;

/**
 * 能源报表的聚合结果（一次请求 = 一个角色 × 一个能源类型）。
 *
 * <p>{@code buckets} 是按本地时区对齐、密集零填充的展示桶；{@code totals} 是窗口合计；
 * {@code previous} / {@code lastYear} 只给合计 —— 环比的分子分母都是合计数，
 * 前端拿两个数就能算变化率，不需要再下发两条曲线。</p>
 */
public record EnergyReport(
        String spaceId,
        String role,
        String type,
        long from,
        long to,
        EnergyGranularity granularity,
        List<EnergyBucket> buckets,
        EnergyBucket totals,
        EnergyBucket previous,
        EnergyBucket lastYear,
        List<ConfigSummary> configs
) {

    /** 报表页的配置筛选器：只带展示需要的字段（点位清单不重复下发）。 */
    public record ConfigSummary(
            String id,
            String name,
            String role,
            String type,
            Double price,
            Double chargePrice,
            Double dischargePrice,
            Integer periodHours,
            boolean enabled,
            int points
    ) {
    }
}
