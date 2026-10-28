package cc.openxiot.matrix.db.modbus.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 寄存器中心点表的共享解析：把一只 {@link ModbusRegistry} 换算成读/写所需的字节宽、格式、字节序。
 *
 * <p>字段引用寄存器后（{@code registryIndex}），parser 切片、frame 组帧、validator 对账都要
 * 「由寄存器推出元数据」——集中在这里，避免各自抄一份 {@code dataType→宽度} 的映射而漂移。</p>
 */
public final class ModbusRegistries {

    private ModbusRegistries() {
    }

    /** 寄存器 → 按 index 的查询表（一次构建，反复用）。 */
    public static Map<Integer, ModbusRegistry> byIndex(Iterable<ModbusRegistry> registries) {
        Map<Integer, ModbusRegistry> map = new HashMap<>();
        if (registries != null) {
            for (ModbusRegistry reg : registries) {
                if (reg != null && reg.index != null) {
                    map.put(reg.index, reg);
                }
            }
        }
        return map;
    }

    /** 是不是位点位（coil/discrete）：读按位打包、写按 0/1。 */
    public static boolean isBit(ModbusRegistry reg) {
        String area = reg == null ? null : reg.area;
        return "coil".equals(area) || "discrete".equals(area);
    }

    /**
     * 该寄存器占用的**字节宽**（读切片 / 写数据区用）：
     * 数值由 dataType 推出（int16/uint16→2、int32/uint32/float32→4、int64/uint64→8、int8/uint8→1）；
     * string 宽度不定，用 {@link ModbusRegistry#length}（缺省 1 寄存器 = 2 字节）；
     * 位点位返回 0（按位打包，不参与字节累计）。
     *
     * @throws IllegalArgumentException dataType 不认识或缺失
     */
    public static int byteWidth(ModbusRegistry reg) {
        if (reg == null) {
            throw new IllegalArgumentException("modbus registry is null");
        }
        if (isBit(reg)) {
            return 0;
        }
        String type = dataType(reg);
        return switch (type) {
            case "int8", "uint8" -> 1;
            case "int16", "uint16" -> 2;
            case "int32", "uint32", "float32" -> 4;
            case "int64", "uint64" -> 8;
            case "string" -> reg.length == null ? 2 : reg.length;
            default -> throw new IllegalArgumentException(
                    "unsupported modbus registry data type: " + type + " (registry " + reg.index + ")");
        };
    }

    /** 寄存器占用的**寄存器个数**（写 10 / 读 quantity 口径）：字节宽除 2；位点位恒 1 位。 */
    public static int registerCount(ModbusRegistry reg) {
        if (isBit(reg)) {
            return 1;
        }
        return (byteWidth(reg) + 1) / 2;
    }

    /** 与 {@code format} 同口径的小写数据格式：位点位给 {@code bit}，数值给 dataType。 */
    public static String format(ModbusRegistry reg) {
        return isBit(reg) ? "bit" : dataType(reg);
    }

    /** 归一的小写 dataType；未知/缺失抛异常（定义期校验器会先拦，这里是运行期兜底）。 */
    public static String dataType(ModbusRegistry reg) {
        String type = reg.dataType;
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("modbus registry data type is not set (registry " + reg.index + ")");
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }

    /** 该寄存器的字节序（无则 null → 用默认）。 */
    public static String byteOrder(ModbusRegistry reg) {
        return reg == null ? null : reg.byteOrder;
    }
}