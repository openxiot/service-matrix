package cc.openxiot.matrix.db.modbus.service.codec;

import cc.openxiot.matrix.db.modbus.service.ModbusServiceParent;
import io.vertx.core.json.JsonObject;

/**
 * {@link ModbusServiceParent}（父设备调用坐标）与 JSON 的互转。
 */
public final class ModbusServiceParentCodec {

    private ModbusServiceParentCodec() {
    }

    public static JsonObject encode(ModbusServiceParent parent) {
        JsonObject o = new JsonObject();
        if (parent == null) {
            return o;
        }
        if (parent.did != null) {
            o.put("did", parent.did);
        }
        if (parent.siid != null) {
            o.put("siid", parent.siid);
        }
        if (parent.aiid != null) {
            o.put("aiid", parent.aiid);
        }
        if (parent.argument != null) {
            o.put("argument", parent.argument);
        }
        return o;
    }

    public static ModbusServiceParent decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusServiceParent parent = new ModbusServiceParent();
        parent.did = o.getString("did");
        parent.siid = o.getInteger("siid");
        parent.aiid = o.getInteger("aiid");
        parent.argument = o.getInteger("argument");
        return parent;
    }
}
