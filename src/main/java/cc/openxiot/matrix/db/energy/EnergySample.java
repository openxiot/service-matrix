package cc.openxiot.matrix.db.energy;

/**
 * 一次采样对一个小时桶的**增量**（纯值对象）。
 *
 * <p>由 {@code EnergyAccumulator} 算出来、由 {@link EnergyStatRepository#addSample} 累加进桶。
 * 用 record 而不是直接改 {@link EnergyStat}，是为了让「累计」这一步成为可单测的纯函数 ——
 * 仓库那层只负责把数值翻译成 Mongo 的 {@code $inc} / {@code $min} / {@code $max}。</p>
 *
 * <p>数值型字段为 {@code null} 表示「这一轮没有这个指标」，不是 0：null 不会往桶里写，
 * 0 会。两者在报表上分别对应「没配这个点位」与「配了但这一轮读数是 0」。</p>
 */
public record EnergySample(
        double energy,
        double charge,
        double discharge,
        double cost,
        double revenue,
        Double power,
        Double soc,
        Double capacity,
        Double soh,
        Double temperature,
        Double cycles
) {

    /** 空采样：点位全部没读到 / 全部是基线轮，不往桶里写任何东西。 */
    public static final EnergySample EMPTY =
            new EnergySample(0, 0, 0, 0, 0, null, null, null, null, null, null);

    public boolean isEmpty() {
        return energy == 0 && charge == 0 && discharge == 0 && cost == 0 && revenue == 0
                && power == null && soc == null && capacity == null && soh == null
                && temperature == null && cycles == null;
    }
}
