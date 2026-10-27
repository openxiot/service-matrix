package cc.openxiot.matrix.db.modbus.fd.poll.response.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * 应答帧里的一个字段：描述「从数据区第几段开始、多少字节、怎么解」。
 *
 * <p>一个方法的应答数据区按 {@link #index} 升序、以 {@link #bytes} 依次累加偏移切分，
 * 故各字段的 bytes 之和必须等于应答帧的 byteCount（跳过 slave/fc/byteCount 头与 CRC 尾之后的数据区）。</p>
 *
 * <p>01/02 读位的应答是**按位打包**的字节区，整段只能是一个字段（{@link #bitList} 让其中的若干位
 * 各自再作为一个 0/1 取值输出，字段自身的值仍是整段位掩码）。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "应答字段解析规则")
public class ModbusResponseField {

    @Schema(description = "字段序号（1 起自然数，同一应答内唯一）：决定该字段在数据区里的先后位置",
            examples = {"1"}, required = true)
    public Integer index;

    @Schema(description = "字段名称：invoke 返回值的 key", examples = {"进水温度"}, required = true)
    public String field;

    @Schema(description = "该字段占用的字节数", examples = {"2"}, required = true)
    public Integer bytes;

    @Schema(description = "数据格式：int8/uint8/int16/uint16/int32/uint32/float32/string",
            examples = {"uint16"}, required = true)
    public String format;

    @Schema(description = "字节序（bytes > 1 时必填）：ABCD 大端 / DCBA 小端 / BADC 字内字节交换 / CDAB 字交换",
            examples = {"ABCD"})
    public String byteOrder;

    @Schema(description = "缩放系数（未命中 value-list 时对读值生效，缺省 1 不缩放）", examples = {"0.1"})
    public Double scale;

    @Schema(description = "单位（展示用，如 ℃ / %），不影响取值", examples = {"℃"})
    public String unit;

    @Schema(description = "取值表：原始值命中 value 时，直接以对应 description 作为字段值（不再应用 scale）")
    @BsonProperty("value-list")
    @JsonProperty("value-list")
    public List<ModbusResponseFieldValue> valueList;

    @Schema(description = "位清单（01/02 位区）：把本字段覆盖的位区里的若干位各自作为一个 0/1 取值输出，"
            + "取值的 key 即各位的 field；本字段自身的值仍是整段位掩码")
    @BsonProperty("bit-list")
    @JsonProperty("bit-list")
    public List<ModbusResponseFieldBit> bitList;
}
