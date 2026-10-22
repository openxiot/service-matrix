package cc.openxiot.matrix.db.modbus.config;

import cc.openxiot.common.person.Person;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Modbus 设备点表配置：从站设备信息（{@link ModbusSlave}）+ 有序功能码动作列表。
 *
 * <p>独立库 {@code modbus}/集合 {@code configs}；主键 {@code _id} 由系统生成（ObjectId），
 * 对外（JSON / URL）一律以十六进制字符串呈现；持久化经 {@code ModbusConfigRepository}。</p>
 */
@RegisterForReflection
@Schema(description = "Modbus 设备点表配置（从站设备信息 + 有序功能码动作列表）")
public class ModbusConfig {

    @BsonId
    @Schema(description = "主键（MongoDB ObjectId，十六进制字符串对外）", required = false)
    public ObjectId id;

    @Schema(description = "归属主体（id + type organization/user + name 快照）", required = true)
    public ModbusConfigOwner owner;

    @Schema(description = "可见度：private 私有 / public 公开", examples = {"private"}, required = true)
    public String visibility;

    @Schema(description = "从站设备信息（厂家/型号/从站地址/描述）", required = true)
    public ModbusSlave slave = new ModbusSlave();

    @Schema(description = "寄存器/点位定义列表（以寄存器为核心的点表，**可空**——为空时点表行为与纯 commands 一致；新配置想用寄存器中心再填）", required = false)
    public List<ModbusRegistry> registries;

    @Schema(description = "功能码动作列表（有序；显式命令 = 用户手动添加的批量读/写。单寄存器读以寄存器表隐式生成，不再放这里）", required = false)
    public List<ModbusCommand> commands;

    @Schema(description = "生命周期：development 开发 / preview 预览 / released 已发布（仅 development 可修改）",
            examples = {"development"}, required = true)
    public String lifecycle;

    @Schema(description = "创建者/创建时间")
    public Person creator;

    @Schema(description = "最后更新者/更新时间")
    public Person updater;
}
