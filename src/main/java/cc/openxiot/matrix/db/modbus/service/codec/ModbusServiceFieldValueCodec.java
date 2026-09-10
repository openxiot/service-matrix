package cc.openxiot.matrix.db.modbus.service.codec;

import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldValue;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusServiceFieldValue}（应答字段的取值表条目）与 JSON 的互转。
 */
public final class ModbusServiceFieldValueCodec {

    private ModbusServiceFieldValueCodec() {
    }

    public static JsonObject encode(ModbusServiceFieldValue entry) {
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

    public static JsonArray encode(List<ModbusServiceFieldValue> entries) {
        JsonArray arr = new JsonArray();
        if (entries != null) {
            for (ModbusServiceFieldValue entry : entries) {
                arr.add(encode(entry));
            }
        }
        return arr;
    }

    public static ModbusServiceFieldValue decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusServiceFieldValue entry = new ModbusServiceFieldValue();
        entry.value = o.getLong("value");
        entry.description = o.getString("description");
        return entry;
    }

    public static List<ModbusServiceFieldValue> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusServiceFieldValue> entries = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            entries.add(decode(arr.getJsonObject(i)));
        }
        return entries;
    }
}
