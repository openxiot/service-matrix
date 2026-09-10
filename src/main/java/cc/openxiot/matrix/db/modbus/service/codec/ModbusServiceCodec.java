package cc.openxiot.matrix.db.modbus.service.codec;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusService} 与 JSON 的互转。
 *
 * <p>{@link #encode} 用于返回给前端：主键输出为十六进制字符串，子结构逐个委派给各自的 Codec。
 * {@link #decode} 把请求体转成实体：{@code id} / {@code orgId} / {@code creator} / {@code updater}
 * 不由请求体决定（id 由 Panache 生成、org 取 {@code X-Org-Id}、人员取当前登录用户），故不在这里解。</p>
 */
public final class ModbusServiceCodec {

    private ModbusServiceCodec() {
    }

    public static JsonObject encode(ModbusService service) {
        JsonObject o = new JsonObject();
        if (service == null) {
            return o;
        }
        if (service.id != null) {
            // 落库主键是 ObjectId，对外一律十六进制字符串（URL 路径参数 / invoke 请求体同口径）
            o.put("id", service.id.toHexString());
        }
        if (service.orgId != null) {
            o.put("orgId", service.orgId);
        }
        if (service.name != null) {
            o.put("name", service.name);
        }
        if (service.version != null) {
            o.put("version", service.version);
        }
        if (service.configId != null) {
            o.put("configId", service.configId);
        }
        o.put("parent", ModbusServiceParentCodec.encode(service.parent));
        o.put("functions", ModbusServiceFunctionCodec.encode(service.functions));
        if (service.creator != null) {
            o.put("creator", personToJson(service.creator));
        }
        if (service.updater != null) {
            o.put("updater", personToJson(service.updater));
        }
        return o;
    }

    public static JsonArray encode(List<ModbusService> services) {
        JsonArray arr = new JsonArray();
        if (services != null) {
            for (ModbusService service : services) {
                arr.add(encode(service));
            }
        }
        return arr;
    }

    public static ModbusService decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusService service = new ModbusService();
        service.name = o.getString("name");
        service.version = o.getInteger("version");
        service.configId = o.getString("configId");
        service.parent = ModbusServiceParentCodec.decode(o.getJsonObject("parent"));
        service.functions = ModbusServiceFunctionCodec.decode(o.getJsonArray("functions"));
        return service;
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
}
