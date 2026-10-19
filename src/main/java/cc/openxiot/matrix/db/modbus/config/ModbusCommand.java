package cc.openxiot.matrix.db.modbus.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Modbus 设备点表中的一个“功能码动作”。
 *
 * <p>以功能码为中心：一行 = 一次读操作（01/02 读一个位区，逐位具名见 {@link #bitNames}；
 * 03/04 读 {@code quantity} 个值，逐个具名见 {@link #fields}），或一次写操作
 * （05 单线圈、06 单寄存器、0F 多线圈、10 多寄存器）。
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

    @Schema(description = "序号（1 起自然数）：功能码动作在点表内的顺序；前端排序依赖它",
            examples = {"1"}, required = true)
    public Integer index;

    @Schema(description = "起始地址 = 0 基数据地址（线上值；逻辑地址由前端按 fc 区段换算）", examples = {"0", "10", "16384"}, required = true)
    public Integer start;

    // ===== 读操作 01/02/03/04 =====

    @Schema(description = "数量：03/04 为**值的个数**（每个值占数据格式的寄存器跨度 int16/uint16→1、int32/uint32/float32→2；"
            + "string 时即字符串长度、整段算一个值，故恒为 1 个字段）；01/02 为位/线圈个数。"
            + "请求帧里的数量 = 03/04 非 string 时 数量×跨度、其余即本值",
            examples = {"1", "3"})
    public Integer quantity;

    @Schema(description = "应答字段（03/04 读寄存器；个数即应答字段数：非 string 为 quantity 个、string 为 1 个）。"
            + "以寄存器为中心的命令每条字段自带 名称/缩放/单位，一次读多寄存器各字段缩放可独立；"
            + "缺失或某项名称为空时由消费方按功能名称兜底；01/02 读位请用 bitNames，写操作无应答字段，不填")
    public List<Field> fields;

    // ===== 存量迁移（仅读，不出现在 JSON API） =====

    /**
     * 旧版点表（fieldNames→fields 对齐前）把 03/04 应答名存在 {@code fieldNames}（字符串数组）、
     * 缩放/单位挂在命令级 {@code scale}/{@code unit}。这三项是<b>迁移专用</b>：
     * 由 Mongo POJO codec 从存量文档解码（故配 {@link BsonProperty}），经
     * {@link #upgradeLegacyFields} 归一成 {@link #fields} 后即清空，**不进入 JSON API**（配 {@link JsonIgnore}），
     * 也不重复持久化。新建/新改一律直接写 {@link #fields}，这三项恒为 null。
     */
    @JsonIgnore
    @BsonProperty("fieldNames")
    public List<String> legacyFieldNames;

    @JsonIgnore
    @BsonProperty("scale")
    public Double legacyScale;

    @JsonIgnore
    @BsonProperty("unit")
    public String legacyUnit;

    /**
     * 把存量的 fieldNames/命令级 scale/unit 升级成 {@link #fields}（每字段独立 name + 复制命令级 scale/unit），
     * 顺便清掉迁移字段，使读后不残留、后续 replace 只落新形状。幂等：`fields` 已存在或没有 legacy 名则不动。
     */
    public static void upgradeLegacyFields(ModbusCommand c) {
        if (c == null || c.fields != null || c.legacyFieldNames == null || c.legacyFieldNames.isEmpty()) {
            return;
        }
        List<Field> fields = new ArrayList<>(c.legacyFieldNames.size());
        for (String name : c.legacyFieldNames) {
            Field f = new Field();
            f.name = name == null || name.isBlank() ? null : name;
            f.scale = c.legacyScale;
            // unit 为空串视作未配
            f.unit = c.legacyUnit == null || c.legacyUnit.isBlank() ? null : c.legacyUnit;
            fields.add(f);
        }
        c.fields = fields;
        c.legacyFieldNames = null;
        c.legacyScale = null;
        c.legacyUnit = null;
    }

    @Schema(description = "位名称（01/02 读位）：给读回的位区里的若干位各自命名，取值 0/1；缺省表示只按整段位掩码出字段。"
            + "offset 为位偏移（0 基，从起始地址起，与 0F 的 Coil.offset 同口径）",
            examples = {"[{\"offset\":0,\"name\":\"进水阀\"},{\"offset\":1,\"name\":\"出水阀\"}]"})
    public List<BitName> bitNames;

    @Schema(description = "数据格式（03/04）：int16/uint16/int32/uint32/int64/uint64/float32/string；01/02 固定为 位/布尔，无需填写", examples = {"float32"})
    public String dataType;

    @Schema(description = "字节序（03/04，多寄存器值按 32 位四种排布）：ABCD 大端 / DCBA 小端 / BADC 字内字节交换 / CDAB 字交换", examples = {"ABCD"})
    public String byteOrder;

    // ===== 写操作 05/06/0F/10 =====

    @Schema(description = "线圈状态（05 写单线圈）：on 打开 / off 关闭", examples = {"on"})
    public String coilState;

    @Schema(description = "寄存器值（06 写单寄存器，原始值；可 -32768..65535）", examples = {"0", "120"})
    public Long registerValue;

    @Schema(description = "线圈选择（0F 写多线圈）：每个线圈一个条目（offset + 状态），列表长度即线圈数量")
    public List<Coil> coils;

    @Schema(description = "寄存器明细（10 写多寄存器）：逐寄存器填 地址/数据类型/字节序/原始数值；地址须连续，行数即写入寄存器个数")
    public List<Register> registers;

    /** 01/02 读位的单个位名称。 */
    @RegisterForReflection
    @Schema(description = "单个位名称")
    public static class BitName {
        @Schema(description = "位偏移（从起始地址起，0 基，< 位/线圈个数）", examples = {"0"})
        public Integer offset;

        @Schema(description = "位名称（如 进水阀）", examples = {"进水阀"})
        public String name;
    }

    /** 0F 写多线圈的单个线圈选择。 */
    @RegisterForReflection
    @Schema(description = "单个线圈选择")
    public static class Coil {
        @Schema(description = "相对偏移（从起始地址起，0 基，< 线圈数量）", examples = {"0"})
        public Integer offset;

        @Schema(description = "状态：true 打开(ON) / false 关闭(OFF)", examples = {"true"})
        public Boolean on;
    }

    /** 03/04 读寄存器的单个应答字段（以寄存器为中心：名/缩放/单位，各字段可独立）。 */
    @RegisterForReflection
    @Schema(description = "03/04 读寄存器的单个应答字段")
    public static class Field {
        @Schema(description = "字段名（应答时作为值的 key/表头）", examples = {"A相电压"})
        public String name;

        @Schema(description = "该字段缩放系数（读值转工程量用；缺省视为 1）", examples = {"0.1"})
        public Double scale;

        @Schema(description = "该字段单位（读值展示用），如 ℃ / °F / % / kPa", examples = {"℃"})
        public String unit;
    }

    /** 10 写多寄存器的单个寄存器条目。 */
    @RegisterForReflection
    @Schema(description = "单个寄存器条目")
    public static class Register {
        @Schema(description = "数据地址（0 基；须与上一条目按类型跨度连续）", examples = {"10"})
        public Integer address;

        @Schema(description = "数据格式：int16/uint16/int32/uint32/int64/uint64/float32", examples = {"uint16"})
        public String dataType;

        @Schema(description = "字节序：ABCD/DCBA/BADC/CDAB", examples = {"ABCD"})
        public String byteOrder;

        @Schema(description = "原始数值（按 dataType 取值，如 uint32/float32 可为 0..0xFFFFFFFF 位模式）", examples = {"120"})
        public Long value;
    }
}