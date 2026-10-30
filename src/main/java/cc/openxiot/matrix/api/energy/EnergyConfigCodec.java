package cc.openxiot.matrix.api.energy;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.energy.EnergyConfig;
import cc.openxiot.matrix.db.energy.EnergyPoint;
import cc.openxiot.matrix.db.energy.EnergyType;
import cc.openxiot.matrix.db.energy.EnergyRole;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 能源配置的 JSON 编解码。
 *
 * <p>请求体走 {@link Request} 而不是直接反序列化 {@link EnergyConfig}：后者带
 * {@code ObjectId id} / 审计字段，客户端不该（也不能）填。{@code spaceId} 一律取路径参数，
 * 同样不从请求体读。</p>
 *
 * <p>点位里的 {@code name} / {@code unit} 是用户数据，原样存、原样下发，**不翻译**
 * （见 AGENTS.md 的 i18n 一节）。</p>
 */
public final class EnergyConfigCodec {

    private EnergyConfigCodec() {
    }

    /** 创建/更新配置的请求体。 */
    public static class Request {
        public String name;
        public EnergyRole role;
        public EnergyType type;
        public Double price;
        public Double chargePrice;
        public Double dischargePrice;
        public Integer periodHours;
        public Boolean enabled;
        public List<EnergyPoint> points;
    }

    /** 请求体 → 实体（id / spaceId / 审计字段由服务端补）。 */
    public static EnergyConfig decode(Request request) {
        EnergyConfig config = new EnergyConfig();
        if (request == null) {
            return config;
        }
        config.name = request.name;
        config.role = request.role;
        config.type = request.type;
        config.price = request.price;
        config.chargePrice = request.chargePrice;
        config.dischargePrice = request.dischargePrice;
        config.periodHours = request.periodHours;
        config.enabled = request.enabled == null || request.enabled;
        config.points = request.points == null ? new ArrayList<>() : new ArrayList<>(request.points);
        return config;
    }

    public static JsonArray encode(List<EnergyConfig> configs) {
        JsonArray array = new JsonArray();
        if (configs != null) {
            for (EnergyConfig config : configs) {
                array.add(encode(config));
            }
        }
        return array;
    }

    public static JsonObject encode(EnergyConfig config) {
        if (config == null) {
            return new JsonObject();
        }
        return new JsonObject()
                .put("id", config.id == null ? null : config.id.toHexString())
                .put("spaceId", config.spaceId)
                .put("name", config.name)
                .put("role", name(config.role))
                .put("type", name(config.type))
                .put("price", config.price)
                .put("chargePrice", config.chargePrice)
                .put("dischargePrice", config.dischargePrice)
                .put("periodHours", config.periodHours)
                .put("enabled", config.enabled)
                .put("points", encodePoints(config.points))
                .put("creator", encodePerson(config.creator))
                .put("updater", encodePerson(config.updater))
                .put("create", millis(config.create))
                .put("update", millis(config.update));
    }

    private static JsonArray encodePoints(List<EnergyPoint> points) {
        JsonArray array = new JsonArray();
        if (points == null) {
            return array;
        }
        for (EnergyPoint point : points) {
            if (point == null) {
                continue;
            }
            array.add(new JsonObject()
                    .put("source", name(point.source))
                    .put("did", point.did)
                    .put("siid", point.siid)
                    .put("piid", point.piid)
                    .put("fdid", point.fdid)
                    .put("registerIndex", point.registerIndex)
                    .put("metric", name(point.metric))
                    .put("mode", name(point.mode))
                    .put("name", point.name)
                    .put("unit", point.unit));
        }
        return array;
    }

    private static JsonObject encodePerson(Person person) {
        if (person == null) {
            return null;
        }
        return new JsonObject()
                .put("id", person.id)
                .put("name", person.name)
                .put("timestamp", millis(person.timestamp));
    }

    private static Long millis(Date date) {
        return date == null ? null : date.getTime();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
