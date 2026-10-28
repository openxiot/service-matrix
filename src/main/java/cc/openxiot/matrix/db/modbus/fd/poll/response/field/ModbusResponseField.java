package cc.openxiot.matrix.db.modbus.fd.poll.response.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 应答帧里的一个切分点：**引用点表里的一只寄存器**。
 *
 * <p>身份与切片/解值全部由被引用的 {@code ModbusRegistry} 决定：type/字节序/缩放/单位/枚举映射
 * （{@code enumMap}）/位区都从寄存器取。寄存器中心允许重名，字段身份用 {@link #registryIndex}
 * （唯一）而不是名字，故同类读里出现重名寄存器不再互相覆盖。</p>
 *
 * <p>invoke 返回值以被引用寄存器的<b>地址</b>作 key（地址跨四区可能重复时以 area+address 消解）。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "应答字段解析规则（按寄存器引用）")
public class ModbusResponseField {

    @Schema(description = "被读寄存器的序号（configSnapshot.registries[].index，必须存在）",
            examples = {"31"}, required = true)
    public Integer registryIndex;
}