package cc.openxiot.matrix.db.modbus.history.codec;

import cc.openxiot.matrix.db.modbus.history.ModbusServiceFailure;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ModbusServiceFailure} → JSON（故障列表接口用）：明细 + 按分类的聚合。
 *
 * <p>{@link #encodeSummary} 是这套分类字段存在的理由 —— 前端要按**故障类型**看分布，
 * 再从分类下钻到远端原始码（哪个从站、哪个异常码最多）。聚合放在这里而不是让前端拉全量自己算：
 * 明细可以只给一屏，聚合数字必须是整段的。</p>
 *
 * <p>注意调用方给它的是**本次查询取回的那一段**（有 limit，见
 * {@code ModbusHistoryQueryService.failures}），所以 {@code total} 是「本次聚合了多少条」，
 * 界面上要和 {@code truncated} 一起读。</p>
 *
 * <p>只出不进：故障是采集写进去的，不由请求体决定，所以没有 decode。</p>
 */
public final class ModbusServiceFailureCodec {

    private ModbusServiceFailureCodec() {
    }

    public static JsonObject encode(ModbusServiceFailure failure) {
        JsonObject o = new JsonObject();
        if (failure == null) {
            return o;
        }
        if (failure.functionIndex != null) {
            o.put("functionIndex", failure.functionIndex);
        }
        if (failure.type != null) {
            o.put("type", failure.type);
        }
        // 只有 SLAVE_EXCEPTION / DEVICE_ERROR 有码；没有就不出键（与 ModbusServiceCodec 同口径）
        if (failure.remoteCode != null) {
            o.put("remoteCode", failure.remoteCode);
        }
        if (failure.message != null) {
            o.put("message", failure.message);
        }
        if (failure.at != null) {
            o.put("at", failure.at.getTime());
        }
        return o;
    }

    public static JsonArray encode(List<ModbusServiceFailure> failures) {
        JsonArray arr = new JsonArray();
        if (failures != null) {
            for (ModbusServiceFailure failure : failures) {
                arr.add(encode(failure));
            }
        }
        return arr;
    }

    /**
     * 按分类与远端原始码聚合这批复故障：{@code {total, byType: [...], byRemoteCode: [...]}}，
     * 两个数组都按**条数降序**（条数相同按名字/码升序），让输出稳定、前端不必再排。
     */
    public static JsonObject encodeSummary(List<ModbusServiceFailure> failures) {
        Map<String, Tally> byType = new LinkedHashMap<>();
        Map<Integer, Tally> byRemoteCode = new LinkedHashMap<>();

        for (ModbusServiceFailure failure : failures == null ? List.<ModbusServiceFailure>of() : failures) {
            if (failure == null) {
                continue;
            }
            String type = failure.type == null ? "UNKNOWN" : failure.type;
            byType.computeIfAbsent(type, key -> new Tally()).add(failure.at);
            if (failure.remoteCode != null) {
                byRemoteCode.computeIfAbsent(failure.remoteCode, key -> new Tally()).add(failure.at);
            }
        }

        JsonObject summary = new JsonObject();
        summary.put("total", failures == null ? 0 : failures.size());
        summary.put("byType", tallyArray(byType, key -> new JsonObject().put("type", key)));
        summary.put("byRemoteCode", tallyArray(byRemoteCode, key -> new JsonObject().put("remoteCode", key)));
        return summary;
    }

    private static <K> JsonArray tallyArray(Map<K, Tally> tallies, java.util.function.Function<K, JsonObject> key) {
        List<Map.Entry<K, Tally>> entries = new ArrayList<>(tallies.entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<K, Tally> entry) -> entry.getValue().count).reversed()
                .thenComparing(entry -> String.valueOf(entry.getKey())));

        JsonArray arr = new JsonArray();
        for (Map.Entry<K, Tally> entry : entries) {
            JsonObject o = key.apply(entry.getKey());
            o.put("count", entry.getValue().count);
            if (entry.getValue().last != null) {
                o.put("lastAt", entry.getValue().last.getTime());
            }
            arr.add(o);
        }
        return arr;
    }

    /** 一类故障的计数与最近一次发生时刻。 */
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
