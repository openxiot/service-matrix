package cc.openxiot.matrix.api.energy;

import cc.openxiot.matrix.db.energy.EnergyConfig;
import cc.openxiot.matrix.db.energy.EnergyConfigRepository;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergyType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 能源报表：把若干条配置的小时桶汇总成一条曲线 + 三个合计数（本期 / 环比 / 同比）。
 *
 * <p><b>粒度自动选</b>（见 {@link EnergyRollup#granularityFor}）：前端不传粒度，
 * 今天看小时、本月看天、跨年看月。要精确到哪个桶是前端按预设算的 {@code from/to} 决定的。</p>
 *
 * <p><b>环比 / 同比也是查库算的</b>，不是拿本期数乘系数：上一周期不一定和本期一样长
 * （「本月至今」的上一周期是上月同期），直接比金额/电量即可。</p>
 *
 * <p><b>窗口边界按精确的 {@code from/to} 取数</b>，展示桶按本地时区对齐。自定义范围从 10:37 起时，
 * 第一个小时桶只含 10:37 之后的数据 —— 这是「这段时间的用量」应有的口径；不做补全猜测。</p>
 */
@ApplicationScoped
public class EnergyReportService {

    /** 自定义范围上限：一年（按 366 天给闰年留余量）。再长按月也只有 13 个点，但库里的行数没有意义。 */
    private static final long MAX_WINDOW = 366L * EnergyRollup.DAY;

    @Inject
    EnergyConfigRepository configRepo;

    @Inject
    EnergySeriesReader seriesReader;

    /**
     * @param from     起始时刻（含，epoch 毫秒），必填
     * @param to       结束时刻（不含，epoch 毫秒），null = 现在
     * @param configId 可选：只看某一条配置
     * @param zone     展示桶的时区（null = 系统时区）
     */
    public Uni<EnergyReport> report(String spaceId, EnergyRole role, EnergyType type,
                                    Long from, Long to, String configId, ZoneId zone) {
        if (role == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("role is required"));
        }
        if (type == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("type is required"));
        }
        if (from == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("from is required"));
        }
        long end = to == null ? System.currentTimeMillis() : to;
        if (from >= end) {
            return Uni.createFrom().failure(new IllegalArgumentException("from must be before to"));
        }
        if (end - from > MAX_WINDOW) {
            return Uni.createFrom().failure(new IllegalArgumentException("range must not exceed 366 days"));
        }
        ZoneId reportZone = zone == null ? ZoneId.systemDefault() : zone;
        EnergyGranularity granularity = EnergyRollup.granularityFor(from, end);
        long span = end - from;

        return configRepo.listBySpace(spaceId, role, type)
                .onItem().transformToUni(configs -> {
                    List<EnergyConfig> selected = select(configs, configId);
                    if (configId != null && selected.isEmpty()) {
                        return Uni.createFrom().failure(
                                new IllegalArgumentException("energy config not found: " + configId));
                    }
                    long lastYearFrom = minusYear(from, reportZone);
                    long lastYearTo = minusYear(end, reportZone);
                    return Uni.combine().all().unis(
                                    seriesReader.buckets(selected, from, end, granularity, reportZone),
                                    seriesReader.totals(selected, from, end),
                                    seriesReader.totals(selected, from - span, from),
                                    seriesReader.totals(selected, lastYearFrom, lastYearTo))
                            .asTuple()
                            .map(tuple -> new EnergyReport(
                                    spaceId,
                                    role.name(),
                                    type.name(),
                                    from,
                                    end,
                                    granularity,
                                    tuple.getItem1(),
                                    tuple.getItem2(),
                                    tuple.getItem3(),
                                    tuple.getItem4(),
                                    summaries(selected)));
                });
    }

    private static List<EnergyConfig> select(List<EnergyConfig> configs, String configId) {
        if (configId == null || configId.isBlank()) {
            return configs == null ? List.of() : configs;
        }
        List<EnergyConfig> selected = new ArrayList<>();
        if (configs != null) {
            for (EnergyConfig config : configs) {
                if (config != null && config.id != null && configId.equals(config.id.toHexString())) {
                    selected.add(config);
                }
            }
        }
        return selected;
    }

    private static List<EnergyReport.ConfigSummary> summaries(List<EnergyConfig> configs) {
        List<EnergyReport.ConfigSummary> summaries = new ArrayList<>();
        for (EnergyConfig config : configs) {
            summaries.add(new EnergyReport.ConfigSummary(
                    config.id == null ? null : config.id.toHexString(),
                    config.name,
                    config.role == null ? null : config.role.name(),
                    config.type == null ? null : config.type.name(),
                    config.price,
                    config.chargePrice,
                    config.dischargePrice,
                    config.periodHours,
                    config.enabled,
                    config.points == null ? 0 : config.points.size()));
        }
        return summaries;
    }

    /** 同一本地时刻的去年（2 月 29 日会落到 2 月 28 日，由 {@link ZonedDateTime} 处理）。 */
    private static long minusYear(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).minusYears(1).toInstant().toEpochMilli();
    }
}
