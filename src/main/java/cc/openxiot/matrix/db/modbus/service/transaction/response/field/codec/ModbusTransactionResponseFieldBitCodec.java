package cc.openxiot.matrix.db.modbus.service.transaction.response.field.codec;

import cc.openxiot.matrix.db.modbus.service.transaction.response.field.ModbusTransactionResponseFieldBit;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusTransactionResponseFieldBit}（位区字段里的具名位）与 JSON 的互转。
 */
public final class ModbusTransactionResponseFieldBitCodec {

    private ModbusTransactionResponseFieldBitCodec() {
    }

    public static JsonObject encode(ModbusTransactionResponseFieldBit bit) {
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

    public static JsonArray encode(List<ModbusTransactionResponseFieldBit> bits) {
        JsonArray arr = new JsonArray();
        if (bits != null) {
            for (ModbusTransactionResponseFieldBit bit : bits) {
                arr.add(encode(bit));
            }
        }
        return arr;
    }

    public static ModbusTransactionResponseFieldBit decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusTransactionResponseFieldBit bit = new ModbusTransactionResponseFieldBit();
        bit.offset = o.getInteger("offset");
        bit.field = o.getString("field");
        return bit;
    }

    public static List<ModbusTransactionResponseFieldBit> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusTransactionResponseFieldBit> bits = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            bits.add(decode(arr.getJsonObject(i)));
        }
        return bits;
    }
}
