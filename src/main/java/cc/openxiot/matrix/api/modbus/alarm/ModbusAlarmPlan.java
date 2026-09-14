package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;

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
 * <h2>判据只有一条：新定义里还有没有配着启用告警的这个出值键</h2>
 *
 * <p>「出值键」= {@code response[].field}，或 {@code response[].bitList[].field}（位是独立的结果键）。
 * 字段改名、方法序号变了、字段被删、告警被关掉 —— 都表现为「旧键在新定义里找不到」，一视同仁。</p>
 *
 * <p><b>不认识的服务一律不动</b>：{@code services} 里没有某个 {@code serviceId}，
 * 只说明**这一次对账没拿到它的定义**，不是「它的告警已经陈旧」的证据。这个保守口径让两条调用路径
 * （写入路径只传那一个服务、启动路径传全部）行为一致，也免掉了「传入的服务不全 ⇒ 误关一片」这个
 * 静默而严重的走法。真正该消失的行归**级联删除**（{@code ModbusHistoryService.purge}）。</p>
 */
public final class ModbusAlarmPlan {

    private ModbusAlarmPlan() {
    }

    /**
     * 一次要关的残留：某个服务、某个方法上的一批出值（{@code closeType = DEFINITION}）。
     *
     * @param functionIndex 方法序号；**可能是 null**（行里没有的话），此时只有
     *                      「这个方法已从定义里删掉」这一种解释 —— 也一样要关
     */
    public record Close(String serviceId, Integer functionIndex, List<String> fields) {
    }

    /**
     * 新定义里**配着启用告警**的全部出值键：{@code functionIndex → {出值名}}。
     *
     * <p>写方法（{@code response} 为空）进来是空集 —— 告警只许配在读方法上，写方法的应答是请求回显、
     * 没有读值，所以它上面任何开行都是残留。</p>
     */
    static Map<Integer, Set<String>> configuredFields(ModbusService service) {
        Map<Integer, Set<String>> byIndex = new HashMap<>();
        if (service == null || service.functions == null) {
            return byIndex;
        }
        for (ModbusServiceFunction function : service.functions) {
            if (function == null || function.index == null) {
                continue;
            }
            Set<String> keys = new HashSet<>();
            if (function.response != null) {
                for (ModbusServiceField field : function.response) {
                    if (field == null) {
                        continue;
                    }
                    // 位与字段进的是同一个结果对象，所以两种键平级收进来（与 parser 同口径）
                    if (field.field != null && ModbusAlarmPolicy.isEnabled(field.alarm)) {
                        keys.add(field.field);
                    }
                    if (field.bitList == null) {
                        continue;
                    }
                    for (ModbusServiceFieldBit bit : field.bitList) {
                        if (bit != null && bit.field != null && ModbusAlarmPolicy.isEnabled(bit.alarm)) {
                            keys.add(bit.field);
                        }
                    }
                }
            }
            byIndex.put(function.index, keys);
        }
        return byIndex;
    }

    /**
     * 这批开着的告警行里，哪些该按 {@code DEFINITION} 关掉。不需要动的**不出现在结果里**
     * （调用方据此零写操作，启动时稳态就是零写）。
     *
     * <p>结果按 {@code (serviceId, functionIndex)} 排好序，且每个方法只出现一次 ——
     * 关闭是按整个开行标识 {@code updateMany} 的，一个方法一条语句就够。</p>
     */
    public static List<Close> plan(Collection<ModbusService> services, Collection<ModbusServiceAlarm> open) {
        List<Close> closes = new ArrayList<>();
        if (services == null || open == null || open.isEmpty()) {
            return closes;
        }

        Map<String, Map<Integer, Set<String>>> covered = new HashMap<>();
        for (ModbusService service : services) {
            if (service == null || service.id == null) {
                continue;
            }
            covered.put(service.id.toHexString(), configuredFields(service));
        }

        // (serviceId, functionIndex) → 该关的出值名；TreeSet 让同一批的字段名稳定有序
        Map<Key, Set<String>> stale = new LinkedHashMap<>();
        for (ModbusServiceAlarm row : open) {
            if (row == null || row.serviceId == null || row.field == null || row.field.isBlank()) {
                continue;   // 坐标不全的行没有「定义是否还覆盖它」可言，不在这里动
            }
            Map<Integer, Set<String>> byIndex = covered.get(row.serviceId);
            if (byIndex == null) {
                continue;   // 不认识的服务：见类注释，保守不动
            }
            Set<String> keys = row.functionIndex == null ? null : byIndex.get(row.functionIndex);
            if (keys != null && keys.contains(row.field)) {
                continue;   // 新定义里还配着这个出值的告警，留着 —— 它归值判定管
            }
            stale.computeIfAbsent(new Key(row.serviceId, row.functionIndex), key -> new TreeSet<>())
                    .add(row.field);
        }

        stale.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> closes.add(new Close(entry.getKey().serviceId(),
                        entry.getKey().functionIndex(), List.copyOf(entry.getValue()))));
        return closes;
    }

    /** 分组键。{@code functionIndex} 可以是 null（Comparator 显式处理，不靠 {@code Integer} 的自然序）。 */
    private record Key(String serviceId, Integer functionIndex) implements Comparable<Key> {

        @Override
        public int compareTo(Key other) {
            int byService = serviceId.compareTo(other.serviceId);
            if (byService != 0) {
                return byService;
            }
            if (functionIndex == null || other.functionIndex == null) {
                // null 排在前（没有序号的行最可疑，日志里先看到它）
                return functionIndex == other.functionIndex ? 0 : (functionIndex == null ? -1 : 1);
            }
            return Integer.compare(functionIndex, other.functionIndex);
        }
    }
}
