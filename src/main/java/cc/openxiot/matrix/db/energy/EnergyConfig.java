package cc.openxiot.matrix.db.energy;

import cc.openxiot.common.person.Person;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 一条能源统计配置：一个项目（根空间）里、某个角色某个类型的「哪些点位加起来算什么」。
 *
 * <p>落库 {@code matrix}/集合 {@code energy-configs}。{@code spaceId} 存**根空间 id**，
 * 与设备/服务/看板的「按项目」口径一致；点位可以挂在项目下任意子空间的设备上，
 * 但一条配置只属于一个项目。</p>
 *
 * <p><b>价格是配置项而不是点位属性</b>：同一项目的电量共用一个电价，配置改了从下一个统计周期
 * 起生效（已生成的历史桶不重算 —— 那正是「数据已经算出来了」的意义）。存储配置有两个价：
 * 充电成本按 {@link #chargePrice}、放电收益按 {@link #dischargePrice}；消耗/生产用 {@link #price}。</p>
 */
@RegisterForReflection
@Schema(description = "能源统计配置")
public class EnergyConfig {

    @BsonId
    public ObjectId id;

    @Schema(description = "项目根空间 id", required = true)
    public String spaceId;

    @Schema(description = "配置名称（展示用，用户数据不翻译）", required = true)
    public String name;

    @Schema(description = "角色：消耗 / 生产 / 存储", required = true)
    public EnergyRole role;

    @Schema(description = "能源类型：电 / 水 / 燃气", required = true)
    public EnergyType type;

    /** 消耗/生产单价（元/kWh、元/m³…）；null = 不计算费用。 */
    public Double price;

    /** 存储：充电单价（元/kWh）；null = 充电成本按 0 计。 */
    public Double chargePrice;

    /** 存储：放电单价（元/kWh，或上网电价）；null = 放电收益按 0 计。 */
    public Double dischargePrice;

    @Schema(description = "统计周期（小时）：1 / 4 / 12 / 24", required = true)
    public Integer periodHours;

    @Schema(description = "是否参与采样；停用后历史仍在，只是不再产生新数据", required = true)
    public boolean enabled = true;

    @Schema(description = "点位清单", required = true)
    public List<EnergyPoint> points = new ArrayList<>();

    public Person creator;

    public Person updater;

    public Date create;

    public Date update;

    /** 该配置是否配了显式的充/放电量点位（有则不按功率积分，免得两边重复计电）。 */
    public boolean hasChargeDischargePoints() {
        if (points == null) {
            return false;
        }
        return points.stream().anyMatch(point -> point != null
                && (point.metric == EnergyMetric.CHARGE_ENERGY
                || point.metric == EnergyMetric.DISCHARGE_ENERGY));
    }
}
