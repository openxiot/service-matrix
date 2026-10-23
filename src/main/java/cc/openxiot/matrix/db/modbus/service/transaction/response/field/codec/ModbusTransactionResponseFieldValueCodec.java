package cc.openxiot.matrix.db.modbus.service.transaction.response.field.codec;

import cc.openxiot.matrix.db.modbus.service.transaction.response.field.ModbusTransactionResponseFieldValue;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusTransactionResponseFieldValue}（应答字段的取值表条目）与 JSON 的互转。
 */
public final class ModbusTransactionResponseFieldValueCodec {

    private ModbusTransactionResponseFieldValueCodec() {
    }

    public static JsonObject encode(ModbusTransactionResponseFieldValue entry) {
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

    public static JsonArray encode(List<ModbusTransactionResponseFieldValue> entries) {
        JsonArray arr = new JsonArray();
        if (entries != null) {
            for (ModbusTransactionResponseFieldValue entry : entries) {
                arr.add(encode(entry));
            }
        }
        return arr;
    }

    public static ModbusTransactionResponseFieldValue decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusTransactionResponseFieldValue entry = new ModbusTransactionResponseFieldValue();
        entry.value = o.getLong("value");
        entry.description = o.getString("description");
        return entry;
    }

    public static List<ModbusTransactionResponseFieldValue> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusTransactionResponseFieldValue> entries = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            entries.add(decode(arr.getJsonObject(i)));
        }
        return entries;
    }
}
