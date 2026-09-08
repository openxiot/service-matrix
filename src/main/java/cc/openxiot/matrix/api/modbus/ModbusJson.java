package cc.openxiot.matrix.api.modbus;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.ModbusCommand;
import cc.openxiot.matrix.db.modbus.ModbusDeviceConfig;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * 将实体投影为返回给前端的 JsonObject/JsonArray（日期以字符串形式输出）。
 */
public final class ModbusJson {

    private ModbusJson() {
    }

    public static JsonObject toJson(ModbusDeviceConfig c) {
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
        if (c.manufacturer != null) {
            o.put("manufacturer", c.manufacturer);
        }
        if (c.model != null) {
            o.put("model", c.model);
        }
        if (c.slaveId != null) {
            o.put("slaveId", c.slaveId);
        }
        if (c.visibility != null) {
            o.put("visibility", c.visibility);
        }
        if (c.description != null) {
            o.put("description", c.description);
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

    public static JsonArray toArray(List<ModbusDeviceConfig> list) {
        JsonArray arr = new JsonArray();
        if (list != null) {
            for (ModbusDeviceConfig c : list) {
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
