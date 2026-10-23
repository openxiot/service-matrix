package cc.openxiot.matrix.db.modbus.service.poll.response.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 应答字段的取值表条目（点表的枚举语义）：原始值 = {@link #value} 时输出 {@link #description}。
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "取值表条目")
public class ModbusResponseFieldValue {

    @Schema(description = "原始值（按字段 format 解出的值，未应用 scale）", examples = {"0"}, required = true)
    public Long value;

    @Schema(description = "该原始值对应的语义文案", examples = {"ON"}, required = true)
    public String description;
}
