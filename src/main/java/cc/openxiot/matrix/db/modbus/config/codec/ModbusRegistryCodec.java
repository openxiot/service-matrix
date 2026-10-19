package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusRegistry}（一个寄存器/点位定义）与 JSON 的互转。
 *
 * <p>只输出有值的字段。byteOrder 仅 32 位值有、unit/scale 可选、coil/discrete 的 dataType 免填，
 * 前端按 area 取用。</p>
 */
public final class ModbusRegistryCodec {

    private ModbusRegistryCodec() {
    }

    public static JsonObject encode(ModbusRegistry reg) {
        JsonObject o = new JsonObject();
        if (reg == null) {
            return o;
        }
        if (reg.name != null) {
            o.put("name", reg.name);
        }
        if (reg.index != null) {
            o.put("index", reg.index);
        }
        if (reg.address != null) {
            o.put("address", reg.address);
        }
        if (reg.area != null) {
            o.put("area", reg.area);
        }
        if (reg.dataType != null) {
            o.put("dataType", reg.dataType);
        }
        if (reg.byteOrder != null) {
            o.put("byteOrder", reg.byteOrder);
        }
        if (reg.access != null) {
            o.put("access", reg.access);
        }
        if (reg.scale != null) {
            o.put("scale", reg.scale);
        }
        if (reg.unit != null) {
            o.put("unit", reg.unit);
        }
        if (reg.enumMap != null) {
            o.put("enumMap", reg.enumMap);
        }
        return o;
    }

    public static JsonArray encode(List<ModbusRegistry> registries) {
        JsonArray arr = new JsonArray();
        if (registries != null) {
            for (ModbusRegistry registry : registries) {
                arr.add(encode(registry));
            }
        }
        return arr;
    }
}