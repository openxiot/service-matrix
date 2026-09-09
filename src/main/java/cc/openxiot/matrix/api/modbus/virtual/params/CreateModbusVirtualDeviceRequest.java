package cc.openxiot.matrix.api.modbus.virtual.params;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 创建Modbus虚拟设备的请求体。
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateModbusVirtualDeviceRequest {

    @Schema(description = "Modbus配置ID", required = true)
    public String modbusConfigId;

    @Schema(description = "设备ID", required = true)
    public String did;

    @Schema(description = "服务ID", required = true)
    public int siid;

    @Schema(description = "方法ID", required = true)
    public int aiid;
}
