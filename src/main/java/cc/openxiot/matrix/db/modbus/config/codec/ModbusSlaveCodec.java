package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.matrix.db.modbus.config.ModbusSlave;
import io.vertx.core.json.JsonObject;

/**
 * {@link ModbusSlave}（从站设备信息）与 JSON 的互转。
 */
public final class ModbusSlaveCodec {

    private ModbusSlaveCodec() {
    }

    public static JsonObject encode(ModbusSlave s) {
        JsonObject o = new JsonObject();
        if (s == null) {
            return o;
        }
        if (s.manufacturer != null) {
            o.put("manufacturer", s.manufacturer);
        }
        if (s.model != null) {
            o.put("model", s.model);
        }
        if (s.slaveId != null) {
            o.put("slaveId", s.slaveId);
        }
        if (s.type != null) {
            o.put("type", ModbusDeviceTypeCodec.encode(s.type));
        }
        if (s.description != null) {
            o.put("description", s.description);
        }
        return o;
    }
}
