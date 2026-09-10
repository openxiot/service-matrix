package cc.openxiot.matrix.db.modbus.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Modbus 从站设备信息：一条点表配置描述的那台从站设备的身份与描述。
 *
 * <p>同一值对象三种身份复用：/config 的 createOne / updateOne 请求体中的 {@code slave} 子对象、
 * 落库在 {@link ModbusConfig#slave} 的子文档、响应 {@code slave} 对象的原样回显。
 * {@code visibility}（共享口径）与 {@code commands}（功能码动作）属配置本身，留在 {@link ModbusConfig} 顶层。</p>
 *
 * @deprecated 设备类型 {@code type} 字段及其类型 {@link ModbusDeviceType} 已废弃不用（新方案里服务只认
 * 点表与依赖设备，不再需要产品品类/规范快照）。代码暂留以便回滚与兼容存量文档，新代码不要再读写它。
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModbusSlave {

    @Schema(description = "厂家/品牌，如 特灵/开利/麦克维尔", required = true)
    public String manufacturer;

    @Schema(description = "设备型号，如 19XRV/CVHG", required = true)
    public String model;

    @Schema(description = "Modbus 从站地址 0-247", examples = {"1"}, required = true)
    public Integer slaveId;

    /** @deprecated 已废弃不用，暂留（见类注释）；存量文档里可能仍有值。 */
    @Deprecated
    @Schema(description = "设备类型（品类 DeviceType + 多语文案快照）——已废弃，新代码不要读写", required = false)
    public ModbusDeviceType type;

    @Schema(description = "描述（可选）", required = false)
    public String description;
}
