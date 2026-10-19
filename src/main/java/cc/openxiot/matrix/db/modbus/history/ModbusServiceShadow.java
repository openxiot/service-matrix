package cc.openxiot.matrix.db.modbus.history;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Date;
import java.util.Map;

/**
 * Modbus 服务的「当前值」：某个方法**最后一次调用**的结果（库 {@code modbus}、集合 {@code service-shadows}）。
 *
 * <p>一张表三个用途：给前端看最新值、作为下一次采集的**变化比对基准**（见 {@code ModbusHistoryPolicy}）、
 * 以及记住上一次调用是否失败了（{@link #lastError}）。按 function 一条、字段内嵌 ——
 * 一次调用一次写就够，文档数 = 服务数 × 方法数，与采样频率无关。</p>
 *
 * <p>成功与失败共用这一个文档，是为了让每次 invoke 只读一次库：最后一次调用的结果，
 * 本来就该是一份状态 —— 要么是值，要么是一个故障。</p>
 *
 * <p>与 {@code service-controlpoint} 的「设备影子」无关：那张影子是平台层的设备属性副本，
 * 本文档只服务 Modbus 服务的采集历史（见 Modbus-History.md）。</p>
 *
 * <p>{@code fields} 的值是异构的（数值 / {@code value-list} 的 description / 位展开的 0|1），
 * 比对一律走 {@code ModbusHistoryPolicy.sameValue}，不要在这里重写等值判断。</p>
 *
 * <p><b>失败时 {@code fields} / {@code recordedAt} 一律不动</b>：它们是「最后一次成功的采集」，
 * 前端要靠它们显示最后已知值（以及它有多旧），清掉就等于把值丢了。</p>
 */
@MongoEntity(database = "modbus", collection = "service-shadows")
@RegisterForReflection
@Schema(description = "Modbus 服务的当前值（最后一次调用的结果，兼作变化比对基准与故障态）")
public class ModbusServiceShadow extends ReactivePanacheMongoEntity {

    @Schema(description = "服务 id（ModbusService 主键的十六进制字符串）", required = true)
    public String serviceId;

    @Schema(description = "方法序号（ModbusPolling.index）", examples = {"1"}, required = true)
    public Integer functionIndex;

    @Schema(description = "最后一次**成功**采集的「字段 → 值」：数值，或 value-list 的 description，或位展开的 0|1")
    public Map<String, Object> fields;

    @Schema(description = "最后一次**成功**采集的落库时间（值变化或保活打点，UTC）：保活周期的判定依据")
    public Date recordedAt;

    @Schema(description = "最后一次调用失败的原因；null = 最后一次调用是成功的。"
            + "下一次成功会把它清掉，前端据此判断当前值是不是陈旧的")
    public String lastError;

    @Schema(description = "当前这条故障首次出现的时刻（UTC）。同一故障持续期间不刷新"
            + "—— 它到下一行成功样本的间隔就是故障持续了多久")
    public Date errorAt;
}
