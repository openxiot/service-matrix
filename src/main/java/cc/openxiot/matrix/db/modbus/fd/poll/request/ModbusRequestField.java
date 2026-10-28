package cc.openxiot.matrix.db.modbus.fd.poll.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 写方法（fc 05/06/0F/10）数据区里要写入的一个点：**引用点表里的一只寄存器**。
 *
 * <p>字段的身份与全部解码/编码元数据（地址、区、数据类型、字节序）都落在被引用的
 * {@code ModbusRegistry} 上，这里只留「写哪只寄存器 + 缺省写入值」。寄存器中心的点表允许
 * 寄存器重名，故字段用 {@link #registryIndex}（唯一）而不再用名字。</p>
 *
 * <p>{@link #value} 是<b>缺省值</b>（原始值，与被引用寄存器的 {@code dataType} 同口径），
 * 让「运行模式 = 制冷」这类枚举点开箱即用，同时又能被 invoke 传入的值覆盖；
 * 不填则每次调用都必须给值。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "写入字段定义（按寄存器引用）")
public class ModbusRequestField {

    @Schema(description = "被写寄存器的序号（configSnapshot.registries[].index，必须存在）",
            examples = {"31"}, required = true)
    public Integer registryIndex;

    @Schema(description = "缺省值（原始值，不做工程量换算）：invoke 没给这个字段时用它；不填则该字段每次必给。"
            + "位字段为布尔，寄存器字段为数值", examples = {"1"})
    public Object value;
}