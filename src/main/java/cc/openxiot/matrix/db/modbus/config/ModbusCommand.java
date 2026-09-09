package cc.openxiot.matrix.db.modbus.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Modbus 设备点表中的一个“功能码动作”。
 *
 * <p>旧版以“点位”为中心（area/address/rw 一列一个点），现改为以功能码为中心：
 * 一行 = 一次读操作（读一个参数，{@code quantity} = 该参数占用的寄存器/位个数），
 * 或一次写操作（05 单线圈、06 单寄存器、0F 多线圈、10 多寄存器）。
 *
 * <p>读写方向与“寄存器区”均由功能码决定（01/05/0F=线圈、02=离散输入、03/06/10=保持寄存器、04=输入寄存器），
 * 因此不再存 area/rw。逻辑地址不落库，由前端按 {@code fc}+{@code start} 实时换算。
 *
 * <p>功能码词表（十六进制字符串）：01 读线圈 / 02 读离散输入 / 03 读保持寄存器 / 04 读输入寄存器 /
 * 05 写单线圈 / 06 写单寄存器 / 0F 写多线圈 / 10 写多寄存器。
 */
@RegisterForReflection
@Schema(description = "Modbus 功能码动作（读参数或写操作）")
public class ModbusCommand {

    @Schema(description = "功能名称（用户可改），如 读蒸发器进水温度", required = true)
    public String name;

    @Schema(description = "功能码：01/02/03/04/05/06/0F/10（十六进制大写）", examples = {"03"}, required = true)
    public String fc;

    @Schema(description = "序号（1 起自然数）：功能码动作在点表内的顺序；前端排序依赖它，生成虚拟设备实例时 action 的 iid = 该 index",
            examples = {"1"}, required = true)
    public Integer index;

    @Schema(description = "起始地址 = 0 基数据地址（线上值；逻辑地址由前端按 fc 区段换算）", examples = {"0", "10", "16384"}, required = true)
    public Integer start;

    // ===== 读操作 01/02/03/04 =====

    @Schema(description = "数量：该参数占用的寄存器/位个数（int16/uint16→1，int32/uint32/float32→2，string→自定；01/02 位→通常 1）", examples = {"1", "2"})
    public Integer quantity;

    @Schema(description = "数据格式（03/04）：int16/uint16/int32/uint32/float32/string；01/02 固定为 位/布尔，无需填写", examples = {"float32"})
    public String dataType;

    @Schema(description = "字节序（03/04，多寄存器值按 32 位四种排布）：ABCD 大端 / DCBA 小端 / BADC 字内字节交换 / CDAB 字交换", examples = {"ABCD"})
    public String byteOrder;

    @Schema(description = "缩放系数（03/04 读值转工程量用）", examples = {"0.1"})
    public Double scale;

    @Schema(description = "单位（03/04 读值展示用），如 ℃ / °F / % / kPa", examples = {"℃"})
    public String unit;

    // ===== 写操作 05/06/0F/10 =====

    @Schema(description = "线圈状态（05 写单线圈）：on 打开 / off 关闭", examples = {"on"})
    public String coilState;

    @Schema(description = "寄存器值（06 写单寄存器，原始值；可 -32768..65535）", examples = {"0", "120"})
    public Long registerValue;

    @Schema(description = "线圈选择（0F 写多线圈）：每个线圈一个条目（offset + 状态），列表长度即线圈数量")
    public List<Coil> coils;

    @Schema(description = "寄存器明细（10 写多寄存器）：逐寄存器填 地址/数据类型/字节序/原始数值；地址须连续，行数即写入寄存器个数")
    public List<Register> registers;

    /** 0F 写多线圈的单个线圈选择。 */
    @RegisterForReflection
    @Schema(description = "单个线圈选择")
    public static class Coil {
        @Schema(description = "相对偏移（从起始地址起，0 基，< 线圈数量）", examples = {"0"})
        public Integer offset;

        @Schema(description = "状态：true 打开(ON) / false 关闭(OFF)", examples = {"true"})
        public Boolean on;
    }

    /** 10 写多寄存器的单个寄存器条目。 */
    @RegisterForReflection
    @Schema(description = "单个寄存器条目")
    public static class Register {
        @Schema(description = "数据地址（0 基；须与上一条目按类型跨度连续）", examples = {"10"})
        public Integer address;

        @Schema(description = "数据格式：int16/uint16/int32/uint32/float32", examples = {"uint16"})
        public String dataType;

        @Schema(description = "字节序：ABCD/DCBA/BADC/CDAB", examples = {"ABCD"})
        public String byteOrder;

        @Schema(description = "原始数值（按 dataType 取值，如 uint32/float32 可为 0..0xFFFFFFFF 位模式）", examples = {"120"})
        public Long value;
    }
}
