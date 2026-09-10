package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusCommand.Coil}（写多线圈 0F 的每一个线圈）与 JSON 的互转。
 */
public final class ModbusCommandCoilCodec {

    private ModbusCommandCoilCodec() {
    }

    public static JsonObject encode(ModbusCommand.Coil c) {
        JsonObject o = new JsonObject();
        if (c == null) {
            return o;
        }
        if (c.offset != null) {
            o.put("offset", c.offset);
        }
        if (c.on != null) {
            o.put("on", c.on);
        }
        return o;
    }

    public static JsonArray encode(List<ModbusCommand.Coil> coils) {
        JsonArray arr = new JsonArray();
        if (coils != null) {
            for (ModbusCommand.Coil coil : coils) {
                arr.add(encode(coil));
            }
        }
        return arr;
    }
}
