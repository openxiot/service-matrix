package cc.openxiot.matrix.db.modbus.service.codec;

import cc.openxiot.common.person.codec.PersonCodec;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.polling.codec.ModbusPollingCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusService} 与 JSON 的互转。
 *
 * <p>{@link #encode} 用于返回给前端：主键输出为十六进制字符串，子结构逐个委派给各自的 Codec。
 * {@link #decode} 把请求体转成实体：{@code id} / {@code creator} / {@code updater}
 * 不由请求体决定（id 由 Panache 生成、人员取当前登录用户），故不在这里解。</p>
 *
 * <p>{@link #encodeBrief} 是「只够列个清单」的精简视图，给空间图这类一次要带很多实体的地方用：
 * 整份定义（functions / response）动辄几十 KB，清单里用不上。</p>
 */
public final class ModbusServiceCodec {

    /** 精简视图的 {@code type}：前端把服务与设备混在一张表里渲染时，靠它区分。 */
    private static final String TYPE = "modbus";

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
        if (service.name != null) {
            o.put("name", service.name);
        }
        if (service.version != null) {
            o.put("version", service.version);
        }
        if (service.configId != null) {
            o.put("configId", service.configId);
        }
        o.put("device", DeviceOnCodec.encode(service.device));
        o.put("functions", ModbusPollingCodec.encode(service.functions));
        if (service.creator != null) {
            o.put("creator", PersonCodec.encode(service.creator));
        }
        if (service.updater != null) {
            o.put("updater", PersonCodec.encode(service.updater));
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

    /**
     * 精简视图：{@code id} / {@code name} / {@code type}（固定 {@code modbus}）+ 依赖设备的
     * {@code spaceId} 与 {@code did}，取详情另调 `/one/{id}`。
     *
     * <p>{@code spaceId} / {@code did} 来自 {@link ModbusService#device}：清单里靠它们把服务和设备对上
     * （同一张表渲染、按空间/设备筛选），所以精简查询也要投影这两个字段（见
     * {@code ModbusServiceRepository.findBriefBySpaceId}）。</p>
     */
    public static JsonObject encodeBrief(ModbusService service) {
        JsonObject o = new JsonObject();
        if (service == null) {
            return o;
        }
        if (service.id != null) {
            o.put("id", service.id.toHexString());
        }
        if (service.name != null) {
            o.put("name", service.name);
        }
        o.put("type", TYPE);
        if (service.device != null) {
            if (service.device.did != null) {
                o.put("did", service.device.did);
            }
            if (service.device.space != null && service.device.space.spaceId != null) {
                o.put("spaceId", service.device.space.spaceId);
            }
        }
        return o;
    }

    public static JsonArray encodeBrief(List<ModbusService> services) {
        JsonArray arr = new JsonArray();
        if (services != null) {
            for (ModbusService service : services) {
                arr.add(encodeBrief(service));
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
        service.device = DeviceOnCodec.decode(o.getJsonObject("device"));
        service.functions = ModbusPollingCodec.decode(o.getJsonArray("functions"));
        return service;
    }
}
