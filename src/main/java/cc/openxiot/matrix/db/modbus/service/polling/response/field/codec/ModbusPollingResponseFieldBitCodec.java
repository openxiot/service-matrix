package cc.openxiot.matrix.db.modbus.service.polling.response.field.codec;

import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseFieldBit;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusPollingResponseFieldBit}（位区字段里的具名位）与 JSON 的互转。
 */
public final class ModbusPollingResponseFieldBitCodec {

    private ModbusPollingResponseFieldBitCodec() {
    }

    public static JsonObject encode(ModbusPollingResponseFieldBit bit) {
        JsonObject o = new JsonObject();
        if (bit == null) {
            return o;
        }
        if (bit.offset != null) {
            o.put("offset", bit.offset);
        }
        if (bit.field != null) {
            o.put("field", bit.field);
        }
        if (bit.alarms != null && !bit.alarms.isEmpty()) {
            o.put("alarms", ModbusPollingResponseFieldAlarmCodec.encode(bit.alarms));
        }
        return o;
    }

    public static JsonArray encode(List<ModbusPollingResponseFieldBit> bits) {
        JsonArray arr = new JsonArray();
        if (bits != null) {
            for (ModbusPollingResponseFieldBit bit : bits) {
                arr.add(encode(bit));
            }
        }
        return arr;
    }

    public static ModbusPollingResponseFieldBit decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusPollingResponseFieldBit bit = new ModbusPollingResponseFieldBit();
        bit.offset = o.getInteger("offset");
        bit.field = o.getString("field");
        bit.alarms = ModbusPollingResponseFieldAlarmCodec.decode(o.getJsonArray("alarms"));
        return bit;
    }

    public static List<ModbusPollingResponseFieldBit> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusPollingResponseFieldBit> bits = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            bits.add(decode(arr.getJsonObject(i)));
        }
        return bits;
    }
}
