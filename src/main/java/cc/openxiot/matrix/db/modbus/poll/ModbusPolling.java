package cc.openxiot.matrix.db.modbus.poll;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Date;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;

/**
 * 一个轮询任务：某个服务的某个方法**该多久跑一次、上次跑成什么样**（库 {@code modbus}、集合 {@code polls}）。
 *
 * <p>运行时状态（{@code nextRunAt} / 租约 / 上次结果）**不写进 {@code ModbusService} 定义文档**：
 * 那是接口直接返回的、空间图投影查询也在用的文档，混进实时状态会让「读定义」变成「读实时状态」。
 * 所以另起一个派生集合，一条 = 一个 {@code (serviceId, transactionIndex)}。</p>
 *
 * <p>只有**读方法、有 {@code interval}、{@code polling != false}** 才入表（口径见 {@code ModbusPollPlan}）；
 * 定义改了由对账（{@code ModbusPollReconciler}）跟着改，服务删了跟着删。
 * 也就是说**这张表随时可以从 {@code ModbusService} 全量重建** —— 它不是数据源，是索引。</p>
 *
 * <p>{@link #leaseOwner} / {@link #leaseUntil} 是「扩容是安全操作」的前提（见 Modbus-Service-Poller.md 第六节）：
 * 单副本阶段永远抢得到、等于空跑；多副本时靠 {@code findOneAndUpdate} 单文档原子抢来决定谁跑。
 * 副本数由部署侧保证，代码这边只有开关与这条租约。</p>
 *
 * <p>{@link #lastStatus} 只记成功与失败**两种**：跳过（重入保护）不是状态，它是「这一次没跑、下次照跑」，
 * 表现为采样间隔临时变长 —— 把它记成状态会让看板上出现一堆不需要处置的感叹号。</p>
 */
@RegisterForReflection
@Schema(description = "Modbus 轮询任务（派生集合：一条 = 一个服务的某个方法）")
public class ModbusPolling {

    @BsonId
    @Schema(description = "主键（MongoDB ObjectId）", required = false)
    public ObjectId id;


    @Schema(description = "服务 id（ModbusService 主键的十六进制字符串）", required = true)
    public String serviceId;

    @Schema(description = "方法序号（ModbusTransaction.index）", examples = {"1"}, required = true)
    public Integer transactionIndex;

    /** 冗余一份：调度要按设备分组（同一条 RS485 串行），不必每次回查服务定义；它同时是分片键的候选。 */
    @Schema(description = "父设备 did：同一个 did 下的方法必须串行（同一条 RS485 总线）", required = true)
    public String did;

    /** 冗余一份：调度循环不必每次都持有定义文档。 */
    @Schema(description = "调用周期（秒），来自 ModbusTransaction.interval", examples = {"10"}, required = true)
    public Integer interval;

    @Schema(description = "下一次该跑的时刻（epoch 毫秒，UTC）。落库是必须的：否则一次滚动发布会把所有任务挤到同一秒爆发")
    public Date nextRunAt;

    @Schema(description = "租约持有者（本实例标识）；null = 没人持有")
    public String leaseOwner;

    @Schema(description = "租约到期时刻（epoch 毫秒，UTC）：过了这一刻别人就能抢")
    public Date leaseUntil;

    @Schema(description = "上一次**开始执行**的时刻（epoch 毫秒，UTC）：nextRunAt 的计算基准之一")
    public Date lastRunAt;

    @Schema(description = "上一次执行的结果：OK / FAILED；null = 还没跑过")
    public String lastStatus;

    @Schema(description = "上一次失败的原因；成功后清掉")
    public String lastError;

    @Schema(description = "连续失败次数：成功清零。本轮不做退避，只落下来供观测与实测")
    public Integer failCount;
}
