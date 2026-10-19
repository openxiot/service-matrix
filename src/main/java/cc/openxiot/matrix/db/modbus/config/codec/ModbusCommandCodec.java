package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusCommand}（一个功能码动作）与 JSON 的互转。
 *
 * <p>字段按「所有功能码共用 → 读操作 → 写操作」的顺序输出，只输出有值的：
 * 读操作的 quantity / fields / bitNames / dataType / byteOrder，写操作的
 * coilState / registerValue / coils / registers 互不重叠，前端按 fc 取用。</p>
 */
public final class ModbusCommandCodec {

    private ModbusCommandCodec() {
    }

    public static JsonObject encode(ModbusCommand cmd) {
        JsonObject o = new JsonObject();
        if (cmd == null) {
            return o;
        }
        // 存量 fieldNames/命令级 scale-unit → fields 归一（幂等；迁移专用字段不进 JSON）
        ModbusCommand.upgradeLegacyFields(cmd);
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
        if (cmd.fields != null) {
            o.put("fields", encodeFields(cmd.fields));
        }
        if (cmd.bitNames != null) {
            o.put("bitNames", encodeBitNames(cmd.bitNames));
        }
        if (cmd.dataType != null) {
            o.put("dataType", cmd.dataType);
        }
        if (cmd.byteOrder != null) {
            o.put("byteOrder", cmd.byteOrder);
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

    /** 应答字段（03/04）：逐条输出 name + 自带 scale/unit（以寄存器为中心，各字段独立）。 */
    private static JsonArray encodeFields(List<ModbusCommand.Field> fields) {
        JsonArray arr = new JsonArray();
        for (ModbusCommand.Field field : fields) {
            JsonObject o = new JsonObject();
            if (field.name != null) {
                o.put("name", field.name);
            }
            if (field.scale != null) {
                o.put("scale", field.scale);
            }
            if (field.unit != null) {
                o.put("unit", field.unit);
            }
            arr.add(o);
        }
        return arr;
    }

    /** 位名称（01/02）：逐条输出 offset + name（与 0F 的 coils 同形，前端表格直读）。 */
    private static JsonArray encodeBitNames(List<ModbusCommand.BitName> bitNames) {
        JsonArray arr = new JsonArray();
        for (ModbusCommand.BitName bit : bitNames) {
            JsonObject o = new JsonObject();
            if (bit.offset != null) {
                o.put("offset", bit.offset);
            }
            if (bit.name != null) {
                o.put("name", bit.name);
            }
            arr.add(o);
        }
        return arr;
    }
}
