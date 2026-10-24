package cc.openxiot.matrix.api.modbus.alarm.reconciler;
import cc.openxiot.matrix.api.modbus.alarm.policy.ModbusAlarmPolicy;

import cc.openxiot.matrix.db.modbus.alarm.ModbusAlarm;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 告警的**定义变更对账**（纯函数）：找出那些「新定义再也判定不到、却又还开着」的告警行。
 *
 * <h2>它兜的是哪一类残留</h2>
 *
 * <p>主路径是自愈的：{@link ModbusAlarmPolicy#decide} 只看**新定义**，一次采样里出现、
 * 而新定义已不再配告警的键会被按 {@code DEFINITION} 关掉。但那条路要跑到，得**这个方法还在采**、
 * 而且**这个函数上还有别的启用着的告警** —— 第一层闸门 {@code hasEnabledAlarm} 一旦不过，
 * 整条评估路径直接返回（见 {@code Modbus-Alarm.md} 第五节）。于是剩下两类残局：</p>
 *
 * <ul>
 *   <li><b>最后一个告警也被关掉</b>：闸门从此跳过这个方法，那条开行再也判定不到。</li>
 *   <li><b>这个方法再也不采</b>（{@code polling} 关掉、{@code interval} 变长到没人等得到、
 *       方法被整个删掉）：同样再也判定不到。</li>
 * </ul>
 *
 * <p>两者都与「定义刚被改过」同阶，所以挂在对账上而不是挂在采集热路径上 —— 后者要为此
 * 每次都查库，那是拿**每次采样**的代价去兜**一次编辑**的残局。</p>
 *
 * <h2>判据只有一条：新定义里还有没有「这条开行所依据的那条规则」</h2>
 *
 * <p>即：该出值（寄存器中心里的 {@code configSnapshot.registries[].name} —— 寄存器名是唯一的结果键）
 * 上，还有没有一条**启用着的、{@code id} 等于这行 {@code ruleId}** 的规则。寄存器改名、寄存器被删、
 * 字段被删、那条规则被删、规则被停用 —— 都表现为「这条规则在新定义里找不到」，一视同仁。</p>
 *
 * <p><b>为什么细到规则、而不是停在出值</b>：一个出值能配一组规则，判定时只留最严重的一条开着。
 * 停在出值的话，用户删掉「超过 30 严重」这条规则后，那条按它开出来的行会被判成「还覆盖着」
 * 而永远留着 —— 新定义里已经没有能判定它的规则了。</p>
 *
 * <p><b>不认识的服务一律不动</b>：{@code services} 里没有某个 {@code serviceId}，
 * 只说明**这一次对账没拿到它的定义**，不是「它的告警已经陈旧」的证据。这个保守口径让两条调用路径
 * （写入路径只传那一个服务、启动路径传全部）行为一致，也免掉了「传入的服务不全 ⇒ 误关一片」这个
 * 静默而严重的走法。真正该消失的行归**级联删除**（{@code ModbusHistoryService.purge}）。</p>
 *
 * <p><b>行上没有 {@code ruleId} 的也不动</b>：判据是拿它去比对的，没有它就说不出「这条规则还在不在」。
 * 这种行只可能来自更早的数据，而采集侧的胜者比对会把它按 {@code SUPERSEDED} 接手 ——
 * 那才是能说清原因的那条路径。关掉才是不可逆的那个动作，宁可留着。</p>
 */
public final class ModbusAlarmPlan {

    private ModbusAlarmPlan() {
    }

    /**
     * 一次要关的残留：某个服务、某个方法上的一批出值（{@code closeType = DEFINITION}）。
     *
     * @param pollIndex 方法序号；**可能是 null**（行里没有的话），此时只有
     *                      「这个方法已从定义里删掉」这一种解释 —— 也一样要关
     */
    public record Close(String serviceId, Integer pollIndex, List<String> fields) {
    }

    /**
     * 新定义里**配着启用告警**的全部规则：{@code 出值名(寄存器名) → 该出值上启用的规则标识}。
     *
     * <p>告警属寄存器（寄存器中心），不属 function 应答字段，故这里直接从
     * {@code configSnapshot.registries[]} 的 {@code alarms} 收，不再按 function/pollIndex 分组。</p>
     *
     * <p>启用着却没有 {@code id} 的规则（校验器挡着新数据，只可能是老数据）收不进来：
     * 它开出来的行 {@code ruleId} 也是 null，同样落进「行上没有 ruleId ⇒ 不动」那条。</p>
     */
    static Map<String, Set<String>> configuredRules(ModbusService service) {
        Map<String, Set<String>> keys = new HashMap<>();
        if (service == null || service.configSnapshot == null || service.configSnapshot.registries == null) {
            return keys;
        }
        for (ModbusRegistry reg : service.configSnapshot.registries) {
            if (reg == null || reg.name == null) {
                continue;
            }
            addRuleIds(keys, reg.name, reg.alarms);
        }
        return keys;
    }

    /** 把一个出值（寄存器）上启用着的规则标识收进那张表（出值名或规则标识为空的都不算）。 */
    private static void addRuleIds(
            Map<String, Set<String>> keys,
            String field,
            List<ModbusRegistryAlarm> alarms
    ) {
        if (field == null || alarms == null) {
            return;
        }
        for (ModbusRegistryAlarm alarm : alarms) {
            if (ModbusAlarmPolicy.isEnabled(alarm) && alarm.id != null) {
                keys.computeIfAbsent(field, ignored -> new HashSet<>()).add(alarm.id);
            }
        }
    }

    /**
     * 这批开着的告警行里，哪些该按 {@code DEFINITION} 关掉。不需要动的**不出现在结果里**
     * （调用方据此零写操作，启动时稳态就是零写）。
     *
     * <p>结果按 {@code (serviceId, pollIndex)} 排好序，且每个方法只出现一次 ——
     * 关闭是按整个开行标识 {@code updateMany} 的，一个方法一条语句就够。</p>
     */
    public static List<Close> plan(Collection<ModbusService> services, Collection<ModbusAlarm> open) {
        List<Close> closes = new ArrayList<>();
        if (services == null || open == null || open.isEmpty()) {
            return closes;
        }

        Map<String, Map<String, Set<String>>> covered = new HashMap<>();
        for (ModbusService service : services) {
            if (service == null || service.id == null) {
                continue;
            }
            covered.put(service.id.toHexString(), configuredRules(service));
        }

        // (serviceId, pollIndex) → 该关的出值名；TreeSet 让同一批的字段名稳定有序
        Map<Key, Set<String>> stale = new LinkedHashMap<>();
        for (ModbusAlarm row : open) {
            if (row == null || row.serviceId == null || row.field == null || row.field.isBlank()
                    || row.ruleId == null) {
                continue;   // 坐标不全、或说不上是哪条规则开的：见类注释，不在这里动
            }
            Map<String, Set<String>> rules = covered.get(row.serviceId);
            if (rules == null) {
                continue;   // 不认识的服务：见类注释，保守不动
            }
            Set<String> ids = rules.get(row.field);
            if (ids != null && ids.contains(row.ruleId)) {
                continue;   // 新定义里还有这条规则且启用着，留着 —— 它归值判定管
            }
            stale.computeIfAbsent(new Key(row.serviceId, row.pollIndex), key -> new TreeSet<>())
                    .add(row.field);
        }

        stale.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> closes.add(new Close(entry.getKey().serviceId(),
                        entry.getKey().pollIndex(), List.copyOf(entry.getValue()))));
        return closes;
    }

    /** 分组键。{@code pollIndex} 可以是 null（Comparator 显式处理，不靠 {@code Integer} 的自然序）。 */
    private record Key(String serviceId, Integer pollIndex) implements Comparable<Key> {

        @Override
        public int compareTo(Key other) {
            int byService = serviceId.compareTo(other.serviceId);
            if (byService != 0) {
                return byService;
            }
            if (pollIndex == null || other.pollIndex == null) {
                // null 排在前（没有序号的行最可疑，日志里先看到它）
                return pollIndex == other.pollIndex ? 0 : (pollIndex == null ? -1 : 1);
            }
            return Integer.compare(pollIndex, other.pollIndex);
        }
    }
}
