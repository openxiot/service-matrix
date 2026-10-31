package cc.openxiot.matrix.api.energy.status;

import cc.openxiot.matrix.api.energy.EnergyBucket;
import cc.openxiot.matrix.api.energy.EnergyRollup;
import cc.openxiot.matrix.db.energy.EnergyConfig;
import cc.openxiot.matrix.db.energy.EnergyConfigRepository;
import cc.openxiot.matrix.db.energy.EnergyMetric;
import cc.openxiot.matrix.db.energy.EnergyPoint;
import cc.openxiot.matrix.db.energy.EnergyPointState;
import cc.openxiot.matrix.db.energy.EnergyPointStateRepository;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergyStat;
import cc.openxiot.matrix.db.energy.EnergyStatRepository;
import cc.openxiot.matrix.db.energy.EnergyType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 储能实时状态：点位基线 → 当前 SOC / 容量 / 功率等；当天小时桶 → 今日充放电量。
 *
 * <p>只返回**启用中且有点位**的配置：停用配置基线还留在库里，但拿它当「当前状态」会误导，
 * 停用之后页面应当看不到这台电池，而不是看到一个永远不变的数字。</p>
 *
 * <p>一条配置内多个同指标点位：SOC / SOH / 温度取平均，容量 / 功率 / 循环次数相加。
 * 多台电池（多条配置）请分别建配置；总览按容量对 SOC / SOH 加权，温度简单平均。</p>
 */
@ApplicationScoped
public class EnergyStatusService {

    @Inject
    EnergyConfigRepository configRepo;

    @Inject
    EnergyPointStateRepository stateRepo;

    @Inject
    EnergyStatRepository statRepo;

    public Uni<EnergyStatus> status(String spaceId, EnergyType type, ZoneId zone) {
        return configRepo.listBySpace(spaceId, EnergyRole.STORAGE, type)
                .chain(configs -> {
                    long todayFrom = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
                    long todayTo = System.currentTimeMillis() + 1;
                    List<Uni<EnergyStatus.Group>> groups = new ArrayList<>();
                    if (configs != null) {
                        for (EnergyConfig config : configs) {
                            if (config == null || config.id == null || !config.enabled
                                    || config.points == null || config.points.isEmpty()) {
                                continue;
                            }
                            String configId = config.id.toHexString();
                            groups.add(Uni.combine().all().unis(
                                            stateRepo.listByConfig(configId),
                                            statRepo.findRange(configId, todayFrom, todayTo))
                                    .asTuple()
                                    .map(tuple -> group(config, tuple.getItem1(), tuple.getItem2())));
                        }
                    }
                    if (groups.isEmpty()) {
                        return Uni.createFrom().item(
                                new EnergyStatus(spaceId, type.name(), List.of(), total(List.of())));
                    }
                    return Uni.join().all(groups).andFailFast()
                            .map(list -> new EnergyStatus(spaceId, type.name(), list, total(list)));
                });
    }

    /** 一条配置的当前状态：点位基线给瞬时量，当天小时桶给今日充放电量。 */
    private static EnergyStatus.Group group(EnergyConfig config, List<EnergyPointState> states,
                                            List<EnergyStat> stats) {
        Map<String, EnergyPointState> byKey = new HashMap<>();
        if (states != null) {
            for (EnergyPointState state : states) {
                if (state != null && state.pointKey != null) {
                    byKey.put(state.pointKey, state);
                }
            }
        }

        MetricValues values = new MetricValues();
        Long updatedAt = null;
        for (EnergyPoint point : config.points) {
            if (point == null) {
                continue;
            }
            EnergyPointState state = byKey.get(point.key());
            if (state == null || state.lastRaw == null) {
                continue;
            }
            values.add(point.metric, state.lastRaw);
            updatedAt = latest(updatedAt, state.lastAt);
        }

        Double soc = average(values.soc);
        Double capacity = sum(values.capacity);
        Double available = soc == null || capacity == null ? null : capacity * soc / 100;
        EnergyBucket today = EnergyRollup.summarize(stats);
        return new EnergyStatus.Group(
                config.id.toHexString(),
                config.name,
                soc,
                capacity,
                available,
                sum(values.power),
                average(values.soh),
                average(values.temperature),
                sum(values.cycles),
                today.charge(),
                today.discharge(),
                updatedAt);
    }

    /** 多台电池的总览：容量/可用容量/功率/循环次数/今日电量相加。 */
    private static EnergyStatus.Group total(List<EnergyStatus.Group> groups) {
        Double capacity = null;
        Double available = null;
        Double power = null;
        Double cycles = null;
        double charge = 0;
        double discharge = 0;
        Long updatedAt = null;
        for (EnergyStatus.Group group : groups) {
            if (group == null) {
                continue;
            }
            capacity = add(capacity, group.capacity());
            available = add(available, group.available());
            power = add(power, group.power());
            cycles = add(cycles, group.cycles());
            charge += group.charge();
            discharge += group.discharge();
            updatedAt = latest(updatedAt, group.updatedAt());
        }
        return new EnergyStatus.Group(
                null,
                null,
                weightedAverage(groups, EnergyStatus.Group::soc),
                capacity,
                available,
                power,
                weightedAverage(groups, EnergyStatus.Group::soh),
                averageOf(groups),
                cycles,
                charge,
                discharge,
                updatedAt);
    }

    /**
     * SOC / SOH 的跨配置平均：有容量的按容量加权（总 SOC 不能把 10 kWh 与 100 kWh 的电池
     * 同样看待），全都没容量才退回简单平均。温度不走这里 —— 它与容量无关，见 {@link #averageOf}。
     */
    private static Double weightedAverage(List<EnergyStatus.Group> groups,
                                          Function<EnergyStatus.Group, Double> valueOf) {
        double weighted = 0;
        double weight = 0;
        double plain = 0;
        int count = 0;
        for (EnergyStatus.Group group : groups) {
            Double value = valueOf.apply(group);
            if (value == null) {
                continue;
            }
            count++;
            plain += value;
            Double capacity = group.capacity();
            if (capacity != null && capacity > 0) {
                weighted += value * capacity;
                weight += capacity;
            }
        }
        if (count == 0) {
            return null;
        }
        return weight > 0 ? weighted / weight : plain / count;
    }

    /** 温度：简单平均（权重与容量无关）。单独拿出来，免得读代码的人以为它也被容量加权了。 */
    private static Double averageOf(List<EnergyStatus.Group> groups) {
        double sum = 0;
        int count = 0;
        for (EnergyStatus.Group group : groups) {
            if (group.temperature() != null) {
                sum += group.temperature();
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    /** 同一配置内按指标归集点位读数。 */
    private static final class MetricValues {
        private final List<Double> soc = new ArrayList<>();
        private final List<Double> capacity = new ArrayList<>();
        private final List<Double> power = new ArrayList<>();
        private final List<Double> soh = new ArrayList<>();
        private final List<Double> temperature = new ArrayList<>();
        private final List<Double> cycles = new ArrayList<>();

        void add(EnergyMetric metric, double value) {
            if (metric == null) {
                return;
            }
            switch (metric) {
                case SOC -> soc.add(value);
                case CAPACITY -> capacity.add(value);
                case POWER -> power.add(value);
                case SOH -> soh.add(value);
                case TEMPERATURE -> temperature.add(value);
                case CYCLES -> cycles.add(value);
                default -> {
                    // 电量类点位不进状态卡；今日电量看小时桶
                }
            }
        }
    }

    private static Double sum(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum;
    }

    private static Double average(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static Double add(Double a, Double b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + b;
    }

    private static Long latest(Long a, Date b) {
        if (b == null) {
            return a;
        }
        long value = b.getTime();
        return a == null ? value : Math.max(a, value);
    }

    private static Long latest(Long a, Long b) {
        if (b == null) {
            return a;
        }
        return a == null ? b : Math.max(a, b);
    }
}
