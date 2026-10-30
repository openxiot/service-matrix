package cc.openxiot.matrix.db.energy;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 能源类型：电（kWh）、水（m³）、燃气（m³）。
 *
 * <p>单位不由后端换算：配置里的点位自带 {@link EnergyPoint#unit}，报表原样展示。
 * 这个枚举只负责「分类」，让同一类配置能放在一起比较、也决定页面上默认的图表标题。</p>
 */
@RegisterForReflection
public enum EnergyType {
    ELECTRICITY,
    WATER,
    GAS
}
