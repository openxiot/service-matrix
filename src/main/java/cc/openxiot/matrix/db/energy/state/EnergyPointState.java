package cc.openxiot.matrix.db.energy.state;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.Date;

/**
 * 一条点位的累计基线（库 {@code matrix}/集合 {@code energy-point-states}）。
 *
 * <p>采样要算「这次读数 − 上次读数」，所以上次读数必须跨进程重启、跨小时桶一直留着；
 * 放在内存里一重启就会把重启那一刻之前、当前周期里已经采到的增量再算一遍（或者漏掉）。</p>
 *
 * <p>主键是 {@code configId#pointKey}：配置删了，基线跟着删（见 {@code EnergySampler} 的清理）；
 * 点位改名不影响主键（{@link cc.openxiot.matrix.db.energy.config.EnergyPoint#key()} 不含名字）。</p>
 */
@RegisterForReflection
public class EnergyPointState {

    @BsonId
    public String id;

    public String configId;

    public String pointKey;

    /** 上一次成功读到的原始值；null = 还没有基线。 */
    public Double lastRaw;

    /** 上一次读数发生的时刻（采样时刻，不是设备时间）。用于功率积分与增量去重。 */
    public Date lastAt;

    /** 该点位自配置以来累计的电量（只增不减）：排查用，不参与报表。 */
    public double total;

    public Date updatedAt;
}
