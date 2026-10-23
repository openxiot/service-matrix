package cc.openxiot.matrix.db.modbus.history.codec;

import cc.openxiot.matrix.db.modbus.history.ModbusServiceRegistryHistory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusServiceRegistryHistory} → JSON（曲线图取数用）。
 *
 * <p>只出不进：历史事件是采集写进去的，不由请求体决定，所以没有 decode。</p>
 *
 * <p>单条 = 一次读的一个事件：{@code at} + 成功时的 {@code value}，或失败时 {@code failure:true}
 * 与 {@code message}（失败不给 {@code value} —— 那是这次读没读到值的记号）。消费方据此一条源
 * 同时画「值曲线」与「采集失败竖线」，不必再交叉查失败表。</p>
 */
public final class ModbusServiceRegistryHistoryCodec {

    private ModbusServiceRegistryHistoryCodec() {
    }

    public static JsonObject encode(ModbusServiceRegistryHistory event) {
        JsonObject o = new JsonObject();
        if (event == null) {
            return o;
        }
        if (event.at != null) {
            o.put("at", event.at.getTime());
        }
        if (Boolean.TRUE.equals(event.failure)) {
            o.put("failure", true);
            if (event.message != null) {
                o.put("message", event.message);
            }
        } else if (event.value != null) {
            o.put("value", event.value);
        } else {
            // 成功但没取到值的样本（极少）：给 value:null，与「没有这一条」区分开
            o.put("value", null);
        }
        return o;
    }

    public static JsonArray encode(List<ModbusServiceRegistryHistory> events) {
        JsonArray arr = new JsonArray();
        if (events != null) {
            for (ModbusServiceRegistryHistory event : events) {
                arr.add(encode(event));
            }
        }
        return arr;
    }
}