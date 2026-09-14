package cc.openxiot.matrix.db.modbus.service.codec;

import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusServiceFieldBit}（位区字段里的具名位）与 JSON 的互转。
 */
public final class ModbusServiceFieldBitCodec {

    private ModbusServiceFieldBitCodec() {
    }

    public static JsonObject encode(ModbusServiceFieldBit bit) {
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
            o.put("alarms", ModbusServiceFieldAlarmCodec.encode(bit.alarms));
        }
        return o;
    }

    public static JsonArray encode(List<ModbusServiceFieldBit> bits) {
        JsonArray arr = new JsonArray();
        if (bits != null) {
            for (ModbusServiceFieldBit bit : bits) {
                arr.add(encode(bit));
            }
        }
        return arr;
    }

    public static ModbusServiceFieldBit decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusServiceFieldBit bit = new ModbusServiceFieldBit();
        bit.offset = o.getInteger("offset");
        bit.field = o.getString("field");
        bit.alarms = ModbusServiceFieldAlarmCodec.decode(o.getJsonArray("alarms"));
        return bit;
    }

    public static List<ModbusServiceFieldBit> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusServiceFieldBit> bits = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            bits.add(decode(arr.getJsonObject(i)));
        }
        return bits;
    }
}
