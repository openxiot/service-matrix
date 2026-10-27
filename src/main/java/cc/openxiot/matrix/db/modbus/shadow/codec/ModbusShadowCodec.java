package cc.openxiot.matrix.db.modbus.shadow.codec;

import cc.openxiot.matrix.db.modbus.shadow.ModbusShadow;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusShadow} → JSON（服务卡当前值接口用）。
 *
 * <p>只出不进：影子是采集写进去的派生状态，不由请求体决定，所以没有 decode。</p>
 *
 * <p>{@code lastError} / {@code errorAt} 为 null 时**不出键**（与 {@code FieldDeviceCodec} 同口径）：
 * 前端判「有没有故障」看 {@code lastError} 在不在，而不是判一个显式的 null。
 * {@code value} / {@code recordedAt} 是**最后一次成功**采集的值与时刻，有故障时它们照旧存在。</p>
 *
 * <p>名称/单位由服务定义提供，这里不下发（影子本身不再携带它们）。</p>
 */
public final class ModbusShadowCodec {

    private ModbusShadowCodec() {
    }

    public static JsonObject encode(ModbusShadow shadow) {
        JsonObject o = new JsonObject();
        if (shadow == null) {
            return o;
        }
        if (shadow.registerIndex != null) {
            o.put("registerIndex", shadow.registerIndex);
        }
        if (shadow.value != null) {
            o.put("value", shadow.value);
        }
        if (shadow.recordedAt != null) {
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

    public static JsonArray encode(List<ModbusShadow> shadows) {
        JsonArray arr = new JsonArray();
        if (shadows != null) {
            for (ModbusShadow shadow : shadows) {
                arr.add(encode(shadow));
            }
        }
        return arr;
    }
}