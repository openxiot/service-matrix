package cc.openxiot.matrix.db.energy;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 能源点位的来源：设备服务里的属性，或现场设备（Modbus）的寄存器。
 *
 * <p>两者取数路径不同（前者走 manipulation 的设备影子，后者直接读 matrix 自己的
 * {@code modbus/fd-shadows}），但对报表来说都只是「某一刻的一个数值」，
 * 所以配置与累计逻辑在这一层统一。</p>
 */
@RegisterForReflection
public enum EnergySourceKind {
    /** 设备服务属性：{@code did + siid + piid}，值是设备影子的 {@code value} */
    SERVICE,
    /** 现场设备寄存器：{@code fdid + registerIndex}，值是 Modbus 影子的 {@code value} */
    REGISTER
}
