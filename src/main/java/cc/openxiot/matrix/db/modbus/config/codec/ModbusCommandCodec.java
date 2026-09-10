package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusCommand}（一个功能码动作）与 JSON 的互转。
 *
 * <p>字段按「所有功能码共用 → 读操作 → 写操作」的顺序输出，只输出有值的：
 * 读操作的 quantity / dataType / byteOrder / scale / unit，写操作的 coilState / registerValue / coils / registers
 * 互不重叠，前端按 fc 取用。</p>
 */
public final class ModbusCommandCodec {

    private ModbusCommandCodec() {
    }

    public static JsonObject encode(ModbusCommand cmd) {
        JsonObject o = new JsonObject();
        if (cmd == null) {
            return o;
        }
        if (cmd.name != null) {
            o.put("name", cmd.name);
        }
        if (cmd.fc != null) {
            o.put("fc", cmd.fc);
        }
        if (cmd.index != null) {
            o.put("index", cmd.index);
        }
        if (cmd.start != null) {
            o.put("start", cmd.start);
        }
        if (cmd.quantity != null) {
            o.put("quantity", cmd.quantity);
        }
        if (cmd.dataType != null) {
            o.put("dataType", cmd.dataType);
        }
        if (cmd.byteOrder != null) {
            o.put("byteOrder", cmd.byteOrder);
        }
        if (cmd.scale != null) {
            o.put("scale", cmd.scale);
        }
        if (cmd.unit != null) {
            o.put("unit", cmd.unit);
        }
        if (cmd.coilState != null) {
            o.put("coilState", cmd.coilState);
        }
        if (cmd.registerValue != null) {
            o.put("registerValue", cmd.registerValue);
        }
        if (cmd.coils != null) {
            o.put("coils", ModbusCommandCoilCodec.encode(cmd.coils));
        }
        if (cmd.registers != null) {
            o.put("registers", ModbusCommandRegisterCodec.encode(cmd.registers));
        }
        return o;
    }

    public static JsonArray encode(List<ModbusCommand> commands) {
        JsonArray arr = new JsonArray();
        if (commands != null) {
            for (ModbusCommand command : commands) {
                arr.add(encode(command));
            }
        }
        return arr;
    }
}
