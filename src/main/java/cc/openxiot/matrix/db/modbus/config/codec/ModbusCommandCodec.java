package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusCommand}（一个功能码动作）与 JSON 的互转。
 *
 * <p>以寄存器为中心：解码/编码元数据不再内联。字段按「所有功能码共用 → 写操作」输出，只输出有值的：
 * 读操作的 quantity，写操作的 writeItems（{@code [{registryIndex, value}]}）互不重叠，前端按 fc 取用。</p>
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
        if (cmd.writeItems != null) {
            o.put("writeItems", ModbusCommandRegisterCodec.encode(cmd.writeItems));
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