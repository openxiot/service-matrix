package cc.openxiot.matrix.api.energy.status;

import java.util.List;

/**
 * 储能实时状态（{@code /energy/status}）：当前值 + 今日充放电量。
 *
 * <p><b>为什么单独一个接口</b>：报表是「一段时间」的聚合，取不到「现在 SOC 多少」——
 * 报表里的 SOC 是桶内平均值。这里直接读采样器留下的点位基线（{@code lastRaw} / {@code lastAt}），
 * 给状态卡用；今日充放电量来自当天的小时桶合计。</p>
 *
 * <p><b>一台电池 = 一条配置</b>：组内多个 SOC 点位取平均、多个容量点位相加、多个功率点位相加，
 * 对应「一台电池有多个模组」的配置方式。多台电池请建多条配置，页面按配置名逐台列卡。
 * 总览的 SOC / SOH 按容量加权，温度取简单平均（温度与容量无关）。</p>
 *
 * <p>{@code total} 与 {@code groups} 用同一个形状：前端两张卡（总览 / 单台）可以共用渲染。
 * 总览没有 {@code configId} / {@code name}。没有数据的指标是 {@code null}，不是 0 ——
 * 页面上「—」与「确实是 0」必须分得开。</p>
 */
public record EnergyStatus(
        String spaceId,
        String type,
        List<Group> groups,
        Group total
) {

    /** 一台电池（一条配置）的当前状态。 */
    public record Group(
            String configId,
            String name,
            Double soc,
            Double capacity,
            Double available,
            Double power,
            Double soh,
            Double temperature,
            Double cycles,
            /** 今日充电量（kWh） */
            double charge,
            /** 今日放电量（kWh） */
            double discharge,
            /** 该组最后一个有效采样时刻（epoch 毫秒）；null = 还没采到过 */
            Long updatedAt
    ) {
    }
}
