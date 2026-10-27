package cc.openxiot.matrix.db.modbus.fd.poll.response.field.codec;

import cc.openxiot.matrix.db.modbus.fd.poll.response.field.ModbusResponseFieldValue;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusResponseFieldValue}（应答字段的取值表条目）与 JSON 的互转。
 */
public final class ModbusResponseFieldValueCodec {

    private ModbusResponseFieldValueCodec() {
    }

    public static JsonObject encode(ModbusResponseFieldValue entry) {
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

    public static JsonArray encode(List<ModbusResponseFieldValue> entries) {
        JsonArray arr = new JsonArray();
        if (entries != null) {
            for (ModbusResponseFieldValue entry : entries) {
                arr.add(encode(entry));
            }
        }
        return arr;
    }

    public static ModbusResponseFieldValue decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusResponseFieldValue entry = new ModbusResponseFieldValue();
        entry.value = o.getLong("value");
        entry.description = o.getString("description");
        return entry;
    }

    public static List<ModbusResponseFieldValue> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusResponseFieldValue> entries = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            entries.add(decode(arr.getJsonObject(i)));
        }
        return entries;
    }
}
