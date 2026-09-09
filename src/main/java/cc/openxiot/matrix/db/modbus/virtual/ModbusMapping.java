package cc.openxiot.matrix.db.modbus.virtual;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Modbus 虚拟设备的映射信息：把一条点表配置虚拟成父设备下一子设备所需的挂载描述。
 *
 * <p>同一值对象三种身份复用：createOne 的请求体、落库在 {@link ModbusVirtualDevice#mapping} 的子文档、
 * 响应 {@code mapping} 字段的原样回显（与实体不再各自平铺重复字段）。
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModbusMapping {

    @Schema(description = "被虚拟化的 Modbus 点表配置 ID", required = true)
    public String configId;

    @Schema(description = "父设备 ID（虚拟设备挂载到其下）", required = true)
    public String did;

    @Schema(description = "父设备服务 ID（siid）", required = true)
    public int siid;

    @Schema(description = "父设备方法 ID（aiid）", required = true)
    public int aiid;
}
