package cc.openxiot.matrix.db.modbus.alarm.codec;

import cc.openxiot.common.person.codec.PersonCodec;
import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarm;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@link ModbusServiceAlarm} → JSON（告警列表接口用）：明细 + 汇总。
 *
 * <p><b>只出不进</b>：告警是采集侧写进去的，不由请求体决定，所以没有 decode
 * （与 {@code ModbusServiceFailureCodec} 同口径）。</p>
 *
 * <p><b>有意偏离故障 Codec 的一处：这里每条都发 {@code id}。</b>故障记录是纯日志、只能看；
 * 告警行有**逐行动作**（点「处理」），前端得知道点的是哪一条。</p>
 *
 * <p>{@code openKey} 是并发护栏用的内部字段，**绝不下发**（与密码一类的道理不同：
 * 它本身不敏感，但它把 {@code serviceId#functionIndex#field} 拼成主键的形态泄漏成接口契约，
 * 以后就改不动了）。「是否已恢复」由 {@code recoveredAt} 表达，那才是对外的语义。</p>
 *
 * <p>{@code text} / {@code state} / {@code unit} / {@code field} 都是**数据**
 * （用户填的、或点表里的），原样输出、**永不翻译**。</p>
 */
public final class ModbusAlarmCodec {

    private ModbusAlarmCodec() {
    }

    public static JsonObject encode(ModbusServiceAlarm alarm) {
        JsonObject o = new JsonObject();
        if (alarm == null) {
            return o;
        }
        if (alarm.id != null) {
            o.put("id", alarm.id.toHexString());
        }
        // 每条都带 serviceId：空间级查询（不传 serviceId）回来的清单里混着多个服务的告警，
        // 前端得靠它认领归属。单服务查询时它与顶层那个重复，但不值得为此分两套编码。
        if (alarm.serviceId != null) {
            o.put("serviceId", alarm.serviceId);
        }
        if (alarm.functionIndex != null) {
            o.put("functionIndex", alarm.functionIndex);
        }
        if (alarm.field != null) {
            o.put("field", alarm.field);
        }
        // 触发那一刻的定义快照：列表里的「触发条件」列直接读它，不去查当前点表 ——
        // 查当前点表会让历史行显示成「按今天的阈值」解释，那正是快照要避免的
        if (alarm.compare != null) {
            o.put("compare", alarm.compare);
        }
        if (alarm.threshold != null) {
            o.put("threshold", alarm.threshold);
        }
        if (alarm.state != null) {
            o.put("state", alarm.state);
        }
        if (alarm.level != null) {
            o.put("level", alarm.level);
        }
        if (alarm.text != null) {
            o.put("text", alarm.text);
        }
        if (alarm.unit != null) {
            o.put("unit", alarm.unit);
        }
        if (alarm.at != null) {
            o.put("at", alarm.at.getTime());
        }
        // 已恢复才出键；未恢复时不出 —— 前端判「有没有 recoveredAt」与判「recoveredAt 为 null」
        // 都是 falsy，但不出键更贴合「这个字段现在不存在」的语义
        if (alarm.recoveredAt != null) {
            o.put("recoveredAt", alarm.recoveredAt.getTime());
        }
        if (alarm.closeType != null) {
            o.put("closeType", alarm.closeType);
        }
        if (alarm.handled != null) {
            o.put("handled", alarm.handled);
        }
        if (alarm.handledBy != null) {
            o.put("handledBy", PersonCodec.encode(alarm.handledBy));
        }
        // 样本可能是 Number，也可能是取值表的 description 字符串（或 null），原样出去
        if (alarm.sample != null) {
            o.put("sample", alarm.sample);
        }
        return o;
    }

    public static JsonArray encode(List<ModbusServiceAlarm> alarms) {
        JsonArray arr = new JsonArray();
        if (alarms != null) {
            for (ModbusServiceAlarm alarm : alarms) {
                arr.add(encode(alarm));
            }
        }
        return arr;
    }

    /**
     * 这批量告警的汇总：{@code {total, open, unhandled, byLevel: [...], byText: [...]}}。
     *
     * <p>{@code byLevel} 是「级别分布」（筛选用），{@code byText} 是「告警类型分布」
     * （用户自己起的名字，按条数降序）；两个数组都按**条数降序、同数按名字升序**排好，
     * 让输出稳定、前端不必再排。</p>
     *
     * <p>注意调用方给它的是**本次查询取回的那一段**（有 limit），所以 {@code total} 是
     * 「本次聚合了多少条」，界面上要和 {@code truncated} 一起读（与故障汇总同一条注记）。</p>
     *
     * <p>{@code level} / {@code text} 缺失的行归到一个 {@code UNKNOWN} 桶里：新存的定义被校验器
     * 挡住了必填，所以这只可能是更早的口径或脏数据 —— 归桶是为了让计数与 {@code total} 对得上，
     * **不是替用户编一个告警文本**。</p>
     *
     * <p>{@code open} 按 {@code recoveredAt == null} 数，**不按内部的 {@code openKey}** ——
     * 两者本该等价（写侧同时维护），但万一哪天不等价，汇总不能和它汇总的那批行自相矛盾：
     * {@code recoveredAt} 是前端能看到、也是它据以渲染状态的那个字段。</p>
     */
    public static JsonObject encodeSummary(List<ModbusServiceAlarm> alarms) {
        List<ModbusServiceAlarm> rows = alarms == null ? List.of() : alarms;
        Map<String, Tally> byLevel = new LinkedHashMap<>();
        Map<String, Tally> byText = new LinkedHashMap<>();
        int open = 0;
        int unhandled = 0;

        for (ModbusServiceAlarm alarm : rows) {
            if (alarm == null) {
                continue;
            }
            if (alarm.recoveredAt == null) {
                open++;
            }
            if (!Boolean.TRUE.equals(alarm.handled)) {
                unhandled++;
            }
            byLevel.computeIfAbsent(alarm.level == null ? "UNKNOWN" : alarm.level, key -> new Tally())
                    .add(alarm.at);
            byText.computeIfAbsent(alarm.text == null ? "UNKNOWN" : alarm.text, key -> new Tally())
                    .add(alarm.at);
        }

        JsonObject summary = new JsonObject();
        summary.put("total", rows.size());
        summary.put("open", open);
        summary.put("unhandled", unhandled);
        summary.put("byLevel", tallyArray(byLevel, key -> new JsonObject().put("level", key)));
        summary.put("byText", tallyArray(byText, key -> new JsonObject().put("text", key)));
        return summary;
    }

    private static JsonArray tallyArray(Map<String, Tally> tallies, Function<String, JsonObject> key) {
        List<Map.Entry<String, Tally>> entries = new ArrayList<>(tallies.entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<String, Tally> entry) -> entry.getValue().count).reversed()
                .thenComparing(Map.Entry::getKey));

        JsonArray arr = new JsonArray();
        for (Map.Entry<String, Tally> entry : entries) {
            JsonObject o = key.apply(entry.getKey());
            o.put("count", entry.getValue().count);
            if (entry.getValue().last != null) {
                o.put("lastAt", entry.getValue().last.getTime());
            }
            arr.add(o);
        }
        return arr;
    }

    /** 一类的计数与最近一次发生时刻。 */
    private static final class Tally {

        private int count;
        private Date last;

        private void add(Date at) {
            count++;
            if (at != null && (last == null || at.after(last))) {
                last = at;
            }
        }
    }
}
