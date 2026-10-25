package cc.openxiot.matrix.db.modbus.shadow;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;

import java.util.Date;

/**
 * Modbus设备的**寄存器当前值（影子）**：一只寄存器**最后一次调用**的结果（库 {@code modbus}、
 * 集合 {@code service-shadows}）。
 *
 * <p><b>以寄存器为中心</b>：一条 = 一只寄存器。承接原 {@code service-last-polling} 的三件职责
 * （给前端看最新值 / 作为下一次采集的**变化比对基准** / 记住上次调用是否失败），只是粒度从
 * 「方法（读块）」落到「寄存器」。文档数 = 服务数 × 寄存器数，与采样频率无关；成功与失败共用一个
 * 文档（要么是值，要么是一个故障），让每次读取只读一次库。</p>
 *
 * <p><b>失败时 {@link #value} / {@link #recordedAt} 一律不动</b>：它们是「最后一次成功的采集」，
 * 前端靠它们显示最后已知值（及它有多旧），清掉就等于把值丢了；这次失败的 {@link #lastError} /
 * {@link #errorAt} 另记。</p>
 *
 * <p><b>名称/单位不在此处快照</b>：身份看 {@link #registerIndex}，展示名与单位一律以服务定义
 * {@code configSnapshot.registries} 里的 {@code reg.index} / {@code reg.name} / {@code reg.unit}
 * 为准。</p>
 */
@RegisterForReflection
@Schema(description = "Modbus设备某只寄存器的当前值（最后一次调用的结果，兼作变化比对基准与故障态）")
public class ModbusShadow {

    @BsonId
    @Schema(description = "主键（MongoDB ObjectId）", required = false)
    public ObjectId id;

    @Schema(description = "服务 id（ModbusDevice 主键的十六进制字符串）", required = true)
    public String serviceId;

    @Schema(description = "寄存器序号（ModbusRegistry.index，点表内的稳定身份）", examples = {"1"}, required = true)
    public Integer registerIndex;

    @Schema(description = "最后一次**成功**采集的值：数值，或 value-list 的 description，或位展开的 0|1")
    public Object value;

    @Schema(description = "最后一次**成功**采集的落库时间（UTC）：保全周期的判定依据")
    public Date recordedAt;

    @Schema(description = "最后一次调用失败的原因；null = 最后一次调用是成功的。"
            + "下一次成功会把它清掉，前端据此判断当前值是不是陈旧的")
    public String lastError;

    @Schema(description = "当前这条故障首次出现的时刻（UTC）。同一故障持续期间不刷新")
    public Date errorAt;
}