package cc.openxiot.matrix.db.energy.stat;

import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergyType;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;

import java.util.Date;

/**
 * 一个配置在一个**小时桶**里的统计结果（库 {@code matrix}/集合 {@code energy-stats}）。
 *
 * <p>为什么桶固定是小时、而配置里还有 {@code periodHours}：小时是能同时支撑「今天按小时看」
 * 与「本月按天看」的最小公分母。采样线程只往小时桶里累加，报表按请求的时间范围做一次
 * 内存汇总（天/月都是小时的整数倍）。配置里的统计周期因此只影响**默认展示粒度**，
 * 不影响数据形状 —— 这样改周期不会让历史数据出现「4 小时桶和小时桶混在一起」。</p>
 *
 * <p><b>累计量求和使用增量字段，瞬时量只存「和 + 次数」与极值</b>：SOC 这类值不能相加，
 * 报表要平均值时用 {@link #socSum} ÷ {@link #socCount}，要最高/最低时直接读
 * {@link #socMax} / {@link #socMin}。{@link #capacity} / {@link #soh} / {@link #temperature}
 * 只保留桶内最后一个值（它们的「平均值」在页面上没有意义）。</p>
 */
@RegisterForReflection
public class EnergyStat {

    @BsonId
    public ObjectId id;

    public String spaceId;

    /** 配置 id（{@link cc.openxiot.matrix.db.energy.config.EnergyConfig#id} 的十六进制字符串）。 */
    public String configId;

    public EnergyRole role;

    public EnergyType type;

    /** 桶起点：本地时区整点的 epoch 毫秒。 */
    public long bucketAt;

    /** 用量/产量（kWh、m³…）。 */
    public double energy;

    /** 充电量 / 放电量（储能）。 */
    public double charge;
    public double discharge;

    /** 已按采样当时的价格折算好的成本/收益（元）。 */
    public double cost;
    public double revenue;

    /** 功率：求和 + 次数 + 最大/最小值 + 最后值（瞬时量不能直接相加）。 */
    public double powerSum;
    public long powerCount;
    public double powerMax;
    /** 桶内最小功率（带符号，通常为负 = 放电）。旧文档没有这个键 → null。 */
    public Double powerMin;
    public Double powerLast;

    /** SOC：求和 + 次数 + 极值 + 最后值。 */
    public double socSum;
    public long socCount;
    public double socMin;
    public double socMax;
    public Double socLast;

    /** 容量 / 健康度 / 温度 / 循环次数：只保留桶内最后值。 */
    public Double capacity;
    public Double soh;
    public Double temperature;
    public Double cycles;

    /** 本桶实际采到的点数（用于前端判断「这段是不是没数据」，也便于排查）。 */
    public long samples;

    public Date updatedAt;
}
