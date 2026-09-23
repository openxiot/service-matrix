package cc.openxiot.matrix.db.modbus.service.function.request.codec;

import cc.openxiot.matrix.db.modbus.service.function.request.ModbusFunctionRequest;
import io.vertx.core.json.JsonObject;

/**
 * {@link ModbusFunctionRequest}（请求帧定义）与 JSON 的互转。
 *
 * <p>沿用既有约定：值为 null 就不出键；{@code fields} 为空也不出键（库里不留 {@code fields: []}
 * 这种噪音 —— 读方法本来就没有写字段）。{@code decode} 对空数组给的是一个空列表（与
 * {@code ModbusFunctionResponseFieldCodec} 同形），再编回去那个键就没了。</p>
 */
public final class ModbusFunctionRequestCodec {

    private ModbusFunctionRequestCodec() {
    }

    public static JsonObject encode(ModbusFunctionRequest request) {
        JsonObject o = new JsonObject();
        if (request == null) {
            return o;
        }
        if (request.slaveId != null) {
            o.put("slaveId", request.slaveId);
        }
        if (request.fc != null) {
            o.put("fc", request.fc);
        }
        if (request.start != null) {
            o.put("start", request.start);
        }
        if (request.quantity != null) {
            o.put("quantity", request.quantity);
        }
        if (request.fields != null && !request.fields.isEmpty()) {
            o.put("fields", ModbusFunctionRequestFieldCodec.encode(request.fields));
        }
        return o;
    }

    public static ModbusFunctionRequest decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusFunctionRequest request = new ModbusFunctionRequest();
        request.slaveId = o.getInteger("slaveId");
        request.fc = o.getString("fc");
        request.start = o.getInteger("start");
        request.quantity = o.getInteger("quantity");
        request.fields = ModbusFunctionRequestFieldCodec.decode(o.getJsonArray("fields"));
        return request;
    }
}
