package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusCommand.ModbusCommandWriteItem}（写操作 05/06/0F/10 的每一条：引用寄存器 + 原始值）
 * 与 JSON 的互转，输出形如 {@code {registryIndex, value}}。
 *
 * <p>写命令统一成一条 writeItems 列表后，原有的单线圈(05)/单寄存器(06)/多线圈(0F Coil)/
 * 多寄存器(10 Register) 四种内联结构全部合并为这一种，这里即唯一入口
 * （{@code ModbusCommandCoilCodec} 已随之删除）。</p>
 */
public final class ModbusCommandRegisterCodec {

    private ModbusCommandRegisterCodec() {
    }

    public static JsonObject encode(ModbusCommand.ModbusCommandWriteItem item) {
        JsonObject o = new JsonObject();
        if (item == null) {
            return o;
        }
        if (item.registryIndex != null) {
            o.put("registryIndex", item.registryIndex);
        }
        if (item.value != null) {
            o.put("value", item.value);
        }
        return o;
    }

    public static JsonArray encode(List<ModbusCommand.ModbusCommandWriteItem> items) {
        JsonArray arr = new JsonArray();
        if (items != null) {
            for (ModbusCommand.ModbusCommandWriteItem item : items) {
                arr.add(encode(item));
            }
        }
        return arr;
    }
}