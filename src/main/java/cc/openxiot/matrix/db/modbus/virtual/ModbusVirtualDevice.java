package cc.openxiot.matrix.db.modbus.virtual;

import cc.openxiot.common.person.Person;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Modbus 虚拟设备：把一条 Modbus 设备点表（配置）虚拟成一个挂在父设备下的子设备。
 *
 * <p>独立集合 {@code virtual-devices}（库 {@code modbus}）：与 {@code configs}（点表配置）分库存储，
 * 避免 Panache 双类型同集合解码错乱。{@code did} 为服务端生成的“虚拟设备 ID”，后续接入真实设备注册时作为子设备 did 使用。
 *
 * <p>持久化内容：映射信息（configId + 父 did + siid/aiid，{@link ModbusMapping}）、
 * 实例信息（派生 DeviceType + 描述 + {@code DeviceInstanceCodec} 编码后的 JSON 文本，{@link ModbusDeviceInstance}）。
 */
@MongoEntity(database = "modbus", collection = "virtual-devices")
@RegisterForReflection
@Schema(description = "Modbus 虚拟设备（将 Modbus 点表虚拟成父设备的子设备）")
public class ModbusVirtualDevice extends ReactivePanacheMongoEntityBase {

    @BsonId
    @Schema(description = "虚拟设备 ID（服务端生成，即其 did，后续接入真实设备注册时作为子设备 did）", required = true)
    public String did;

    @Schema(description = "所属组织 ID", required = true)
    public String orgId;

    @Schema(description = "映射信息：源点表配置 + 父设备挂载点（与 createOne 请求体同构）", required = true)
    public ModbusMapping mapping;

    @Schema(description = "实例信息（派生 DeviceType + 描述 + 实例定义）", required = true)
    public ModbusDeviceInstance instance = new ModbusDeviceInstance();

    @Schema(description = "创建者/创建时间")
    public Person creator;
}
