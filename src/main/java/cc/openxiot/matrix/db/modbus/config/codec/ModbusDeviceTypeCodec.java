package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.matrix.db.modbus.config.ModbusDeviceType;
import io.vertx.core.json.JsonObject;

/**
 * {@link ModbusDeviceType}（设备类型：品类 URN + 多语文案快照）与 JSON 的互转。
 *
 * @deprecated 随 {@link ModbusDeviceType} 一起废弃，暂留以兼容存量文档的回显；新代码不要再引用。
 */
@Deprecated
public final class ModbusDeviceTypeCodec {

    private ModbusDeviceTypeCodec() {
    }

    public static JsonObject encode(ModbusDeviceType t) {
        JsonObject o = new JsonObject();
        if (t == null) {
            return o;
        }
        if (t.specDescription != null && !t.specDescription.isEmpty()) {
            JsonObject m = new JsonObject();
            t.specDescription.forEach(m::put);
            o.put("specDescription", m);
        }
        if (t.typeDescription != null && !t.typeDescription.isEmpty()) {
            JsonObject m = new JsonObject();
            t.typeDescription.forEach(m::put);
            o.put("typeDescription", m);
        }
        if (t.type != null) {
            o.put("type", t.type);
        }
        return o;
    }
}
