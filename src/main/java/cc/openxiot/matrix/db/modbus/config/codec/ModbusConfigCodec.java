package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.common.person.codec.PersonCodec;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;

import java.util.List;

/**
 * {@link ModbusConfig} 与 JSON 的互转（原 {@code ModbusJson}，与 {@code ModbusServiceCodec} 同形
 * 拆到实体的 codec 子包，子结构逐个委派给各自的 Codec）。
 *
 * <p>{@link #encode} 用于下发；{@link #decode} 只用于解服务里的 {@code configSnapshot}
 * （点表自己的请求体由 Jackson 直接绑成实体，见 {@code ModbusConfigResource.createOne}，
 * 服务端字段 id / owner / lifecycle / 人员的收口在 Service 里做）。</p>
 */
public final class ModbusConfigCodec {

    private ModbusConfigCodec() {
    }

    /**
     * JSON → 实体（{@link #encode} 的逆）。
     *
     * <p>{@code id} 在 JSON 里是十六进制字符串，而 {@link ObjectId} 没有 Jackson 认的字符串
     * creator：先摘掉再交给 Jackson，最后一个字段设回。快照的 JSON 形状就是 {@link #encode}
     * 的输出（前端原样回传），故其余字段按公有字段名直映即可。</p>
     */
    public static ModbusConfig decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        JsonObject body = o.copy();
        Object rawId = body.remove("id");
        ModbusConfig c = body.mapTo(ModbusConfig.class);
        if (rawId instanceof String id && ObjectId.isValid(id)) {
            c.id = new ObjectId(id);
        }
        return c;
    }

    public static JsonObject encode(ModbusConfig c) {
        JsonObject o = new JsonObject();
        if (c == null) {
            return o;
        }
        if (c.id != null) {
            // 落库主键是 ObjectId，对外一律十六进制字符串（URL 路径参数、服务里的 configSnapshot.id 同口径）
            o.put("id", c.id.toHexString());
        }
        if (c.owner != null) {
            JsonObject owner = new JsonObject();
            if (c.owner.id != null) {
                owner.put("id", c.owner.id);
            }
            if (c.owner.type != null) {
                owner.put("type", c.owner.type);
            }
            if (c.owner.name != null) {
                owner.put("name", c.owner.name);
            }
            o.put("owner", owner);
        }
        o.put("slave", ModbusSlaveCodec.encode(c.slave));
        if (c.visibility != null) {
            o.put("visibility", c.visibility);
        }
        if (c.lifecycle != null) {
            o.put("lifecycle", c.lifecycle);
        }
        if (c.registries != null) {
            o.put("registries", ModbusRegistryCodec.encode(c.registries));
        }
        o.put("commands", ModbusCommandCodec.encode(c.commands));
        if (c.creator != null) {
            o.put("creator", PersonCodec.encode(c.creator));
        }
        if (c.updater != null) {
            o.put("updater", PersonCodec.encode(c.updater));
        }
        return o;
    }

    public static JsonArray encode(List<ModbusConfig> configs) {
        JsonArray arr = new JsonArray();
        if (configs != null) {
            for (ModbusConfig config : configs) {
                arr.add(encode(config));
            }
        }
        return arr;
    }
}
