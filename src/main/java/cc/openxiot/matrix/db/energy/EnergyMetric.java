package cc.openxiot.matrix.db.energy;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 一个能源点位在报表里代表什么。
 *
 * <p>{@link EnergyRole#CONSUMPTION} / {@link EnergyRole#PRODUCTION} 只用 {@link #ENERGY}；
 * {@link EnergyRole#STORAGE} 用得到其余几种。分开命名而不是「一个 role 一套枚举」，
 * 是为了让采样与汇总只有一份实现：角色只决定「哪些指标出现在报表上」，点位本身仍是
 * 「某个数值 + 怎么读它」。</p>
 */
@RegisterForReflection
public enum EnergyMetric {
    /** 用量/产量（累计或增量，见 {@link EnergyValueMode}） */
    ENERGY,
    /** 储能充电量 */
    CHARGE_ENERGY,
    /** 储能放电量 */
    DISCHARGE_ENERGY,
    /** 瞬时功率（kW）：正=充电、负=放电；按时间积分成充/放电量 */
    POWER,
    /** 荷电状态（%） */
    SOC,
    /** 额定/可用容量（kWh） */
    CAPACITY,
    /** 健康度（%） */
    SOH,
    /** 温度（℃） */
    TEMPERATURE,
    /** 累计循环次数 */
    CYCLES
}
