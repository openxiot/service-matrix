package cc.openxiot.matrix.api.energy.summary;

import cc.openxiot.matrix.api.energy.EnergyBucket;
import cc.openxiot.matrix.api.energy.EnergyGranularity;
import cc.openxiot.matrix.api.energy.EnergyRollup;
import cc.openxiot.matrix.api.energy.EnergySeriesReader;
import cc.openxiot.matrix.api.energy.status.EnergyStatus;
import cc.openxiot.matrix.api.energy.status.EnergyStatusService;
import cc.openxiot.matrix.db.energy.EnergyConfig;
import cc.openxiot.matrix.db.energy.EnergyConfigRepository;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergyType;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple6;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 能源摘要页：一个窗口内五张总计卡 + 五组曲线 + 存储状态，一次返回。
 *
 * <p><b>读时聚合，不落摘要表</b>：窗口、时区、粒度都会变，存一份就要处理失效与回填；
 * 现有小时桶按配置 × 小时存着，读的时候按 {@code EnergySeriesReader} 滚一遍即可。</p>
 *
 * <p><b>五张卡按角色 × 类型映射</b>：太阳能 = 生产 × 电，电池 = 存储 × 电的充电量，
 * 电网 = {@code max(0, 用电 − 发电)}（近似净购电），燃气 / 用水 = 消耗 × 气 / 水。
 * 现有模型没有「太阳能 / 电网 / 电池」这种来源字段，不做猜测。</p>
 *
 * <p><b>null 与 0 分开</b>：没有配置或窗口内没有采样 → 该卡 null；有采样但确实是 0 → 0。
 * 电网是例外：发电没配置按 0 计，发电有配置但没采样则电网也给 null —— 不知道自发自用，
 * 不能拿用电冒充净购电。</p>
 */
@ApplicationScoped
public class EnergySummaryService {

    /** 与报表同一上限：一年（按 366 天给闰年留余量）。 */
    private static final long MAX_WINDOW = 366L * EnergyRollup.DAY;

    private static final String KWH = "kWh";
    private static final String CUBIC_METER = "m³";

    @Inject
    EnergyConfigRepository configRepo;

    @Inject
    EnergySeriesReader seriesReader;

    @Inject
    EnergyStatusService statusService;

    /**
     * @param from 起始时刻（含，epoch 毫秒），必填
     * @param to   结束时刻（不含，epoch 毫秒），null = 现在
     * @param zone 展示桶的时区（null = 系统时区）
     */
    public Uni<EnergySummary> summary(String spaceId, Long from, Long to, ZoneId zone) {
        if (from == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("from is required"));
        }
        long start = from;
        long end = to == null ? System.currentTimeMillis() : to;
        if (start >= end) {
            return Uni.createFrom().failure(new IllegalArgumentException("from must be before to"));
        }
        if (end - start > MAX_WINDOW) {
            return Uni.createFrom().failure(new IllegalArgumentException("range must not exceed 366 days"));
        }
        ZoneId summaryZone = zone == null ? ZoneId.systemDefault() : zone;
        EnergyGranularity granularity = EnergyRollup.granularityFor(start, end);
        long span = end - start;

        return configRepo.listBySpace(spaceId)
                .chain(configs -> {
                    List<EnergyConfig> all = configs == null ? List.of() : configs;
                    return Uni.combine().all().unis(
                                    window(select(all, EnergyRole.CONSUMPTION, EnergyType.ELECTRICITY),
                                            start, end, span, granularity, summaryZone),
                                    window(select(all, EnergyRole.PRODUCTION, EnergyType.ELECTRICITY),
                                            start, end, span, granularity, summaryZone),
                                    window(select(all, EnergyRole.CONSUMPTION, EnergyType.GAS),
                                            start, end, span, granularity, summaryZone),
                                    window(select(all, EnergyRole.CONSUMPTION, EnergyType.WATER),
                                            start, end, span, granularity, summaryZone),
                                    window(select(all, EnergyRole.STORAGE, EnergyType.ELECTRICITY),
                                            start, end, span, granularity, summaryZone),
                                    statusService.status(spaceId, EnergyType.ELECTRICITY, summaryZone))
                            .asTuple()
                            .map(tuple -> build(spaceId, start, end, summaryZone, granularity, tuple));
                });
    }

    private static List<EnergyConfig> select(List<EnergyConfig> configs, EnergyRole role, EnergyType type) {
        List<EnergyConfig> selected = new ArrayList<>();
        for (EnergyConfig config : configs) {
            if (config != null && config.id != null && config.role == role && config.type == type) {
                selected.add(config);
            }
        }
        return selected;
    }

    /**
     * 一个角色 × 类型在本期与上一期的取数。
     *
     * <p>没有配置时仍然铺出密集的空桶（{@code samples = 0}），曲线的横轴与其它组一致，
     * 页面不必为「这组没配置」单独处理。</p>
     */
    private Uni<Window> window(List<EnergyConfig> configs, long from, long to, long span,
                               EnergyGranularity granularity, ZoneId zone) {
        if (configs.isEmpty()) {
            List<EnergyBucket> buckets = new ArrayList<>();
            for (long at : EnergyRollup.starts(from, to, granularity, zone)) {
                buckets.add(EnergyBucket.empty(at));
            }
            return Uni.createFrom().item(
                    new Window(buckets, EnergyBucket.empty(0), EnergyBucket.empty(0), false));
        }
        return Uni.combine().all().unis(
                        seriesReader.buckets(configs, from, to, granularity, zone),
                        seriesReader.totals(configs, from, to),
                        seriesReader.totals(configs, from - span, from))
                .asTuple()
                .map(tuple -> new Window(tuple.getItem1(), tuple.getItem2(), tuple.getItem3(), true));
    }

    private static EnergySummary build(String spaceId, long from, long to, ZoneId zone,
                                       EnergyGranularity granularity,
                                       Tuple6<Window, Window, Window, Window, Window, EnergyStatus> tuple) {
        Window consumption = tuple.getItem1();
        Window production = tuple.getItem2();
        Window gas = tuple.getItem3();
        Window water = tuple.getItem4();
        Window storage = tuple.getItem5();
        EnergyStatus status = tuple.getItem6();

        List<EnergySummary.Total> totals = List.of(
                total(EnergySummary.Key.SOLAR, energy(production), KWH, previousEnergy(production)),
                total(EnergySummary.Key.BATTERY, charge(storage), KWH, previousCharge(storage)),
                total(EnergySummary.Key.GRID, grid(consumption, production), KWH,
                        grid(consumption.previous, production.previous,
                                consumption.configured, production.configured)),
                total(EnergySummary.Key.GAS, energy(gas), CUBIC_METER, previousEnergy(gas)),
                total(EnergySummary.Key.WATER, energy(water), CUBIC_METER, previousEnergy(water)));

        return new EnergySummary(
                spaceId,
                from,
                to,
                zone.getId(),
                granularity,
                totals,
                new EnergySummary.Series(
                        consumption.buckets,
                        production.buckets,
                        gas.buckets,
                        water.buckets,
                        storage.buckets),
                charge(storage),
                discharge(storage),
                storageOf(status));
    }

    private static EnergySummary.Total total(EnergySummary.Key key, Double value, String unit,
                                             Double previous) {
        return new EnergySummary.Total(key, value, unit, previousBase(previous));
    }

    /** 本期合计的用量；一个采样点都没有 → null。 */
    private static Double energy(Window window) {
        return window.current.samples() == 0 ? null : window.current.energy();
    }

    /** 上一期合计的用量；没有采样 → null。 */
    private static Double previousEnergy(Window window) {
        return window.previous.samples() == 0 ? null : window.previous.energy();
    }

    /** 窗口内的充电量；没有存储采样 → null。上一期没有数据或为 0 → null。 */
    private static Double charge(Window window) {
        return window.current.samples() == 0 ? null : window.current.charge();
    }

    private static Double previousCharge(Window window) {
        return window.previous.samples() == 0 ? null : window.previous.charge();
    }

    /** 窗口内的放电量；没有存储采样 → null。 */
    private static Double discharge(Window window) {
        return window.current.samples() == 0 ? null : window.current.discharge();
    }

    /**
     * 电网 = max(0, 用电 − 发电)。
     *
     * <p>用电没数据 → null。发电没配置按 0 计；发电有配置但窗口内没采样 → null，
     * 因为此时算不出自发自用。</p>
     */
    private static Double grid(Window consumption, Window production) {
        return grid(consumption.current, production.current,
                consumption.configured, production.configured);
    }

    private static Double grid(EnergyBucket consumption, EnergyBucket production,
                               boolean consumptionConfigured, boolean productionConfigured) {
        if (!consumptionConfigured || consumption.samples() == 0) {
            return null;
        }
        if (productionConfigured) {
            if (production.samples() == 0) {
                return null;
            }
            return Math.max(0, consumption.energy() - production.energy());
        }
        return Math.max(0, consumption.energy());
    }

    /** 环比的分母：上一期没有数据或为 0 时给 null，前端据此不显示百分比。 */
    private static Double previousBase(Double previous) {
        return previous == null || previous == 0 ? null : previous;
    }

    private static EnergySummary.Storage storageOf(EnergyStatus status) {
        if (status == null || status.groups() == null || status.groups().isEmpty()) {
            return null;
        }
        EnergyStatus.Group total = status.total();
        if (total == null) {
            return null;
        }
        return new EnergySummary.Storage(total.soc(), total.available(), total.capacity(), total.power());
    }

    /** 一组配置在本期 / 上一期的结果。 */
    private record Window(List<EnergyBucket> buckets, EnergyBucket current, EnergyBucket previous,
                          boolean configured) {
    }
}
