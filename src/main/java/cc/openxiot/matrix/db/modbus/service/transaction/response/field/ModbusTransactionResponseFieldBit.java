package cc.openxiot.matrix.db.modbus.service.transaction.response.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 位区字段（01/02 的应答）里的一个具名位：除字段自身那份整段位掩码外，额外把该位单独作为一个
 * 0/1 取值输出，取值的 key 即 {@link #field}。
 *
 * <p>偏移是 0 基、从该字段的位区起点（也就是请求的起始地址）算起，与点表
 * {@code ModbusCommand.BitName.offset}、写多线圈的 {@code Coil.offset} 同口径；帧内按 LSB-first
 * 取位，即第 {@code offset/8} 个数据字节的第 {@code offset%8} 位（低位在前，与 Modbus 规范一致）。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "位区里的具名位")
public class ModbusTransactionResponseFieldBit {

    @Schema(description = "位偏移（0 基，从位区起点算起；帧内 LSB-first：第 offset/8 字节的第 offset%8 位）",
            examples = {"0"}, required = true)
    public Integer offset;

    @Schema(description = "该位的取值名：invoke 返回值里这个位的 key（值为 0/1）", examples = {"进水阀"}, required = true)
    public String field;
}
