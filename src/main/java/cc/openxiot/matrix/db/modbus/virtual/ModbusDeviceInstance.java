package cc.openxiot.matrix.db.modbus.virtual;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 虚拟设备的实例定义信息：派生 DeviceType + 描述 + xiot-spec 实例定义（JSON 文本）。
 *
 * <p>存 {@link ModbusVirtualDevice#instance} 子文档；同一值对象在实体与生成侧复用。
 * 实例定义用 {@code DeviceInstanceCodec} 编码为 JSON 文本落库，不做二次解析存储。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "虚拟设备实例：派生 DeviceType + 描述 + 实例定义（DeviceInstanceCodec 编码 JSON）")
public class ModbusDeviceInstance {

    @Schema(description = "虚拟设备 DeviceType（派生：继承点表 type + vendor=父 did + model=配置 ID）", required = true)
    public String type;

    @Schema(description = "设备描述（取点表配置描述）", required = false)
    public String description;

    @Schema(description = "生成的实例定义（DeviceInstanceCodec 编码 JSON）", required = true)
    public String definition;
}
