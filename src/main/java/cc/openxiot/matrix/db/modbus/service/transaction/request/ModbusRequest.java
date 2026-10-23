package cc.openxiot.matrix.db.modbus.service.transaction.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * 请求帧的结构化定义：一次调用的请求帧由本对象在 invoke 时现组（含 CRC16），不再由前端算好一整个
 * 16 进制字符串塞进来。
 *
 * <p>与点表的 {@code ModbusCommand} 同为 <b>fc 中心</b>：读写方向与寄存器区都由 {@link #fc} 推出，
 * 模型里没有 {@code area} / {@code rw} 字段。方向决定另外两个字段谁有效，二者互斥：</p>
 *
 * <ul>
 *   <li><b>读</b>（01/02/03/04）：只有 {@link #quantity}，{@link #fields} 必须为空 —— 读请求里
 *       没有「写入的字段」这回事。</li>
 *   <li><b>写</b>（05/06/0F/10）：只有 {@link #fields}，{@link #quantity} 必须不填 ——
 *       帧里的数量由字段的偏移与跨度推出来（{@code 0F} 是位数、{@code 10} 是寄存器数），
 *       再让人填一遍就是第二个真相来源。</li>
 * </ul>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "请求帧定义（invoke 时现组帧）")
public class ModbusRequest {

    @Schema(description = "从站地址（帧首字节）；同一服务的各方法必须一致，1..247", examples = {"1"}, required = true)
    public Integer slaveId;

    @Schema(description = "功能码：01/02/03/04 读 · 05/06/0F/10 写",
            examples = {"03"}, required = true)
    public String fc;

    @Schema(description = "起始地址（0 基线上地址，与点表 ModbusCommand.start 同口径），0..65535",
            examples = {"0"}, required = true)
    public Integer start;

    @Schema(description = "读方法的数量：**帧里那个数量字段的字面值**（01/02 为位数、03/04 为寄存器数）。"
            + "写方法必须不填（由 fields 推出）。注意与点表口径不同：点表 quantity 是「值的个数」，"
            + "按单条命令的唯一 dataType 乘跨度换算；服务里每个应答字段各带自己的 format/bytes，"
            + "请求侧无从得知每个值占多宽，故这里直接写线上数字，并在校验层与 response 对账",
            examples = {"2"})
    public Integer quantity;

    @Schema(description = "写方法的写入字段：决定数据区的内容与排列；读方法必须为空")
    public List<ModbusRequestField> fields;
}
