package cc.openxiot.matrix.api.modbus.shadow;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusRegisterSnapshot} → JSON（{@code /modbus/service/{spaceId}/{serviceId}/shadows} 用）。
 *
 * <p>只出不进：网格是读侧投影，不由请求体决定，所以没有 decode。
 * {@code value} / {@code recordedAt} / {@code lastError} / {@code errorAt} 为 null 时**不出键**
 * （与 {@code ModbusServiceCodec} 同口径）：前端判「有没有缓存」看 {@code value} 在不在，
 * 而不是判一个显式的 null。</p>
 */
public final class ModbusRegisterSnapshotCodec {

    private ModbusRegisterSnapshotCodec() {
    }

    public static JsonObject encode(ModbusRegisterSnapshot r) {
        JsonObject o = new JsonObject();
        o.put("name", r.name);
        if (r.area != null) {
            o.put("area", r.area);
        }
        if (r.dataType != null) {
            o.put("dataType", r.dataType);
        }
        if (r.unit != null) {
            o.put("unit", r.unit);
        }
        o.put("access", r.access);
        if (r.pollingIndex != null) {
            o.put("pollingIndex", r.pollingIndex);
        }
        if (r.value != null) {
            o.put("value", r.value);
        }
        if (r.recordedAt != null) {
            // 时间一律输出 epoch 毫秒：与 ModbusServiceLastPollingCodec 同口径
            o.put("recordedAt", r.recordedAt.getTime());
        }
        o.put("status", r.status);
        if (r.lastError != null) {
            o.put("lastError", r.lastError);
        }
        if (r.errorAt != null) {
            o.put("errorAt", r.errorAt.getTime());
        }
        return o;
    }

    public static JsonArray encode(List<ModbusRegisterSnapshot> list) {
        JsonArray arr = new JsonArray();
        if (list != null) {
            for (ModbusRegisterSnapshot r : list) {
                arr.add(encode(r));
            }
        }
        return arr;
    }
}