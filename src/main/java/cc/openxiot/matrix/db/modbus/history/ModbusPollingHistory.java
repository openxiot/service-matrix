package cc.openxiot.matrix.db.modbus.history;

import cc.openxiot.matrix.db.modbus.shadow.ModbusShadow;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;

import java.util.Date;
import java.util.Map;

/**
 * Modbus 服务的**操作日志**：一次采集（一个读块的一次调用）一条（库 {@code modbus}、集合
 * {@code service-polling-histories}）。
 *
 * <p><b>以操作（调用）为中心</b>：它回答「这台服务这段时间**调用得怎么样**」—— 每次读了/写了、
 * 成没成、失败是哪个分类 / 哪个远端码、带回来的响应快照。与 {@link ModbusRegistryHistory}
 * （逐寄存器事件，喂曲线）和 {@link ModbusShadow}（逐寄存器当前值，喂卡片）是**同一份事实
 * 的三个粒度的投影**：这条记调用本身，那两条记每个寄存器具体落到多少。</p>
 *
 * <p><b>每次调用一条，跟「值变没变」无关</b>：这是被接受的行数放大的一部分（见整改方案「写路径
 * 放大已接受」），靠保留策略/TTL 兜底。轮询明细、失败统计都是从这一层过滤 —— 得失成败是**操作**的
 * 属性，不再单独存一张 {@code service-failures}。</p>
 */
@RegisterForReflection
@Schema(description = "Modbus 服务一次采集（读/写）的操作记录，含成败")
public class ModbusPollingHistory {

    @BsonId
    @Schema(description = "主键（MongoDB ObjectId）", required = false)
    public ObjectId id;

    @Schema(description = "服务 id（ModbusService 主键的十六进制字符串）", required = true)
    public String serviceId;

    @Schema(description = "读块序号（ModbusPoll.index，操作日志按读块记）", examples = {"1"}, required = true)
    public Integer pollIndex;

    @Schema(description = "操作类型：read（读） / write（写）", required = true)
    public String operation;

    /** 这次调用是否成功。{@code true} = 成功（写 {@link #fields}）；{@code false} = 失败（写下面三样）。 */
    @Schema(description = "本次调用是否成功", required = true)
    public Boolean ok;

    @Schema(description = "失败分类（ModbusFailureType 的名字，前端按它聚合；成功为 null）",
            examples = {"NO_RESPONSE"})
    public String type;

    @Schema(description = "远端返回的原始码（SLAVE_EXCEPTION 是 Modbus 异常码、DEVICE_ERROR 是父设备状态码；成功为 null）",
            examples = {"2"})
    public Integer remoteCode;

    @Schema(description = "失败明细（异常文本，已截断；成功为 null）")
    public String message;

    @Schema(description = "调用时刻（UTC）")
    public Date at;

    /** 成功读到时的响应快照（字段 → 值）；写方法 / 失败调用无。 */
    @Schema(description = "成功读的响应快照（字段 → 值）；写方法 / 失败调用为空")
    public Map<String, Object> fields;
}