package cc.openxiot.matrix.api.modbus.alarm.policy;

import cc.openxiot.matrix.db.modbus.alarm.OpenAlarmRule;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * 命中枚举映射的是显示名（字符串），线圈/离散位是 0/1。</p>
 *
 * <p><b>三态，不是两态</b>：{@link Verdict#HIT} 越限、{@link Verdict#MISS} 没越限、
 * {@link Verdict#SKIP} <b>判不了</b>（值缺失 / NaN / 类型对不上）。SKIP 必须区别于 MISS：
 * 一次截断或畸形的应答不能把一条开着的告警关掉，那是「凭空恢复」。</p>
 *
 * <p><b>一个寄存器可以配一组规则，但一次采样只留最严重的一条</b>：温度「低于 20 告警 / 超过 26 提示 /
 * 超过 28 警告 / 超过 30 严重」这种分级配置里，越限到 31℃ 时四条规则里两条同时命中 ——
 * 却只产生一条告警（{@link #rank} 最高的那条），其余开着的行按 {@code SUPERSEDED} 关掉。
 * 于是不变式是「一个寄存器同时最多一条开着」，它恰好就是告警表那条唯一部分索引强制的东西
 * （{@code openKey} 因此仍然只是 {@code fdid#pollIndex#field}，不带规则标识）。</p>
 *
 * <p>「开着的行就是状态」：不另存一份告警态，{@code (fdid, pollIndex, field)} 上有未恢复的告警
 * 就是「开着」。判定只需要这一张集合，它由告警表自己查出来
 * （见 Modbus-Alarm.md 第三节：为什么不放进 ModbusServiceLastPolling、也不用 poller 的内存基准）。</p>
 */
public final class ModbusAlarmPolicy {

    /**
     * 比较方式白名单。<b>只此一处</b>：校验器（挡新存的）与判定器（跑老数据）共用，
     * 防止两边各写一份而漂移。
     */
    public static final Set<String> OPERATORS = Set.of(">", ">=", "<", "<=", "=");

    /**
     * 级别次序，<b>由轻到重</b>。<b>只此一处</b>：白名单（{@link #LEVELS}）与「谁更严重」
     * （{@link #rank}）都从它来，不会漂移成一个拒收另一个。
     */
    public static final List<String> LEVEL_ORDER = List.of("INFO", "WARN", "CRITICAL");

    /**
     * 告警级别白名单。前端 device.service.editor 里有一份同口径的常量（下拉选项），改动要同步。
     */
    public static final Set<String> LEVELS = Set.copyOf(LEVEL_ORDER);

    /**
     * 什么都没发生（绝大多数采集走这条）：调用方应当**一次数据库操作都不做**。
     */
    private static final Decision NOTHING =
            new Decision(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    private ModbusAlarmPolicy() {
    }

    /**
     * 一个出值的判定结果。
     */
    public enum Verdict {
        /**
         * 越限。
         */
        HIT,
        /**
         * 没越限。
         */
        MISS,
        /**
         * 判不了（值缺失 / NaN / 类型对不上）：**不改状态**，一次畸形的应答不能把开着的告警关掉。
         */
        SKIP
    }

    /**
     * 判定的最小单元：一个可告警「出值」及其规则。由调用方从寄存器中心搭出来 ——
     * {@link #registerRules(FieldDevice)} 把 {@code configSnapshot.registries[]} 里带启用告警的
     * 寄存器展开成这张表（{@code field} 即寄存器名、{@code unit} 即寄存器单位）。
     *
     * @param field  出值名（写进告警行 / 比 {@code values} 的键）
     * @param alarms 该出值的一组阈值告警规则（按声明顺序）
     * @param unit   快照进告警行的单位
     */
    public record ModbusFieldRule(String field, List<ModbusRegistryAlarm> alarms, String unit) {
    }

    /**
     * 该开的告警：出值名 + 触发那一刻的配置（要快照进告警行，见 Modbus-Alarm.md 第四节）。
     */
    public record Open(String field, ModbusRegistryAlarm alarm, Object sample, String unit) {
    }

    /**
     * 一个**判不了**的出值（{@link Verdict#SKIP}）：配了告警，但这一帧拿不出可比的值。
     *
     * <p>带着 {@code alarm} 与 {@code sample} 交给调用方记日志 —— 判定器是纯函数，不碰日志；
     * 而这三个字段加上服务与方法坐标，正好够把「为什么这条告警不工作」说清楚。
     * {@code sample} 为 null 表示**这一帧里根本没有这个出值**（截断的应答）。</p>
     *
     * <p>现在一个出值可以有多条规则，所以 {@code alarm} 是**哪一条**规则判不了也一并带着：
     * 日志的去重键要细到规则，否则同一出值第二条坏规则永远不会被报出来。</p>
     */
    public record Skip(String field, ModbusRegistryAlarm alarm, Object sample) {
    }

    /**
     * 一次采集的判定结果。
     *
     * @param opens            该新开的告警
     * @param valueCloses      该关的告警（值回到不命中任何规则）：{@code closeType = VALUE}
     * @param supersededCloses 该关的告警（命中了，但生效的是这个出值的**另一条**规则 —— 升级或降级）：
     *                         {@code closeType = SUPERSEDED}。降级时值并没有回到正常，记 VALUE 是谎话
     * @param definitionCloses 该关的告警（本次采样里有这个出值、但新定义已不再给它配启用的规则）：
     *                         {@code closeType = DEFINITION}。否则那条告警再也判定不到、会永远挂着
     * @param handledCloses    该关的告警（那条规则**还在命中**，但开着的那行已经被人处理过了）：
     *                         {@code closeType = HANDLED}。同一次判定里还会有一条同出值的新 {@link Open} ——
     *                         「处理」的意思是「我知道了，以后还有越线再提醒我」，所以关掉旧的、重开新的
     * @param skips            判不了的规则，**只用来记日志**（见 {@link Skip}）
     *
     *                         <p>四个关闭列表**互斥**：一个出值在一次判定里最多进其中一个（{@link #judge} 每个出值只调一次，
     *                         那里是 {@code if / else if} 链）。所以调用方可以按 closeType 各发一条 {@code updateMany}，
     *                         不必担心同一条行被两个原因先后改两次。</p>
     */
    public record Decision(
            List<Open> opens,
            List<String> valueCloses,
            List<String> supersededCloses,
            List<String> definitionCloses,
            List<String> handledCloses,
            List<Skip> skips
    ) {

        /**
         * 有没有要**写库**的事；false 时调用方应当一次写操作都不做（稳态走这条）。
         *
         * <p>{@link #skips()} 不参与判定：它是日志、不是写 —— 否则一个判不了的出值会让每次采样
         * 都「有事要做」，把稳态零写这条纪律弄丢。</p>
         */
        public boolean isEmpty() {
            return opens.isEmpty() && valueCloses.isEmpty()
                    && supersededCloses.isEmpty() && definitionCloses.isEmpty()
                    && handledCloses.isEmpty();
        }
    }

    /**
     * 级别的高低：数字越大越严重。不认识的级别（老数据、或后端将来加了新级别）一律最低 ——
     * 它仍然能开出告警来，只是选不赢任何认识的那条。
     */
    public static int rank(String level) {
        if (level == null) {
            return -1;   // List.of(...).indexOf(null) 会 NPE，而 null 只是「不认识」的又一种
        }
        int index = LEVEL_ORDER.indexOf(level);
        return index < 0 ? -1 : index;
    }

    /**
     * 免费的闸门：这组出值规则里有没有<b>任何</b>启用了的告警。
     *
     * <p>纯内存判断、不碰库。没配告警的服务因此在采集热路径上**零数据库操作** ——
     * 与 {@code ModbusHistoryPolicy}「绝大多数采集一个数据库操作都没有」同一条纪律。</p>
     */
    public static boolean hasEnabledAlarm(List<ModbusFieldRule> rules) {
        if (rules == null) {
            return false;
        }
        for (ModbusFieldRule rule : rules) {
            if (rule != null && hasEnabled(rule.alarms())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 配置是否生效：{@code enabled} 缺省与 false 一个意思（配置留着、不告警）。
     */
    public static boolean isEnabled(ModbusRegistryAlarm alarm) {
        return alarm != null && Boolean.TRUE.equals(alarm.enabled);
    }

    /**
     * 一组规则里有没有启用的。
     */
    private static boolean hasEnabled(List<ModbusRegistryAlarm> alarms) {
        if (alarms == null) {
            return false;
        }
        for (ModbusRegistryAlarm alarm : alarms) {
            if (isEnabled(alarm)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该出值**启用的**规则，<b>按声明顺序</b>。
     *
     * <p>顺序是有语义的：级别相同的两条规则同时命中时，靠后的那条赢（见 {@link #decide}）。
     * 停用的规则直接丢掉 —— 它不参与判定，配置却仍然留在定义里（与 {@code polling} 同一条规矩）。</p>
     */
    static List<ModbusRegistryAlarm> enabledRules(List<ModbusRegistryAlarm> alarms) {
        if (alarms == null || alarms.isEmpty()) {
            return List.of();
        }
        List<ModbusRegistryAlarm> rules = new ArrayList<>(alarms.size());
        for (ModbusRegistryAlarm alarm : alarms) {
            if (isEnabled(alarm)) {
                rules.add(alarm);
            }
        }
        return rules;
    }

    /**
     * 一个值相对一份告警配置的判定。
     *
     * <p>判不了的一律 {@link Verdict#SKIP}（而不是猜一个 MISS）—— 校验器挡住了新存的坏定义，
     * 这里兜的是库里可能已有的老数据，调用方见到 SKIP 会记一条 WARN 日志，不静默。</p>
     */
    public static Verdict hit(Object value, ModbusRegistryAlarm alarm) {
        if (!isEnabled(alarm) || value == null || alarm.compare == null) {
            return Verdict.SKIP;
        }
        if ("=".equals(alarm.compare)) {
            return hitEquals(value, alarm);
        }
        return hitNumeric(value, alarm);
    }

    /**
     * 「等于」：目标可能是枚举映射的某个显示名（{@link ModbusRegistryAlarm#state}），
     * 也可能是一个数（{@link ModbusRegistryAlarm#threshold}）—— 由配置里有哪个决定。
     */
    private static Verdict hitEquals(Object value, ModbusRegistryAlarm alarm) {
        if (alarm.state != null) {
            // 带 enumMap 的寄存器：原始值命中映射时出的是显示名字符串。
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
        // 值是字符串（命中了枚举映射）而配置比的是数：定义对不上（老数据），判不了
        return Verdict.SKIP;
    }

    /**
     * {@code >} / {@code >=} / {@code <} / {@code <=}：只对数值有意义。
     */
    private static Verdict hitNumeric(Object value, ModbusRegistryAlarm alarm) {
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
     * 把服务的寄存器中心展开成判定用的出值规则表（规则来源：configSnapshot.registries）。
     *
     * <p>对每个非 null、有 {@code name}、{@code alarms} 非空且有启用的寄存器产出一条规则 ——
     * 告警属寄存器（寄存器中心），不再属 function 应答字段。判定的 identity（告警行按读块维度、
     * openKey 的 pollIndex）仍由调用方从判定的继承关系带过去，本表只管「哪个出值有哪些规则、
     * 单位是什么」。</p>
     */
    public static List<ModbusFieldRule> registerRules(FieldDevice fd) {
        if (fd == null || fd.configSnapshot == null || fd.configSnapshot.registries == null) {
            return List.of();
        }
        List<ModbusFieldRule> rules = new ArrayList<>();
        for (ModbusRegistry reg : fd.configSnapshot.registries) {
            if (reg == null || reg.name == null || !hasEnabled(reg.alarms)) {
                continue;
            }
            rules.add(new ModbusFieldRule(reg.name, reg.alarms, reg.unit));
        }
        return rules;
    }

    /**
     * 判定一次采集。
     *
     * @param rules           这组出值规则（由 {@link #registerRules(FieldDevice)} 从寄存器中心搭出来）
     * @param values          本次采集的「出值名 → 值」（{@code ModbusResponseParser} 的产物）
     * @param openRuleByField 这个方法当前**有未恢复告警**的出值 → 那条开着的行上的
     *                        {@link OpenAlarmRule}（哪条规则开的、有没有被处理过）。
     *                        无开着的不在表里；值里的 {@code ruleId} 可以是 {@code null}
     *                        （行上的 {@code ruleId} 缺失，只可能出现在更早的数据里）——
     *                        故调用方要用允许 null 值的 {@code Map}
     */
    public static Decision decide(
            List<ModbusFieldRule> rules,
            Map<String, Object> values,
            Map<String, OpenAlarmRule> openRuleByField
    ) {
        if (rules == null || rules.isEmpty()) {
            return NOTHING;
        }
        Map<String, Object> vals = values == null ? Map.of() : values;
        Map<String, OpenAlarmRule> open = openRuleByField == null ? Map.of() : openRuleByField;

        Acc acc = new Acc();
        // 这组规则里配了启用的告警、且本次采样里确实有这个出值：这些键归「值判定」管，
        // 不能因为「本次没开/没关」而被下面的 DEFINITION 逻辑误关掉
        Set<String> covered = new HashSet<>();

        for (ModbusFieldRule rule : rules) {
            if (rule == null) {
                continue;
            }
            covered.addAll(judge(rule.field(), rule.alarms(), rule.unit(), vals, open, acc));
        }

        // 采样里有、但新定义已不再给它配启用的规则、却还开着的那条：定义不再覆盖它了，关掉。
        // 采样里**没有**的键不动 —— 分不清是「定义删了它」还是「这一帧截断了」，宁可留着也不能凭空恢复。
        Set<String> definitionCloses = new TreeSet<>();
        for (String field : open.keySet()) {
            if (vals.containsKey(field) && !covered.contains(field)) {
                definitionCloses.add(field);
            }
        }

        if (acc.isEmpty() && definitionCloses.isEmpty()) {
            return NOTHING;
        }
        return new Decision(List.copyOf(acc.opens), List.copyOf(acc.valueCloses),
                List.copyOf(acc.supersededCloses), List.copyOf(definitionCloses),
                List.copyOf(acc.handledCloses), List.copyOf(acc.skips));
    }

    /**
     * 判定单个出值的**一组规则**，把结果记进累积器。
     *
     * <p><b>选举</b>：命中多条时只留一条 —— 级别最高的；级别相同则取**声明顺序里靠后的**那条。
     * 「靠后」是个说不出什么道理、但必须确定的裁决，选它是因为它由用户掌控（想换就把另一条往下挪）。
     * 这不构成「同级去重」的理由：{@code 低于 20 严重} 与 {@code 超过 30 严重} 这类低侧/高侧配置
     * 永远不可能同时命中，拒掉它是错的。</p>
     *
     * <p><b>命中优先于判不了</b>：同一出值的规则之间可能一条 HIT、另一条 SKIP（定义与值形态对不上，
     * 校验器挡新数据、老数据可能出现）。有胜者就按胜者走，判不了的那些只进日志。</p>
     *
     * <p><b>胜者与开着的行是同一条规则时</b>：那一行**没被处理过**就什么都不做 —— 这是稳态零写的落点，
     * 也是「改阈值不会把开着的告警关掉重开」的原因（规则身份是配置里的 {@code id}，改阈值不动它）；
     * **被处理过**则先关（{@code HANDLED}）再开一条新的，见下面那个分支。</p>
     *
     * @param unit 快照进告警行的单位（寄存器单位，见调用处）
     * @return 这个出值是否归「值判定」管（有启用的规则、且本次采样里有它）—— 是的话返回它的名字，
     * 供 DEFINITION 那条逻辑排除
     */
    private static Set<String> judge(
            String field,
            List<ModbusRegistryAlarm> alarms,
            String unit,
            Map<String, Object> vals,
            Map<String, OpenAlarmRule> open,
            Acc acc
    ) {
        if (field == null) {
            return Set.of();
        }
        List<ModbusRegistryAlarm> rules = enabledRules(alarms);
        if (rules.isEmpty()) {
            return Set.of();
        }
        if (!vals.containsKey(field)) {
            // 配了告警、这一帧里却没有这个出值：判不了，记下来让调用方说一声（不能当成「没越限」，
            // 那会把一条开着的告警凭空关掉）。不看 `open`：没开着也一样要报，否则定义配错了没人知道。
            for (ModbusRegistryAlarm rule : rules) {
                acc.skips.add(new Skip(field, rule, null));
            }
            return Set.of();
        }
        Object value = vals.get(field);

        ModbusRegistryAlarm winner = null;
        boolean missed = false;
        List<ModbusRegistryAlarm> skipped = new ArrayList<>(rules.size());
        for (ModbusRegistryAlarm rule : rules) {
            Verdict verdict = hit(value, rule);
            if (verdict == Verdict.HIT) {
                // `>=` 而不是 `>`：同级时**后**出现的那条接手
                if (winner == null || rank(rule.level) >= rank(winner.level)) {
                    winner = rule;
                }
            } else if (verdict == Verdict.MISS) {
                missed = true;
            } else {
                skipped.add(rule);
            }
        }
        // 判不了的照旧记日志：有别的规则命中，不代表这一条就判得了（它仍然是坏的）
        for (ModbusRegistryAlarm rule : skipped) {
            acc.skips.add(new Skip(field, rule, value));
        }

        boolean hasOpen = open.containsKey(field);
        if (winner != null) {
            OpenAlarmRule openRule = hasOpen ? open.get(field) : null;
            if (!hasOpen) {
                acc.opens.add(new Open(field, winner, value, unit));
            } else if (!Objects.equals(openRule.ruleId(), winner.id)) {
                // 开着的行是**另一条规则**开的：先关（SUPERSEDED）再开胜者。升级与降级都走这条 ——
                // 降级时值并没有回到正常，记 VALUE 是谎话（见 ModbusAlarmPolicy 的 Decision 文档）。
                // 这一支排在「已处理」之前：换了规则时被取代是更准确的原因，不能按 HANDLED 记
                acc.supersededCloses.add(field);
                acc.opens.add(new Open(field, winner, value, unit));
            } else if (openRule.handled()) {
                // 还是这条规则、还在命中，但开着的行**已经被人处理过**了。处理的含义是「我知道了，
                // 以后还有越线再提醒我」—— 所以关掉它、再开一条 handled=false 的新行，
                // 让同一件事重新回到「未处理」里。不关的话这一行会一直挂着，把后续的越线全挡在门外
                acc.handledCloses.add(field);
                acc.opens.add(new Open(field, winner, value, unit));
            }
            // 同一条规则、且未处理：什么都不做（稳态零写）
        } else if (missed) {
            if (hasOpen) {
                // 值回到正常了：这时「处理过没有」无所谓，值恢复才是实情（关的是 VALUE 而不是 HANDLED）
                acc.valueCloses.add(field);
            }
        }
        // 剩下的情形就是「一条都没命中，且至少有一条判不了」（全 SKIP）：
        // 什么都不做 —— 一次截断/畸形的应答不能把开着的告警凭空关掉，也不重报
        return Set.of(field);
    }

    /**
     * 判定过程中的几张累积表（判定的输出）。包一层是为了让 {@link #judge} 的入参不至于淹掉它自己。
     */
    private static final class Acc {

        private final List<Open> opens = new ArrayList<>();
        private final List<String> valueCloses = new ArrayList<>();
        private final List<String> supersededCloses = new ArrayList<>();
        private final List<String> handledCloses = new ArrayList<>();
        private final List<Skip> skips = new ArrayList<>();

        private boolean isEmpty() {
            return opens.isEmpty() && valueCloses.isEmpty()
                    && supersededCloses.isEmpty() && handledCloses.isEmpty() && skips.isEmpty();
        }
    }
}