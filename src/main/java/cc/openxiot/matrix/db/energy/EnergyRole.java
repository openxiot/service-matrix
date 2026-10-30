package cc.openxiot.matrix.db.energy;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 能源在页面上的三种角色：消耗、生产、存储。
 *
 * <p>角色决定「点位值怎么变成报表数字」：消耗/生产把 ENERGY 点位累加成用量；
 * 存储还要区分充电量、放电量，并附带 SOC / 容量 / 功率这类瞬时状态。
 * 一个配置只属于一种角色（见 {@link EnergyConfig#role}）——「同一块电表既算消耗又算生产」
 * 在数据上说不通，真要两边都看就建两条配置。</p>
 */
@RegisterForReflection
public enum EnergyRole {
    CONSUMPTION,
    PRODUCTION,
    STORAGE
}
