package cc.openxiot.matrix.db.modbus.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Modbus 设备点表中的一个“功能码动作”。
 *
 * <p>以功能码为中心：一行 = 一次读操作（01/02/03/04）或一次写操作（05/06/0F/10）。</p>
 *
 * <p><b>以寄存器为中心</b>：命令不再内联解码/编码元数据（数据格式、字节序、位名、缩放/单位），
 * 这些现在都存在被引用的 {@link ModbusRegistry} 上，命令只通过 {@code index} 引用寄存器。
 * 单个寄存器的读由消费方按 {@link ModbusConfig#registries} 隐式生成，显式命令只描述用户额外要做的
 * 批量读 / 写动作：<ul>
 *   <li>读（01/02/03/04）：只保留 {@code name / fc / index / start / quantity}；
 *       {@code quantity} 是**值的个数**：01/02 一个值一位；03/04 每个值从
 *       {@code start + 第几个值 × start 处寄存器跨度} 开始、占同样多的寄存器
 *       （整段 string 算一个值）。01/02 的位名取被引用寄存器的名字。</li>
 *   <li>写（05/06/0F/10）：统一成有序的 {@code writeItems}，每条引用一个寄存器并给出原始值。</li>
 * </ul>
 *
 * <p>读写方向与“寄存器区”均由功能码决定（01/05/0F=线圈、02=离散输入、03/06/10=保持寄存器、04=输入寄存器），
 * 因此不再存 area/rw。逻辑地址不落库，由前端按 {@code fc}+{@code start} 实时换算。</p>
 *
 * <p>功能码词表（十六进制字符串）：01 读线圈 / 02 读离散输入 / 03 读保持寄存器 / 04 读输入寄存器 /
 * 05 写单线圈 / 06 写单寄存器 / 0F 写多线圈 / 10 写多寄存器。</p>
 */
@RegisterForReflection
@Schema(description = "Modbus 功能码动作（读参数或写操作，以寄存器为中心）")
public class ModbusCommand {

    @Schema(description = "功能名称（用户可改），如 读蒸发器进水温度", required = true)
    public String name;

    @Schema(description = "功能码：01/02/03/04/05/06/0F/10（十六进制大写）", examples = {"03"}, required = true)
    public String fc;

    @Schema(description = "序号（1 起自然数）：功能码动作在点表内的顺序；前端排序依赖它",
            examples = {"1"}, required = true)
    public Integer index;

    @Schema(description = "起始地址 = 0 基数据地址（线上值；逻辑地址由前端按 fc 区段换算）", examples = {"0", "10", "16384"}, required = true)
    public Integer start;

    // ===== 读操作 01/02/03/04 =====

    @Schema(description = "数量：03/04 为**值的个数**（每个值按 start 处寄存器的跨度占 1/2/…/6 个寄存器；"
            + "string 整段算一个值，已声明长度时恒为 1）；01/02 为位/线圈个数。"
            + "请求帧里的寄存器数 = 03/04 时 数量×start 处寄存器跨度，01/02 即本值",
            examples = {"1", "3"})
    public Integer quantity;

    // ===== 写操作 05/06/0F/10 =====

    @Schema(description = "写条目（05/06/0F/10）：逐条引用一个寄存器（registryIndex = ModbusRegistry.index）"
            + "并给原始值。05 单线圈一条（value 布尔）、06 单寄存器一条（value 数值）、"
            + "0F 每线圈一条（value 布尔，按地址升序首尾相接）、10 每寄存器一条（value 数值，按地址升序首尾相接）。"
            + "读命令不填，恒为 null/空。")
    public List<ModbusCommandWriteItem> writeItems;

    /** 是不是读操作（01/02/03/04）：读命令只有 {@code quantity}，没有 {@code writeItems}。 */
    public boolean isRead() {
        return ModbusCommand.isReadFc(fc);
    }

    /** 取第 i 条写条目（越界/空返回 null），供组帧与校验按位取用。 */
    public ModbusCommandWriteItem writeItem(int i) {
        if (writeItems == null || i < 0 || i >= writeItems.size()) {
            return null;
        }
        return writeItems.get(i);
    }

    /** 读操作数判：1 基功能码，大小写不敏感。 */
    public static boolean isReadFc(String fc) {
        if (fc == null) {
            return false;
        }
        String f = fc.trim().toUpperCase(java.util.Locale.ROOT);
        return "01".equals(f) || "02".equals(f) || "03".equals(f) || "04".equals(f);
    }

    /** 写多寄存器继承：区分 05/06 单条与 0F/10 多条。 */
    public boolean isMultiple() {
        if (fc == null) {
            return false;
        }
        String f = fc.trim().toUpperCase(java.util.Locale.ROOT);
        return "0F".equals(f) || "10".equals(f);
    }

    /**
     * 写操作的单个条目 = 被引用寄存器 + 原始值。
     *
     * <p>{@code value} 的类型随功能码：05/0F（位）为 {@link Boolean}，06/10（寄存器）为 {@link Number}
     * （10 的 float32 位模式在 {@code Number} 的 dtype 下以整数位模式存放，见 {@code ModbusRegistry} 上
     * 的写值约定）。解码/组帧所需的地址、数据类型、字节序、缩放/单位一律由被引用的寄存器推出。</p>
     */
    @RegisterForReflection
    @Schema(description = "写操作的单条写条目（引用寄存器 + 原始值）")
    public static class ModbusCommandWriteItem {
        @Schema(description = "被引用寄存器的序号（ModbusRegistry.index，1 起）", examples = {"2"}, required = true)
        public Integer registryIndex;

        @Schema(description = "原始值：位（05/0F）为 true/false，寄存器（06/10）为数值；"
                + "10 的 float32 按位模式（Integer 整数）给出", examples = {"true", "120"})
        public Object value;
    }
}