package cc.openxiot.matrix.db.modbus.fd.codec;

import cc.openxiot.matrix.db.modbus.fd.DeviceOn;
import cc.openxiot.matrix.db.space.SpaceOn;
import io.vertx.core.json.JsonObject;

/**
 * {@link DeviceOn}（服务依赖的设备：调用坐标 + 所在空间）与 JSON 的互转。
 */
public final class DeviceOnCodec {

    private DeviceOnCodec() {
    }

    public static JsonObject encode(DeviceOn dependent) {
        JsonObject o = new JsonObject();
        if (dependent == null) {
            return o;
        }
        if (dependent.did != null) {
            o.put("did", dependent.did);
        }
        if (dependent.siid != null) {
            o.put("siid", dependent.siid);
        }
        if (dependent.aiid != null) {
            o.put("aiid", dependent.aiid);
        }
        if (dependent.argument != null) {
            o.put("argument", dependent.argument);
        }
        if (dependent.space != null) {
            o.put("space", spaceToJson(dependent.space));
        }
        return o;
    }

    public static DeviceOn decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        DeviceOn dependent = new DeviceOn();
        dependent.did = o.getString("did");
        dependent.siid = o.getInteger("siid");
        dependent.aiid = o.getInteger("aiid");
        dependent.argument = o.getInteger("argument");
        dependent.space = spaceFromJson(o.getJsonObject("space"));
        return dependent;
    }

    private static JsonObject spaceToJson(SpaceOn space) {
        JsonObject o = new JsonObject();
        if (space.spaceId != null) {
            o.put("spaceId", space.spaceId);
        }
        if (space.rootId != null) {
            o.put("rootId", space.rootId);
        }
        return o;
    }

    private static SpaceOn spaceFromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        SpaceOn space = new SpaceOn();
        space.spaceId = o.getString("spaceId");
        space.rootId = o.getString("rootId");
        return space;
    }
}
