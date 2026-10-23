package cc.openxiot.matrix.db.modbus.service.transaction.request;

import cc.openxiot.matrix.db.modbus.service.transaction.response.field.ModbusTransactionResponseField;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 请求帧里要写入的一个字段：写方法（fc 05/06/0F/10）的数据区由若干这样的字段拼出来。
 *
 * <p>与应答侧的 {@link ModbusTransactionResponseField}
 * 是一对：那个描述「从数据区第几段开始、多少字节、怎么解」，这个描述「写到第几个位置、多少位宽、怎么排」。
 * 应答字段是 invoke 返回值的 key，这里的 {@link #field} 是 invoke 入参取值的 key。</p>
 *
 * <p>{@link #value} 是<b>缺省值</b>（原始值，与点表 {@code ModbusCommand.registerValue} 同口径），
 * 让「运行模式 = 制冷」这类枚举点开箱即用，同时又能被 invoke 传入的值覆盖；
 * 不填则每次调用都必须给值。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "写入字段定义")
public class ModbusTransactionRequestField {

    @Schema(description = "字段序号（1 起自然数，同一写请求内唯一）：决定该字段在数据区里的先后位置，"
            + "与数据区偏移由它累加推出", examples = {"1"}, required = true)
    public Integer index;

    @Schema(description = "字段名称：invoke 请求体里取值的 key，同一写请求内唯一", examples = {"设定温度"}, required = true)
    public String field;

    @Schema(description = "0 基偏移（相对 start）：10 为寄存器偏移、0F 为位偏移。05/06 只有单值、"
            + "偏移恒为 0，必须不填", examples = {"1"})
    public Integer offset;

    @Schema(description = "数据格式：bit（05/0F）/ int16/uint16/int32/uint32/float32（06/10）",
            examples = {"uint16"}, required = true)
    public String format;

    @Schema(description = "字节序（跨度 > 1 个寄存器时必填）：ABCD 大端 / DCBA 小端 / BADC 字内字节交换 / CDAB 字交换",
            examples = {"ABCD"})
    public String byteOrder;

    @Schema(description = "缺省值（原始值，不做工程量换算）：invoke 没给这个字段时用它；不填则该字段每次必给。"
            + "位字段为布尔，寄存器字段为数值", examples = {"1"})
    public Object value;
}
