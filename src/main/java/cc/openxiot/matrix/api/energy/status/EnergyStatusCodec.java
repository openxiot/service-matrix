package cc.openxiot.matrix.api.energy.status;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/** 储能实时状态的 JSON 组装（键名与前端 `EnergyStatus` 类型一一对应）。 */
public final class EnergyStatusCodec {

    private EnergyStatusCodec() {
    }

    public static JsonObject encode(EnergyStatus status) {
        return new JsonObject()
                .put("spaceId", status.spaceId())
                .put("type", status.type())
                .put("groups", array(status.groups()))
                .put("total", group(status.total()));
    }

    private static JsonArray array(List<EnergyStatus.Group> groups) {
        JsonArray array = new JsonArray();
        if (groups != null) {
            for (EnergyStatus.Group group : groups) {
                array.add(group(group));
            }
        }
        return array;
    }

    private static JsonObject group(EnergyStatus.Group group) {
        if (group == null) {
            return new JsonObject();
        }
        return new JsonObject()
                .put("configId", group.configId())
                .put("name", group.name())
                .put("soc", group.soc())
                .put("capacity", group.capacity())
                .put("available", group.available())
                .put("power", group.power())
                .put("soh", group.soh())
                .put("temperature", group.temperature())
                .put("cycles", group.cycles())
                .put("charge", group.charge())
                .put("discharge", group.discharge())
                .put("updatedAt", group.updatedAt());
    }
}
