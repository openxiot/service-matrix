package cc.openxiot.matrix.db.modbus.device.poll.response.field.codec;

import cc.openxiot.matrix.db.modbus.device.poll.response.field.ModbusResponseFieldBit;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusResponseFieldBit}（位区字段里的具名位）与 JSON 的互转。
 */
public final class ModbusResponseFieldBitCodec {

    private ModbusResponseFieldBitCodec() {
    }

    public static JsonObject encode(ModbusResponseFieldBit bit) {
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
        return o;
    }

    public static JsonArray encode(List<ModbusResponseFieldBit> bits) {
        JsonArray arr = new JsonArray();
        if (bits != null) {
            for (ModbusResponseFieldBit bit : bits) {
                arr.add(encode(bit));
            }
        }
        return arr;
    }

    public static ModbusResponseFieldBit decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusResponseFieldBit bit = new ModbusResponseFieldBit();
        bit.offset = o.getInteger("offset");
        bit.field = o.getString("field");
        return bit;
    }

    public static List<ModbusResponseFieldBit> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusResponseFieldBit> bits = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            bits.add(decode(arr.getJsonObject(i)));
        }
        return bits;
    }
}
