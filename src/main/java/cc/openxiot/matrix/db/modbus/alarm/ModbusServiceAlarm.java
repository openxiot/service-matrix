package cc.openxiot.matrix.db.modbus.alarm;

import cc.openxiot.common.person.Person;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Date;

/**
 * 一次阈值告警（库 {@code modbus}、集合 {@code service-alarms}）。
 *
 * <p>在此之前只知道「采到没采到」（{@link cc.openxiot.matrix.db.modbus.history.ModbusServiceFailure}），
 * 这张表记的是**采到了但值不对**：读方法的出值越过用户配的阈值就落一条，前端列出来、点「处理」回执。
 * 判定口径见 {@link cc.openxiot.matrix.api.modbus.alarm.ModbusAlarmPolicy}，设计见 {@code Modbus-Alarm.md}。</p>
 *
 * <h3>一次越限一条（边沿），不是每次采样一条</h3>
 *
 * <p>正常 → 越限落一条，持续越限**不重复落**，回到正常后再越限才是新的一次。
 * 「现在还在不在越限」不另存一份状态：<b>{@link #openKey} 有值就是未恢复</b>，
 * 于是「这个出值当前开着吗」就是一次带索引的存在性查询（见 {@code Modbus-Alarm.md} 第五节：
 * 为什么不放进影子文档、也不用 poller 的内存基准）。</p>
 *
 * <h3>为什么要存触发那一刻的定义快照</h3>
 *
 * <p>{@link #compare} / {@link #threshold} / {@link #state} / {@link #level} / {@link #text} /
 * {@link #unit} 都是**触发那一刻**从点表抄下来的，不是引用。定义是活的、行是不可变的历史：
 * 只存引用的话，把阈值从 80 改成 60 会**悄悄改写每一条历史告警的含义** ——
 * 「当时为什么报」这件事被一次无关的修改编辑掉了。这与 {@code ModbusServiceFailure} 存 message
 * 文本而不是错误码同一个道理，也让告警行在服务被删掉之后仍然自解释。</p>
 *
 * <p>{@link #text} 是**用户自己填的**（如「温度过高」），前后端一律原样显示、**永不翻译**。</p>
 */
@MongoEntity(database = "modbus", collection = "service-alarms")
@RegisterForReflection
@Schema(description = "Modbus 服务出值的一次阈值告警")
public class ModbusServiceAlarm extends ReactivePanacheMongoEntity {

    /** {@link #closeType}：值回到正常，告警关闭。 */
    public static final String CLOSE_VALUE = "VALUE";

    /**
     * {@link #closeType}：新定义已不再给这个出值配告警（字段被删、或告警被关掉），故关闭。
     * 与 {@link #CLOSE_VALUE} 分开是为了让 UI 能解释一条**没有恢复样本**的关闭 ——
     * 否则界面上会出现「值明明是 85，为什么显示已恢复」。同 {@code ModbusFailureType}
     * 那句「宁可说不知道，也不猜一个会把人带偏的分类」。
     */
    public static final String CLOSE_DEFINITION = "DEFINITION";

    /**
     * {@link #closeType}：当前生效的是**同一个出值的另一条规则**（升级或降级），故关闭。
     *
     * <p>一个出值可以配一组分级规则（如「超过 26 提示 / 超过 28 警告 / 超过 30 严重」），
     * 但一次采样只留最严重的那条。值从 31℃ 降到 27℃ 时严重那条关闭 ——
     * 此时的真实原因**不是**值恢复正常（27℃ 仍然越限），记成 {@link #CLOSE_VALUE} 是谎话。</p>
     */
    public static final String CLOSE_SUPERSEDED = "SUPERSEDED";

    /**
     * {@link #closeType}：这条**已经被人处理过**，而它那条规则**还在命中**，故关闭并另开一条新的。
     *
     * <p>「处理」在业务上的意思是「我知道了，以后还有越线再提醒我」—— 不是「这事到此为止」。
     * 所以一条未恢复且已处理的行不能就这么一直挂着把后续的越线全挡住：只要那条规则还在命中，
     * 就把它按这个原因关掉、再开一条 {@code handled = false} 的新行，让同一件事重新回到未处理里。
     * 新行的 {@code at} 与旧行的 {@code recoveredAt} 是同一个时刻（见 {@code ModbusAlarmService#apply}）。</p>
     *
     * <p>几种关闭原因**互斥**，判定侧按优先级只挑一个：换规则（{@link #CLOSE_SUPERSEDED}）比它更具体，
     * 排在前；值已回到正常时走 {@link #CLOSE_VALUE}（值恢复才是实情），它不参与。</p>
     */
    public static final String CLOSE_HANDLED = "HANDLED";

    @Schema(description = "服务 id（ModbusService 主键的十六进制字符串）", required = true)
    public String serviceId;

    @Schema(description = "方法序号（ModbusFunction.index）", examples = {"1"}, required = true)
    public Integer functionIndex;

    @Schema(description = "出值名：字段名（response.fields[].field），或位区里某个具名位（bit-list[].field）"
            + "—— 位是独立的结果键，所以它出现在这里与字段平级", examples = {"进水温度"}, required = true)
    public String field;

    // ===== 触发那一刻的定义快照 =====

    @Schema(description = "比较方式快照：> 超过 / >= 达到 / < 低于 / <= 低于等于 / = 等于", examples = {">"})
    public String compare;

    @Schema(description = "数值阈值快照（compare 为 = 且字段带 value-list 时为 null，那种字段比 state）",
            examples = {"80"})
    public Double threshold;

    @Schema(description = "compare 为 = 时比的状态名快照（取值表里的 description），逐字相等")
    public String state;

    @Schema(description = "告警级别快照：INFO 提示 / WARN 警告 / CRITICAL 严重", examples = {"WARN"})
    public String level;

    @Schema(description = "告警文本快照（用户自定义，如 温度过高）—— **用户数据，前端原样显示、不翻译**",
            examples = {"温度过高"})
    public String text;

    @Schema(description = "触发时该字段的单位快照（点表的 unit；可能是 null），列表里跟在当前值后面显示",
            examples = {"℃"})
    public String unit;

    // ===== 边沿 =====

    @Schema(description = "越限**首次**出现的时刻（UTC）；持续越限期间不刷新", required = true)
    public Date at;

    @Schema(description = "恢复时刻（UTC）；**null = 仍在越限** —— 这就是状态本身，不另存一份告警态")
    public Date recoveredAt;

    @Schema(description = "关闭方式：VALUE 值回到正常 / DEFINITION 新定义已不再配这个告警 /"
            + " SUPERSEDED 同一个出值的另一条规则接手了 / HANDLED 已处理但仍在越限，另开了一条",
            examples = {"VALUE"})
    public String closeType;

    // ===== 处理 =====

    @Schema(description = "用户是否已处理（回执，不是改配置）。null / false 都算未处理")
    public Boolean handled;

    @Schema(description = "处理人；处理时刻取它的 timestamp（与 creator / updater 同口径，不另开字段）")
    public Person handledBy;

    // ===== 触发时的样本 =====

    @Schema(description = "触发那一刻的值（Number 或取值表的 description 字符串），开着期间不刷新 ——"
            + "它是「当时报的是多少」的证据，不是当前值")
    public Object sample;

    // ===== 内部，不下发 =====

    @Schema(description = "未恢复时才有：serviceId#functionIndex#field。唯一部分索引建在它上面（并发护栏），"
            + "**绝不下发给前端**", hidden = true)
    public String openKey;

    @Schema(description = "触发这条的是哪条规则（配置里的 ModbusFunctionResponseFieldAlarm.id）。"
            + "「这条开着的行是不是还该开着」要靠它与当前胜者比对 —— "
            + "**内部标识，绝不下发给前端**：行上的定义快照已经够前端说清「触发条件」，"
            + "与 openKey 同一条规矩", hidden = true)
    public String ruleId;

    /**
     * 该出值的开行标识。未恢复的行带它、已恢复的行把它 {@code $unset} 掉 ——
     * 于是「有一个 openKey 等于它的行」与「这个出值正在告警」是同一件事。
     */
    public static String openKeyOf(String serviceId, Integer functionIndex, String field) {
        return serviceId + "#" + functionIndex + "#" + field;
    }
}
