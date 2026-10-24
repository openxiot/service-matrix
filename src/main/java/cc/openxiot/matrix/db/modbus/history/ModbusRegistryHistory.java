package cc.openxiot.matrix.db.modbus.history;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;

import java.util.Date;

/**
 * Modbus 服务的**寄存器历史**：一只寄存器在某次读操作下的一个事件（库 {@code modbus}、集合
 * {@code service-registry-histories}）。
 *
 * <p><b>以寄存器为中心</b>：一条 = 一只寄存器 × 一次读。成功的读落 {@link #value}（数值 /
 * {@code value-list} 的 description / 位展开的 0|1），读失败落 {@link #failure}=true 与
 * {@link #message}（见表 {@code service-failures} 的内容并入此处，见 {@code ModbusPollingHistory}
 * 的说明 —— 失败不再单独占一个集合）。曲线图（首页字段曲线 + 设备服务历史页）就是它的消费方：
 * 一条源同时供「值」（成功段）与「采集失败竖线」（{@code failure} 事件），不必再交叉查两大张表。</p>
 *
 * <p>身份是 {@link #registerIndex}（点表 {@code configSnapshot.registries[].index}，创建时快照定死，
 * 改名不扰历史），{@link #name} / {@link #unit} 只是展示快照；{@code serviceId} 是 ModbusService
 * 主键的十六进制字符串。</p>
 */
@RegisterForReflection
@Schema(description = "Modbus 服务某只寄存器的历史事件（成功值或失败标记）")
public class ModbusRegistryHistory {

    @BsonId
    @Schema(description = "主键（MongoDB ObjectId）", required = false)
    public ObjectId id;

    @Schema(description = "服务 id（ModbusService 主键的十六进制字符串）", required = true)
    public String serviceId;

    @Schema(description = "寄存器序号（ModbusRegistry.index，点表内的稳定身份）", examples = {"1"}, required = true)
    public Integer registerIndex;

    @Schema(description = "寄存器名（展示快照；身份看 registerIndex）")
    public String name;

    @Schema(description = "寄存器单位（展示快照，可空）", examples = {"℃"})
    public String unit;

    @Schema(description = "事件时刻（采集时，UTC）：曲线横轴与按时间排序的依据")
    public Date at;

    /** 成功的读采到的值：数值，或 value-list 的 description，或位展开的 0|1。失败时不落。 */
    @Schema(description = "成功的读采到的值（数值 / 描述串 / 0|1）；失败不落")
    public Object value;

    /** 这次读是否失败。{@code false}（或缺省）= 成功，落 {@link #value}；{@code true} = 失败，落 {@link #message}。 */
    @Schema(description = "这次读是否失败；失败时无 value、带 message", required = false)
    public Boolean failure;

    @Schema(description = "失败原因（读取失败时才有，见 ModbusFailureType / 异常 message）")
    public String message;
}