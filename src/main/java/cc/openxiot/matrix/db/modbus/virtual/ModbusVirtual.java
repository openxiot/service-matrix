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
 * 避免 Panache 双类型同集合解码错乱。{@code id} 为服务端生成的“虚拟设备 ID”，后续接入真实设备注册时作为子设备 did 使用。
 *
 * <p>持久化内容：映射信息（modbusConfigId + 父 did + siid/aiid）、派生 DeviceType、生成的实例定义
 * （{@code DeviceInstanceCodec} 编码后的 JSON 文本）。
 */
@MongoEntity(database = "modbus", collection = "virtual-devices")
@RegisterForReflection
@Schema(description = "Modbus 虚拟设备（将 Modbus 点表虚拟成父设备的子设备）")
public class ModbusVirtual extends ReactivePanacheMongoEntityBase {

    @BsonId
    @Schema(description = "虚拟设备 ID（服务端生成，后续作为子设备 did）", required = true)
    public String id;

    @Schema(description = "所属组织 ID", required = true)
    public String orgId;

    @Schema(description = "被虚拟化的 Modbus 点表配置 ID", required = true)
    public String modbusConfigId;

    @Schema(description = "父设备 ID（挂载到其下）", required = true)
    public String did;

    @Schema(description = "父设备服务 ID（siid，映射信息原样保留）", required = true)
    public int siid;

    @Schema(description = "父设备方法 ID（aiid，映射信息原样保留）", required = true)
    public int aiid;

    @Schema(description = "虚拟设备 DeviceType（派生：继承点表 type + vendor=父 did + model=配置 ID）", required = true)
    public String type;

    @Schema(description = "设备描述（取点表配置描述）", required = false)
    public String description;

    @Schema(description = "生成的实例定义（DeviceInstanceCodec 编码 JSON）", required = true)
    public String definition;

    @Schema(description = "创建者/创建时间")
    public Person creator;
}
