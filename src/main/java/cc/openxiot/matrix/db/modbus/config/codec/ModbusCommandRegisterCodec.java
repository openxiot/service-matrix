package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusCommand.Register}（写多寄存器 10 的每一个寄存器）与 JSON 的互转。
 */
public final class ModbusCommandRegisterCodec {

    private ModbusCommandRegisterCodec() {
    }

    public static JsonObject encode(ModbusCommand.Register r) {
        JsonObject o = new JsonObject();
        if (r == null) {
            return o;
        }
        if (r.address != null) {
            o.put("address", r.address);
        }
        if (r.dataType != null) {
            o.put("dataType", r.dataType);
        }
        if (r.byteOrder != null) {
            o.put("byteOrder", r.byteOrder);
        }
        if (r.value != null) {
            o.put("value", r.value);
        }
        return o;
    }

    public static JsonArray encode(List<ModbusCommand.Register> registers) {
        JsonArray arr = new JsonArray();
        if (registers != null) {
            for (ModbusCommand.Register register : registers) {
                arr.add(encode(register));
            }
        }
        return arr;
    }
}
