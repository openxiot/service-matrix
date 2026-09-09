package cc.openxiot.matrix.api.modbus.config;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusDeviceType;
import cc.openxiot.matrix.db.modbus.config.ModbusSlave;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * 将实体投影为返回给前端的 JsonObject/JsonArray（日期以字符串形式输出）。
 */
public final class ModbusJson {

    private ModbusJson() {
    }

    public static JsonObject toJson(ModbusConfig c) {
        JsonObject o = new JsonObject();
        if (c == null) {
            return o;
        }
        if (c.id != null) {
            o.put("id", c.id);
        }
        if (c.orgId != null) {
            o.put("orgId", c.orgId);
        }
        o.put("slave", slaveToJson(c.slave));
        if (c.visibility != null) {
            o.put("visibility", c.visibility);
        }
        JsonArray arr = new JsonArray();
        if (c.commands != null) {
            for (ModbusCommand cmd : c.commands) {
                arr.add(commandToJson(cmd));
            }
        }
        o.put("commands", arr);
        if (c.creator != null) {
            o.put("creator", personToJson(c.creator));
        }
        if (c.updater != null) {
            o.put("updater", personToJson(c.updater));
        }
        return o;
    }

    private static JsonObject slaveToJson(ModbusSlave s) {
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
            o.put("type", deviceTypeToJson(s.type));
        }
        if (s.description != null) {
            o.put("description", s.description);
        }
        return o;
    }

    private static JsonObject deviceTypeToJson(ModbusDeviceType t) {
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

    private static JsonObject personToJson(Person p) {
        JsonObject o = new JsonObject();
        if (p.id != null) {
            o.put("id", p.id);
        }
        if (p.name != null) {
            o.put("name", p.name);
        }
        if (p.timestamp != null) {
            o.put("timestamp", p.timestamp.getTime());
        }
        return o;
    }

    public static JsonArray toArray(List<ModbusConfig> list) {
        JsonArray arr = new JsonArray();
        if (list != null) {
            for (ModbusConfig c : list) {
                arr.add(toJson(c));
            }
        }
        return arr;
    }

    private static JsonObject commandToJson(ModbusCommand cmd) {
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
            JsonArray arr = new JsonArray();
            for (ModbusCommand.Coil c : cmd.coils) {
                arr.add(coilToJson(c));
            }
            o.put("coils", arr);
        }
        if (cmd.registers != null) {
            JsonArray arr = new JsonArray();
            for (ModbusCommand.Register r : cmd.registers) {
                arr.add(registerToJson(r));
            }
            o.put("registers", arr);
        }
        return o;
    }

    private static JsonObject coilToJson(ModbusCommand.Coil c) {
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

    private static JsonObject registerToJson(ModbusCommand.Register r) {
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
}
