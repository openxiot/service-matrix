package cc.openxiot.matrix.api.energy;

/**
 * 报表展示粒度。
 *
 * <p>存储层固定是小时桶（见 {@code EnergyStat}），展示粒度由请求的时间范围自动选：
 * 两天以内按小时、两个月以内按天、再长按月。这样「今天」能看小时曲线、「本月」不会挤成
 * 七百多个点，「去年同月」也还有意义。</p>
 */
public enum EnergyGranularity {
    HOUR,
    DAY,
    MONTH
}
