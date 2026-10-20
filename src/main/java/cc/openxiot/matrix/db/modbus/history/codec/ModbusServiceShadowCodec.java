package cc.openxiot.matrix.db.modbus.history.codec;

import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusServiceShadow} → JSON（当前值接口用）。
 *
 * <p>只出不进：影子是采集写进去的派生状态，不由请求体决定，所以没有 decode。</p>
 *
 * <p>{@code lastError} / {@code errorAt} 为 null 时**不出键**（与 {@code ModbusServiceCodec} 同口径）：
 * 前端判「有没有故障」看 {@code lastError} 在不在，而不是判一个显式的 null。
 * 注意 {@code fields} / {@code recordedAt} 是**最后一次成功**采集的值与时刻，
 * 有故障时它们照旧存在（值是从那一刻起就不再更新的那个值），所以要靠 {@code errorAt} 判断陈旧程度。</p>
 */
public final class ModbusServiceShadowCodec {

    private ModbusServiceShadowCodec() {
    }

    public static JsonObject encode(ModbusServiceShadow shadow) {
        JsonObject o = new JsonObject();
        if (shadow == null) {
            return o;
        }
        if (shadow.pollingIndex != null) {
            o.put("pollingIndex", shadow.pollingIndex);
        }
        if (shadow.fields != null) {
            o.put("fields", new JsonObject(shadow.fields));
        }
        if (shadow.recordedAt != null) {
            // 时间一律输出 epoch 毫秒：与 `at` / `from` / `to` 同口径，省掉一端的格式解析
            o.put("recordedAt", shadow.recordedAt.getTime());
        }
        if (shadow.lastError != null) {
            o.put("lastError", shadow.lastError);
        }
        if (shadow.errorAt != null) {
            o.put("errorAt", shadow.errorAt.getTime());
        }
        return o;
    }

    public static JsonArray encode(List<ModbusServiceShadow> shadows) {
        JsonArray arr = new JsonArray();
        if (shadows != null) {
            for (ModbusServiceShadow shadow : shadows) {
                arr.add(encode(shadow));
            }
        }
        return arr;
    }
}
