package cc.openxiot.matrix.db.modbus.history;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Date;

/**
 * 一次 function 执行失败的记录（库 {@code modbus}、集合 {@code service-failures}）。
 *
 * <p>为什么单独一张表、而不是并进 {@link ModbusServiceHistory}：失败里**没有「字段 → 值」**。
 * 一次失败对应的是整个 function —— 帧 CRC 都不符的时候，哪个字段都没读到，
 * 塞进历史表就得为一堆字段留空值，而空值在这份数据里另有含义（见 Modbus-History.md 第二节）。</p>
 *
 * <p><b>只在故障「变了」的时候落一条</b>，同一条错误持续存在不重复落
 * （判定在 {@code ModbusHistoryService.recordFailure}）。DTU 离线三天是最常见的形态：
 * 5 秒轮询、逐次落库是一天一万七千条，而信息量和一条完全一样。
 * 故障持续到什么时候，由这一条到**下一条成功样本**之间的空隙推出来 ——
 * 这也正是历史那三条规则里「保活打点」存在的理由（没有它，「值稳定」和「采集挂了」长得一样）。</p>
 *
 * <p>失败**不打保活点**：一个还在持续的故障，靠上面那个「空隙」已经能看出来，
 * 再按周期重复落一条纯属重复计费。</p>
 *
 * <p><b>失败原因是分类的，不是一段文本</b>（{@link #type} + {@link #remoteCode} + {@link #message}）：
 * 前端要按故障类型聚合，而从 message 文本里反推类型等于把协议语义压成了字符串匹配。
 * 分类由抛出点决定（见 {@code ModbusFailureType}），这里只存名字 —— 存字符串不存枚举值，
 * 是为了将来枚举增删时老文档还读得出来（与 {@code SpaceEntity.type}、
 * {@code ModbusPollingResponseField.format} 同口径）。</p>
 */
@MongoEntity(database = "modbus", collection = "service-failures")
@RegisterForReflection
@Schema(description = "Modbus 服务的一次 function 执行失败记录")
public class ModbusServiceFailure extends ReactivePanacheMongoEntity {

    @Schema(description = "服务 id（ModbusService 主键的十六进制字符串）", required = true)
    public String serviceId;

    @Schema(description = "方法序号（ModbusPolling.index）", examples = {"1"}, required = true)
    public Integer pollingIndex;

    @Schema(description = "失败分类（ModbusFailureType 的名字），前端按它聚合；"
            + "分这么细是因为每一类对应不同的处置方向（设备侧 / 报文侧 / 定义侧 / 平台侧）",
            examples = {"NO_RESPONSE"}, required = true)
    public String type;

    @Schema(description = "远端返回的原始码：SLAVE_EXCEPTION 时是 Modbus 异常码"
            + "（1 非法功能 / 2 非法数据地址 / 3 非法数据值 / 4 从站故障 / 6 从站忙 / 11 网关目标无应答），"
            + "DEVICE_ERROR 时是父设备状态码；其余分类为 null。按分类聚合后靠它下钻",
            examples = {"2"})
    public Integer remoteCode;

    @Schema(description = "失败明细（异常文本，已截断）—— 它是排查时唯一能看到的东西，"
            + "也是「同一故障」的判定依据：文本相同就不重复落库", required = true)
    public String message;

    @Schema(description = "该故障首次出现的时刻（UTC）；同一故障持续期间不刷新，它到下一行成功样本的间隔就是故障持续时间")
    public Date at;
}
