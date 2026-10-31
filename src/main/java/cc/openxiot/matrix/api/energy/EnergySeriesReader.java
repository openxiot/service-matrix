package cc.openxiot.matrix.api.energy;

import cc.openxiot.matrix.db.energy.config.EnergyConfig;
import cc.openxiot.matrix.db.energy.stat.EnergyStatRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@code energy-stats} 读一组配置的展示桶与窗口合计。
 *
 * <p>抽出来的原因：报表与摘要都要「每条配置先在本配置内滚动，再跨配置合并」，
 * 而合并顺序是有讲究的 —— 容量、循环次数必须先在配置内取末值再相加，反过来会把
 * 多台电池的容量按小时数放大（见 {@link EnergyBucket}）。两份实现早晚会分叉，
 * 所以统一走这里。</p>
 *
 * <p>读法是按配置逐个 {@code findRange}（{@code (configId, bucketAt)} 是索引前缀），
 * 与告警/历史接口同口径，不用 {@code $in} 把前缀丢掉。</p>
 */
@ApplicationScoped
public class EnergySeriesReader {

    private final EnergyStatRepository statRepo;

    @Inject
    public EnergySeriesReader(EnergyStatRepository statRepo) {
        this.statRepo = statRepo;
    }

    /** 展示曲线：每条配置先在本配置内滚动，再把同一展示桶相加（容量/循环次数不会重复滚）。 */
    public Uni<List<EnergyBucket>> buckets(List<EnergyConfig> configs, long from, long to,
                                           EnergyGranularity granularity, ZoneId zone) {
        if (configs.isEmpty()) {
            return Uni.createFrom().item(new ArrayList<>());
        }
        List<Uni<List<EnergyBucket>>> queries = configs.stream()
                .map(config -> statRepo.findRange(config.id.toHexString(), from, to)
                        .map(stats -> EnergyRollup.rollup(stats, from, to, granularity, zone)))
                .toList();
        return Uni.join().all(queries).andFailFast()
                .map(groups -> mergeBuckets(EnergyRollup.starts(from, to, granularity, zone), groups));
    }

    /** 一个窗口的合计：各配置分别求和，再把各配置的合计相加。 */
    public Uni<EnergyBucket> totals(List<EnergyConfig> configs, long from, long to) {
        if (configs.isEmpty()) {
            return Uni.createFrom().item(EnergyBucket.empty(0));
        }
        List<Uni<EnergyBucket>> queries = configs.stream()
                .map(config -> statRepo.findRange(config.id.toHexString(), from, to)
                        .map(EnergyRollup::summarize))
                .toList();
        return Uni.join().all(queries).andFailFast().map(EnergyRollup::mergeAll);
    }

    private static List<EnergyBucket> mergeBuckets(List<Long> starts, List<List<EnergyBucket>> groups) {
        List<EnergyBucket> merged = new ArrayList<>(starts.size());
        for (long at : starts) {
            merged.add(EnergyBucket.empty(at));
        }
        for (List<EnergyBucket> group : groups) {
            if (group == null) {
                continue;
            }
            for (int i = 0; i < merged.size() && i < group.size(); i++) {
                merged.set(i, merged.get(i).merge(group.get(i)));
            }
        }
        return merged;
    }
}
