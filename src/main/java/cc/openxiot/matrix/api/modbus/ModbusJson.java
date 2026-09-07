package cc.openxiot.matrix.api.modbus;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.ModbusDeviceConfig;
import cc.openxiot.matrix.db.modbus.ModbusPoint;
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
        if (c.points != null) {
            for (ModbusPoint p : c.points) {
                arr.add(pointToJson(p));
            }
        }
        o.put("points", arr);
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

    private static JsonObject pointToJson(ModbusPoint p) {
        JsonObject o = new JsonObject();
        if (p == null) {
            return o;
        }
        if (p.name != null) {
            o.put("name", p.name);
        }
        if (p.area != null) {
            o.put("area", p.area);
        }
        if (p.address != null) {
            o.put("address", p.address);
        }
        if (p.logicalAddress != null) {
            o.put("logicalAddress", p.logicalAddress);
        }
        if (p.dataType != null) {
            o.put("dataType", p.dataType);
        }
        if (p.rw != null) {
            o.put("rw", p.rw);
        }
        if (p.scale != null) {
            o.put("scale", p.scale);
        }
        if (p.unit != null) {
            o.put("unit", p.unit);
        }
        if (p.description != null) {
            o.put("description", p.description);
        }
        return o;
    }
}
