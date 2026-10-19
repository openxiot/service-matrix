package cc.openxiot.matrix.db.modbus.service.polling.response.field.codec;

import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseFieldValue;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusPollingResponseFieldValue}（应答字段的取值表条目）与 JSON 的互转。
 */
public final class ModbusPollingResponseFieldValueCodec {

    private ModbusPollingResponseFieldValueCodec() {
    }

    public static JsonObject encode(ModbusPollingResponseFieldValue entry) {
        JsonObject o = new JsonObject();
        if (entry == null) {
            return o;
        }
        if (entry.value != null) {
            o.put("value", entry.value);
        }
        if (entry.description != null) {
            o.put("description", entry.description);
        }
        return o;
    }

    public static JsonArray encode(List<ModbusPollingResponseFieldValue> entries) {
        JsonArray arr = new JsonArray();
        if (entries != null) {
            for (ModbusPollingResponseFieldValue entry : entries) {
                arr.add(encode(entry));
            }
        }
        return arr;
    }

    public static ModbusPollingResponseFieldValue decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusPollingResponseFieldValue entry = new ModbusPollingResponseFieldValue();
        entry.value = o.getLong("value");
        entry.description = o.getString("description");
        return entry;
    }

    public static List<ModbusPollingResponseFieldValue> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusPollingResponseFieldValue> entries = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            entries.add(decode(arr.getJsonObject(i)));
        }
        return entries;
    }
}
