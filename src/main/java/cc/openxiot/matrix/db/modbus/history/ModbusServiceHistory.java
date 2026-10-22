package cc.openxiot.matrix.db.modbus.history;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Date;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;

/**
 * 一条采集样本（库 {@code modbus}、集合 {@code service-histories}）：**一个字段一个值**。
 *
 * <p>只记录「值变了」的样本和「保活打点」，不变的不落库 —— 规则与理由见 Modbus-History.md 第二节。
 * 因此这份数据**不是均匀采样序列**：相邻两条之间的时间差就是该值持续了多久，
 * 区间查询必须带 carry-in 点、统计必须按时间加权（见 Modbus-History.md 第五节）。</p>
 *
 * <p>点位的身份是 {@code (serviceId, pollingIndex, field)} 三元组，不拼 {@code pointId} 字符串：
 * 字段名是用户填的，拼串做前缀匹配迟早撞上转义问题。查询与索引都按这三个字段走
 * （见 {@link ModbusServiceHistoryRepository}）。</p>
 */
@RegisterForReflection
@Schema(description = "Modbus 采集历史的一条样本（一个字段一个值）")
public class ModbusServiceHistory {

    @BsonId
    @Schema(description = "主键（MongoDB ObjectId）", required = false)
    public ObjectId id;


    @Schema(description = "服务 id（ModbusService 主键的十六进制字符串）", required = true)
    public String serviceId;

    @Schema(description = "方法序号（ModbusPolling.index）", examples = {"1"}, required = true)
    public Integer pollingIndex;

    @Schema(description = "字段名：response 里解析出来的出值名（含 bit-list 展开的位名）", required = true)
    public String field;

    @Schema(description = "值：数值，或 value-list 的 description，或位展开的 0|1")
    public Object value;

    @Schema(description = "采样时刻（UTC）", required = true)
    public Date at;

    @Schema(description = "true = 值未变化、因保活周期到点而落的锚点；false / 缺省 = 值变化落的正常样本")
    public Boolean keepalive;
}
