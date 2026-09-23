package cc.openxiot.matrix.db.modbus.service.function.response.field.codec;

import cc.openxiot.matrix.db.modbus.service.function.response.field.ModbusFunctionResponseFieldValue;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusFunctionResponseFieldValue}（应答字段的取值表条目）与 JSON 的互转。
 */
public final class ModbusFunctionResponseFieldValueCodec {

    private ModbusFunctionResponseFieldValueCodec() {
    }

    public static JsonObject encode(ModbusFunctionResponseFieldValue entry) {
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

    public static JsonArray encode(List<ModbusFunctionResponseFieldValue> entries) {
        JsonArray arr = new JsonArray();
        if (entries != null) {
            for (ModbusFunctionResponseFieldValue entry : entries) {
                arr.add(encode(entry));
            }
        }
        return arr;
    }

    public static ModbusFunctionResponseFieldValue decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusFunctionResponseFieldValue entry = new ModbusFunctionResponseFieldValue();
        entry.value = o.getLong("value");
        entry.description = o.getString("description");
        return entry;
    }

    public static List<ModbusFunctionResponseFieldValue> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusFunctionResponseFieldValue> entries = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            entries.add(decode(arr.getJsonObject(i)));
        }
        return entries;
    }
}
