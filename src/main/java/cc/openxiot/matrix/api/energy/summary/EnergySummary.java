package cc.openxiot.matrix.api.energy.summary;

import cc.openxiot.matrix.api.energy.EnergyBucket;
import cc.openxiot.matrix.api.energy.EnergyGranularity;

import java.util.List;

/**
 * 能源摘要页（一级页）一次取数的全部结果。
 *
 * <p>五张总计卡用稳定 code（{@link Key}）而不是界面词条：服务端不依赖语言，
 * 前端把 code 映射成「太阳能总计」等 i18n 键。{@code previous} 给上一等长窗口的原始值，
 * 环比由前端用既有的 {@code changeRate()} 算 —— 与报表页对「上一期是 0 / 没数据」的
 * 处理保持一致，服务端不重复实现变化率。</p>
 *
 * <p>曲线与合计都来自报表同一套 rollup：{@code series} 是密集零填充的展示桶，
 * 没有采样的桶 {@code samples = 0}。存储状态是「此刻」的瞬时值，来自点位基线，
 * 与窗口无关 —— 切到昨天看的也是现在的 SOC。</p>
 */
public record EnergySummary(
        String spaceId,
        long from,
        long to,
        String zone,
        EnergyGranularity granularity,
        List<Total> totals,
        Series series,
        /** 窗口内充电量（kWh）；没有存储采样时 null */
        Double charge,
        /** 窗口内放电量（kWh）；没有存储采样时 null */
        Double discharge,
        /** 此刻的存储状态；没有启用中的存储配置时 null */
        Storage storage
) {

    /** 总计卡：固定顺序 SOLAR、BATTERY、GRID、GAS、WATER。 */
    public enum Key {
        SOLAR,
        BATTERY,
        GRID,
        GAS,
        WATER
    }

    /**
     * 一张总计卡。
     *
     * @param value    窗口合计；没有配置或没有采样 → null（页面上是「—」，不是 0）
     * @param previous 上一等长窗口的同口径合计；没有数据或为 0 → null
     */
    public record Total(Key key, Double value, String unit, Double previous) {
    }

    /** 五组展示桶；字段名与页面区块一一对应。 */
    public record Series(
            List<EnergyBucket> consumption,
            List<EnergyBucket> production,
            List<EnergyBucket> gas,
            List<EnergyBucket> water,
            List<EnergyBucket> storage
    ) {
    }

    /** 存储状态卡的当前值：任一字段都可能为 null。 */
    public record Storage(Double soc, Double available, Double capacity, Double power) {
    }
}
