package cc.openxiot.matrix.db.modbus.config;

import cc.openxiot.common.person.Person;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Modbus 设备点表配置：从站设备信息（{@link ModbusSlave}）+ 有序功能码动作列表。
 *
 * <p>独立库 {@code modbus}/集合 {@code configs}（沿用 service-matrix-modbus 的落库，
 * 字符串主键写入 {@code _id}，与既有数据兼容）；持久化经 {@code ModbusConfigRepository}（Panache）。
 */
@MongoEntity(database = "modbus", collection = "configs")
@RegisterForReflection
@Schema(description = "Modbus 设备点表配置（从站设备信息 + 有序功能码动作列表）")
public class ModbusConfig extends ReactivePanacheMongoEntityBase {

    @BsonId
    @Schema(description = "配置 ID（服务端生成）", required = true)
    public String id;

    @Schema(description = "所属组织 ID", required = true)
    public String orgId;

    @Schema(description = "可见度：private 私有 / public 公开", examples = {"private"}, required = true)
    public String visibility;

    @Schema(description = "从站设备信息（厂家/型号/从站地址/设备类型/描述）", required = true)
    public ModbusSlave slave = new ModbusSlave();

    @Schema(description = "功能码动作列表（有序；每个动作按功能码读一个参数或写一段）", required = true)
    public List<ModbusCommand> commands;

    @Schema(description = "创建者/创建时间")
    public Person creator;

    @Schema(description = "最后更新者/更新时间")
    public Person updater;
}
