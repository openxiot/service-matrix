package cc.openxiot.matrix.db.modbus.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

/**
 * Modbus 设备点表中的一个寄存器/点位定义——**以寄存器为核心**的点表。
 *
 * <p>与 {@link ModbusCommand}（功能码动作）的分工：本对象描述设备**有什么**（一个物理位的名字、
 * 地址、类型、读向、缩放、单位），而显式命令只描述用户**要额外做**的批量动作（多寄存器读、写）。
 * 单个寄存器的读由消费方（运行期轮询 / 主站调试 / 从站模拟）按本表**隐式生成**：同 area、同多寄存器
 * 类型、地址连续的寄存器归成一块，一块一条读功能码（03/04/01/02）；写（06/10/05/0F）仍走显式命令，
 * 只是目标地址/类型从此表取。</p>
 *
 * <p><b>area 四区</b>（由此决定读/写功能码）：holding→读 03 写 06/10、input→读 04、
 * coil→读 01 写 05/0F、discrete→读 02。dataType 对 holding/input 有效（16/32/64 位与 string）；
 * coil/discrete 固定为布尔，无需填。byteOrder 仅对跨多寄存器的 32 位（int32/uint32/float32，
 * 2 个寄存器）与 64 位（int64/uint64，4 个寄存器）值必填，其余忽略。</p>
 *
 * <p><b>双模式兼容</b>：{@link ModbusConfig#registries} 是可空字段，为空时点表行为与今天完全一致
 * （只有 commands）——存量已落库配置不搬、不改写。新配置想用寄存器中心再填本表。</p>
 */
@RegisterForReflection
@Schema(description = "Modbus 寄存器/点位定义（以寄存器为核心的点表）")
public class ModbusRegistry {

    @Schema(description = "点位名称（用户可改），如 A相电压", required = true)
    public String name;

    @Schema(description = "寄存器在点表里的序号（1 起自然数，寄存器间唯一；供 ModbusPollingFrom.index 引用）", examples = {"1"}, required = true)
    public Integer index;

    @Schema(description = "数据地址（0 基，线上值；同一地址可分别在四个区内各出现一次）", examples = {"0", "100"}, required = true)
    public Integer address;

    @Schema(description = "寄存器区：holding 保持寄存器(读03/写06·10) / input 输入寄存器(读04) / coil 线圈(读01/写05·0F) / discrete 离散输入(读02)", examples = {"holding", "input", "coil", "discrete"}, required = true)
    public String area;

    @Schema(description = "数据格式（holding/input）：int16/uint16/int32/uint32/int64/uint64/float32/string；coil/discrete 固定布尔免填", examples = {"float32"})
    public String dataType;

    @Schema(description = "字节序（仅跨多寄存器值必填）：ABCD 大端 / DCBA 小端 / BADC 字内字节交换 / CDAB 字交换。32 位（int32/uint32/float32）跨 2 个寄存器、64 位（int64/uint64）跨 4 个寄存器", examples = {"ABCD"})
    public String byteOrder;

    @Schema(description = "读写方向：R 只读 / W 只写 / RW 读写。只有 R 与 RW 参与隐式读", examples = {"R", "RW", "W"}, required = true)
    public String access;

    @Schema(description = "缩放系数（读值转工程量用，可选）", examples = {"0.1"})
    public Double scale;

    @Schema(description = "单位（读值展示用，可选），如 ℃ / °F / % / kPa", examples = {"℃"})
    public String unit;

    /**
     * 枚举映射（可选）：原始值(字符串) → 名称。挂在**整型** dataType（int16/uint16/int32/uint32/int64/uint64）上，
     * 作为离散状态的「标牌」——读值仍是数值，仅在展示/输入时按此翻译；未命中的值回退显示原值。
     * key 为字符串形式的原始值（如 "1"）、value 为显示名；允许部分映射，空/缺省视为无枚举。
     */
    @Schema(description = "枚举映射（可选，仅整型 dataType）：原始值(字符串)→名称，如 {\"1\":\"正常\",\"0\":\"告警\"}", examples = {"{\\\"1\\\":\\\"None\\\",\\\"2\\\":\\\"Even\\\"}"}, required = false)
    public Map<String, String> enumMap;
}