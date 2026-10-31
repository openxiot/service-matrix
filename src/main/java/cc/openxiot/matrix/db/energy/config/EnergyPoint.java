package cc.openxiot.matrix.db.energy.config;

import cc.openxiot.matrix.db.energy.EnergyMetric;
import cc.openxiot.matrix.db.energy.EnergySourceKind;
import cc.openxiot.matrix.db.energy.EnergyValueMode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 一条能源点位：从哪个设备/寄存器取数、这个数代表什么、按哪种口径累计。
 *
 * <p>存进配置文档时**内嵌**（没有独立集合）：点位没有自己的生命周期，跟着配置走；
 * 配置被编辑时整份替换，不会留下半个点位。</p>
 *
 * <p>{@code name} / {@code unit} 是**用户配置的数据**（或从点表快照带过来的展示名），
 * 原样存、原样下发，**不翻译**（见 AGENTS.md 的 i18n 一节）。</p>
 */
@RegisterForReflection
@Schema(description = "能源点位")
public class EnergyPoint {

    @Schema(description = "来源：设备服务属性 / 现场设备寄存器", required = true)
    public EnergySourceKind source;

    /** SERVICE：设备 did */
    public String did;
    /** SERVICE：服务 iid */
    public Integer siid;
    /** SERVICE：属性 iid */
    public Integer piid;

    /** REGISTER：现场设备 id */
    public String fdid;
    /** REGISTER：寄存器序号（点表内稳定身份） */
    public Integer registerIndex;

    @Schema(description = "在报表里代表什么（消耗/生产固定 ENERGY）", required = true)
    public EnergyMetric metric;

    @Schema(description = "累计口径（功率不分）", required = true)
    public EnergyValueMode mode;

    /** 展示名（点位/属性的名字）：用户数据，不翻译 */
    public String name;

    /** 单位（kWh / m³ / kW / % / ℃ …）：用户数据，不翻译 */
    public String unit;

    /**
     * 点位在配置内的稳定键：同一条配置里按它索引累计状态。
     *
     * <p>不含 {@code name} / {@code unit}：改名不该让累计基线断掉（否则当月报表会从改名那一刻
     * 重新起算）。也不含 {@code mode}：改了口径本来就该断基线，但上面已经能看出
     * source + 指标不同；mode 变化由调用方连基线一起重建（见 {@code EnergySampler}）。</p>
     */
    public String key() {
        String ref = source == EnergySourceKind.SERVICE
                ? did + "." + siid + "." + piid
                : fdid + "#" + registerIndex;
        return source + "|" + ref + "|" + metric;
    }

    /** 是否是一个「按数值累计」的点位（用量、充放电量）。 */
    public boolean isEnergy() {
        return metric == EnergyMetric.ENERGY
                || metric == EnergyMetric.CHARGE_ENERGY
                || metric == EnergyMetric.DISCHARGE_ENERGY;
    }

    /** 是否是瞬时状态点位（SOC / 容量 / 健康度 / 温度 / 循环次数）。 */
    public boolean isState() {
        return metric == EnergyMetric.SOC
                || metric == EnergyMetric.CAPACITY
                || metric == EnergyMetric.SOH
                || metric == EnergyMetric.TEMPERATURE
                || metric == EnergyMetric.CYCLES;
    }
}
