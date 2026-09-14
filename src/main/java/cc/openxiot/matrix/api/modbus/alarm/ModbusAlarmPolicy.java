package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一次采集「该开哪些告警、该关哪些告警」的判定（规则见 Modbus-Alarm.md 第二、三节）。
 *
 * <p>纯函数、无状态、不碰数据库：与 {@link cc.openxiot.matrix.api.modbus.history.ModbusHistoryPolicy}
 * 同风格 —— 这是这套规则唯一能跑单测的地方（{@code @QuarkusTest} 要连 Mongo）。</p>
 *
 * <p><b>比的是解析后、也就是历史里存下来的那个值</b>，不是原始寄存器值：点表已经声明了
 * {@code scale} / {@code unit}，用户填的阈值是工程单位；曲线画的是解析值，将来在图上画阈值线才对得齐。
 * 故运行期按运行时类型分发 —— 普通数值是 {@code Integer}/{@code Long}/{@code Float}/{@code Double}，
 * 命中 {@code value-list} 的是 description 字符串，{@code bit-list} 的位是 0/1。</p>
 *
 * <p><b>三态，不是两态</b>：{@link Verdict#HIT} 越限、{@link Verdict#MISS} 没越限、
 * {@link Verdict#SKIP} <b>判不了</b>（值缺失 / NaN / 类型对不上）。SKIP 必须区别于 MISS：
 * 一次截断或畸形的应答不能把一条开着的告警关掉，那是「凭空恢复」。</p>
 *
 * <p>「开着的行就是状态」：不另存一份告警态，{@code (serviceId, functionIndex, field)} 上有未恢复的告警
 * 就是「开着」。判定只需要这一张集合，它由告警表自己查出来
 * （见 Modbus-Alarm.md 第三节：为什么不放进 ModbusServiceShadow、也不用 poller 的内存基准）。</p>
 */
public final class ModbusAlarmPolicy {

    /**
     * 比较方式白名单。<b>只此一处</b>：校验器（挡新存的）与判定器（跑老数据）共用，
     * 防止两边各写一份而漂移。
     */
    public static final Set<String> OPERATORS = Set.of(">", ">=", "<", "<=", "=");

    /** 告警级别白名单。前端 device.service.editor 里有一份同口径的常量（下拉选项），改动要同步。 */
    public static final Set<String> LEVELS = Set.of("INFO", "WARN", "CRITICAL");

    /** 什么都没发生（绝大多数采集走这条）：调用方应当**一次数据库操作都不做**。 */
    private static final Decision NOTHING = new Decision(List.of(), List.of(), List.of(), List.of());

    private ModbusAlarmPolicy() {
    }

    /** 一个出值的判定结果。 */
    public enum Verdict {
        /** 越限。 */
        HIT,
        /** 没越限。 */
        MISS,
        /** 判不了（值缺失 / NaN / 类型对不上）：**不改状态**，一次畸形的应答不能把开着的告警关掉。 */
        SKIP
    }

    /** 该开的告警：出值名 + 触发那一刻的配置（要快照进告警行，见 Modbus-Alarm.md 第四节）。 */
    public record Open(String field, ModbusServiceFieldAlarm alarm, Object sample, String unit) {
    }

    /**
     * 一个**判不了**的出值（{@link Verdict#SKIP}）：配了告警，但这一帧拿不出可比的值。
     *
     * <p>带着 {@code alarm} 与 {@code sample} 交给调用方记日志 —— 判定器是纯函数，不碰日志；
     * 而这三个字段加上服务与方法坐标，正好够把「为什么这条告警不工作」说清楚。
     * {@code sample} 为 null 表示**这一帧里根本没有这个出值**（截断的应答）。</p>
     */
    public record Skip(String field, ModbusServiceFieldAlarm alarm, Object sample) {
    }

    /**
     * 一次采集的判定结果。
     *
     * @param opens            该新开的告警（值越限、且当前没有未恢复的告警）
     * @param valueCloses      该关的告警（值回到正常）：{@code closeType = VALUE}
     * @param definitionCloses 该关的告警（本次采样里有这个出值、但新定义已不再给它配告警）：
     *                         {@code closeType = DEFINITION}。否则那条告警再也判定不到、会永远挂着
     * @param skips            判不了的出值，**只用来记日志**（见 {@link Skip}）
     */
    public record Decision(List<Open> opens, List<String> valueCloses, List<String> definitionCloses,
                           List<Skip> skips) {

        /**
         * 有没有要**写库**的事；false 时调用方应当一次写操作都不做（稳态走这条）。
         *
         * <p>{@link #skips()} 不参与判定：它是日志、不是写 —— 否则一个判不了的出值会让每次采样
         * 都「有事要做」，把稳态零写这条纪律弄丢。</p>
         */
        public boolean isEmpty() {
            return opens.isEmpty() && valueCloses.isEmpty() && definitionCloses.isEmpty();
        }
    }

    /**
     * 免费的闸门：这个方法的定义里有没有<b>任何</b>启用了的告警。
     *
     * <p>纯内存判断、不碰库。没配告警的服务因此在采集热路径上**零数据库操作** ——
     * 与 {@code ModbusHistoryPolicy}「绝大多数采集一个数据库操作都没有」同一条纪律。</p>
     */
    public static boolean hasEnabledAlarm(ModbusServiceFunction function) {
        if (function == null || function.response == null) {
            return false;
        }
        for (ModbusServiceField field : function.response) {
            if (field == null) {
                continue;
            }
            if (isEnabled(field.alarm)) {
                return true;
            }
            if (field.bitList != null) {
                for (ModbusServiceFieldBit bit : field.bitList) {
                    if (bit != null && isEnabled(bit.alarm)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 配置是否生效：{@code enabled} 缺省与 false 一个意思（配置留着、不告警）。 */
    public static boolean isEnabled(ModbusServiceFieldAlarm alarm) {
        return alarm != null && Boolean.TRUE.equals(alarm.enabled);
    }

    /**
     * 一个值相对一份告警配置的判定。
     *
     * <p>判不了的一律 {@link Verdict#SKIP}（而不是猜一个 MISS）—— 校验器挡住了新存的坏定义，
     * 这里兜的是库里可能已有的老数据，调用方见到 SKIP 会记一条 WARN 日志，不静默。</p>
     */
    public static Verdict hit(Object value, ModbusServiceFieldAlarm alarm) {
        if (!isEnabled(alarm) || value == null || alarm.compare == null) {
            return Verdict.SKIP;
        }
        if ("=".equals(alarm.compare)) {
            return hitEquals(value, alarm);
        }
        return hitNumeric(value, alarm);
    }

    /**
     * 「等于」：目标可能是取值表的某个 description（{@link ModbusServiceFieldAlarm#state}），
     * 也可能是一个数（{@link ModbusServiceFieldAlarm#threshold}）—— 由配置里有哪个决定。
     */
    private static Verdict hitEquals(Object value, ModbusServiceFieldAlarm alarm) {
        if (alarm.state != null) {
            // 带 value-list 的字段：原始值命中取值表时出的是 description 字符串。
            // 没命中时出的是个数（scale 已生效）—— 那明确「不是」这个命名状态，故是 MISS 不是 SKIP：
            // 我们知道它的值，且知道它不等于 state。
            if (value instanceof String text) {
                return text.equals(alarm.state) ? Verdict.HIT : Verdict.MISS;
            }
            return value instanceof Number ? Verdict.MISS : Verdict.SKIP;
        }
        if (alarm.threshold == null) {
            return Verdict.SKIP;
        }
        if (value instanceof Number number) {
            double v = number.doubleValue();
            if (!Double.isFinite(v)) {
                return Verdict.SKIP;
            }
            return v == alarm.threshold ? Verdict.HIT : Verdict.MISS;
        }
        // 值是字符串（命中了取值表）而配置比的是数：定义对不上（老数据），判不了
        return Verdict.SKIP;
    }

    /** {@code >} / {@code >=} / {@code <} / {@code <=}：只对数值有意义。 */
    private static Verdict hitNumeric(Object value, ModbusServiceFieldAlarm alarm) {
        if (alarm.threshold == null || !(value instanceof Number number)) {
            return Verdict.SKIP;
        }
        double v = number.doubleValue();
        if (!Double.isFinite(v)) {
            return Verdict.SKIP;
        }
        return switch (alarm.compare) {
            case ">" -> v > alarm.threshold ? Verdict.HIT : Verdict.MISS;
            case ">=" -> v >= alarm.threshold ? Verdict.HIT : Verdict.MISS;
            case "<" -> v < alarm.threshold ? Verdict.HIT : Verdict.MISS;
            case "<=" -> v <= alarm.threshold ? Verdict.HIT : Verdict.MISS;
            // 未知比较方式（老数据）：判不了，别猜
            default -> Verdict.SKIP;
        };
    }

    /**
     * 判定一次采集。
     *
     * @param function   本次调用的方法定义（用它的 response 里的告警配置，**新定义**）
     * @param values     本次采集的「出值名 → 值」（{@code ModbusResponseParser} 的产物）
     * @param openFields 该方法当前**有未恢复告警**的出值名集合（由告警表按 openKey 查出）
     */
    public static Decision decide(ModbusServiceFunction function,
                                  Map<String, Object> values,
                                  Set<String> openFields) {
        if (function == null || function.response == null || function.response.isEmpty()) {
            return NOTHING;
        }
        Map<String, Object> vals = values == null ? Map.of() : values;
        Set<String> open = openFields == null ? Set.of() : openFields;

        Acc acc = new Acc();
        // 新定义里配了告警、且本次采样里确实有这个出值：这些键归「值判定」管，
        // 不能因为「本次没开/没关」而被下面的 DEFINITION 逻辑误关掉
        Set<String> covered = new HashSet<>();

        for (ModbusServiceField field : function.response) {
            if (field == null) {
                continue;
            }
            covered.addAll(judge(field.field, field.alarm, field.unit, vals, open, acc));
            if (field.bitList != null) {
                for (ModbusServiceFieldBit bit : field.bitList) {
                    if (bit != null) {
                        // 位没有自己的单位：父字段那个是「整个寄存器」的工程单位，
                        // 贴到 0/1 的位上只会让列表里出现「1 ℃」这种话。位一律不带单位。
                        covered.addAll(judge(bit.field, bit.alarm, null, vals, open, acc));
                    }
                }
            }
        }

        // 采样里有、但新定义已不再给它配告警、却还开着的那条：定义不再覆盖它了，关掉。
        // 采样里**没有**的键不动 —— 分不清是「定义删了它」还是「这一帧截断了」，宁可留着也不能凭空恢复。
        Set<String> definitionCloses = new TreeSet<>();
        for (String field : open) {
            if (vals.containsKey(field) && !covered.contains(field)) {
                definitionCloses.add(field);
            }
        }

        if (acc.isEmpty() && definitionCloses.isEmpty()) {
            return NOTHING;
        }
        return new Decision(List.copyOf(acc.opens), List.copyOf(acc.valueCloses),
                List.copyOf(definitionCloses), List.copyOf(acc.skips));
    }

    /**
     * 判定单个出值，把结果记进累积器。
     *
     * @param unit 快照进告警行的单位（位传 null，见调用处）
     * @return 这个出值是否归「值判定」管（配了告警、且本次采样里有它）—— 是的话返回它的名字，供
     *         DEFINITION 那条逻辑排除
     */
    private static Set<String> judge(String field,
                                     ModbusServiceFieldAlarm alarm,
                                     String unit,
                                     Map<String, Object> vals,
                                     Set<String> open,
                                     Acc acc) {
        if (field == null || !isEnabled(alarm)) {
            return Set.of();
        }
        if (!vals.containsKey(field)) {
            // 配了告警、这一帧里却没有这个出值：判不了，记下来让调用方说一声（不能当成「没越限」，
            // 那会把一条开着的告警凭空关掉）。不看 `open`：没开着也一样要报，否则定义配错了没人知道。
            acc.skips.add(new Skip(field, alarm, null));
            return Set.of();
        }
        Object value = vals.get(field);
        Verdict verdict = hit(value, alarm);
        if (verdict == Verdict.HIT) {
            // 开着就什么都不做 —— 稳态零写，这是「一次越限一条」的落点
            if (!open.contains(field)) {
                acc.opens.add(new Open(field, alarm, value, unit));
            }
        } else if (verdict == Verdict.MISS) {
            if (open.contains(field)) {
                acc.valueCloses.add(field);
            }
        } else {
            acc.skips.add(new Skip(field, alarm, value));
        }
        return Set.of(field);
    }

    /** 判定过程中的三张累积表（判定的输出）。包一层是为了让 {@link #judge} 的入参不至于淹掉它自己。 */
    private static final class Acc {

        private final List<Open> opens = new ArrayList<>();
        private final List<String> valueCloses = new ArrayList<>();
        private final List<Skip> skips = new ArrayList<>();

        private boolean isEmpty() {
            return opens.isEmpty() && valueCloses.isEmpty() && skips.isEmpty();
        }
    }
}
