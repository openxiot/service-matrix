package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.matrix.db.codec.PersonCodec;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * {@link ModbusConfig} 与 JSON 的互转（原 {@code ModbusJson}，与 {@code ModbusServiceCodec} 同形
 * 拆到实体的 codec 子包，子结构逐个委派给各自的 Codec）。
 *
 * <p>只有 {@link #encode}：请求体由 Jackson 直接绑成实体（{@code ModbusConfigResource.createOne}
 * 的形参），服务端字段（id / orgId / lifecycle / 人员）的收口在 Service 里做，不在这里解。</p>
 */
public final class ModbusConfigCodec {

    private ModbusConfigCodec() {
    }

    public static JsonObject encode(ModbusConfig c) {
        JsonObject o = new JsonObject();
        if (c == null) {
            return o;
        }
        if (c.id != null) {
            // 落库主键是 ObjectId，对外一律十六进制字符串（URL 路径参数、虚拟设备 configId 同口径）
            o.put("id", c.id.toHexString());
        }
        if (c.orgId != null) {
            o.put("orgId", c.orgId);
        }
        o.put("slave", ModbusSlaveCodec.encode(c.slave));
        if (c.visibility != null) {
            o.put("visibility", c.visibility);
        }
        if (c.lifecycle != null) {
            o.put("lifecycle", c.lifecycle);
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
