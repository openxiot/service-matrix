package cc.openxiot.matrix.db.modbus;

import cc.openxiot.common.person.Person;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Modbus 设备点表配置：厂家 + 型号 + 描述 + 有序功能码动作列表。
 *
 * <p>独立库 {@code modbus}/集合 {@code device-configs}（沿用 service-matrix-modbus 的落库，
 * 字符串主键写入 {@code _id}，与既有数据兼容）；持久化经 {@code ModbusDeviceConfigRepository}（Panache）。
 */
@MongoEntity(database = "modbus", collection = "device-configs")
@RegisterForReflection
@Schema(description = "Modbus 设备点表配置（厂家型号 + 有序功能码动作列表）")
public class ModbusDeviceConfig extends ReactivePanacheMongoEntityBase {

    @BsonId
    @Schema(description = "配置 ID（服务端生成）", required = true)
    public String id;

    @Schema(description = "所属组织 ID", required = true)
    public String orgId;

    @Schema(description = "厂家/品牌，如 特灵/开利/麦克维尔", required = true)
    public String manufacturer;

    @Schema(description = "设备型号，如 19XRV/CVHG", required = true)
    public String model;

    @Schema(description = "Modbus 从站地址 0-247", examples = {"1"}, required = true)
    public Integer slaveId;

    @Schema(description = "可见度：private 私有 / public 公开", examples = {"private"}, required = true)
    public String visibility;

    @Schema(description = "描述（可选）", required = false)
    public String description;

    @Schema(description = "功能码动作列表（有序；每个动作按功能码读一个参数或写一段）", required = true)
    public List<ModbusCommand> commands;

    @Schema(description = "创建者/创建时间")
    public Person creator;

    @Schema(description = "最后更新者/更新时间")
    public Person updater;
}
