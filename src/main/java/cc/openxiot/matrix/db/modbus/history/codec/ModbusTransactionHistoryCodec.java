package cc.openxiot.matrix.db.modbus.history.codec;

import cc.openxiot.matrix.db.modbus.history.ModbusTransactionHistory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ModbusTransactionHistory} → JSON（故障列表接口用）：失败调用 + 按分类的聚合。
 *
 * <p>编出的**字段与 {@code ModbusServiceFailureCodec} 逐字一致**（{@code serviceId/transactionIndex/
 * type/remoteCode/message/at}、{@code summary: {total, byType[], byRemoteCode[]}}）—— 故障读数改从
 * 操作日志过滤后，前端那条路解码照旧，没有任何契约变化。</p>
 *
 * <p>聚合放在这里而不是让前端拉全量自己算：明细可以只给一屏，聚合数字必须是整段的
 * （调用方给它的是本次查询取回的那一段，有 limit，所以 {@code total} 是「本次聚合了多少条」，
 * 界面上要和 {@code truncated} 一起读）。</p>
 */
public final class ModbusTransactionHistoryCodec {

    private ModbusTransactionHistoryCodec() {
    }

    public static JsonObject encode(ModbusTransactionHistory row) {
        JsonObject o = new JsonObject();
        if (row == null) {
            return o;
        }
        // 每条都带 serviceId：空间级查询（不传 serviceId）回来的清单里混着多个服务的失败，
        // 前端靠它认领归属。单服务查询时它与顶层那个重复，但不值得为此分两套编码。
        if (row.serviceId != null) {
            o.put("serviceId", row.serviceId);
        }
        if (row.transactionIndex != null) {
            o.put("transactionIndex", row.transactionIndex);
        }
        if (row.type != null) {
            o.put("type", row.type);
        }
        // 只有 SLAVE_EXCEPTION / DEVICE_ERROR 有码；没有就不出键
        if (row.remoteCode != null) {
            o.put("remoteCode", row.remoteCode);
        }
        if (row.message != null) {
            o.put("message", row.message);
        }
        if (row.at != null) {
            o.put("at", row.at.getTime());
        }
        return o;
    }

    public static JsonArray encode(List<ModbusTransactionHistory> rows) {
        JsonArray arr = new JsonArray();
        if (rows != null) {
            for (ModbusTransactionHistory row : rows) {
                arr.add(encode(row));
            }
        }
        return arr;
    }

    /** 按分类与远端原始码聚合这批复失败调用：{@code {total, byType: [...], byRemoteCode: [...]}}。 */
    public static JsonObject encodeSummary(List<ModbusTransactionHistory> rows) {
        Map<String, Tally> byType = new LinkedHashMap<>();
        Map<Integer, Tally> byRemoteCode = new LinkedHashMap<>();

        for (ModbusTransactionHistory row : rows == null ? List.<ModbusTransactionHistory>of() : rows) {
            if (row == null) {
                continue;
            }
            String type = row.type == null ? "UNKNOWN" : row.type;
            byType.computeIfAbsent(type, key -> new Tally()).add(row.at);
            if (row.remoteCode != null) {
                byRemoteCode.computeIfAbsent(row.remoteCode, key -> new Tally()).add(row.at);
            }
        }

        JsonObject summary = new JsonObject();
        summary.put("total", rows == null ? 0 : rows.size());
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

    /** 一类失败的计数与最近一次发生时刻。 */
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