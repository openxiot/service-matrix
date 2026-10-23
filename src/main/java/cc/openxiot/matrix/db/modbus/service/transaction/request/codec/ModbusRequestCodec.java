package cc.openxiot.matrix.db.modbus.service.transaction.request.codec;

import cc.openxiot.matrix.db.modbus.service.transaction.request.ModbusRequest;
import io.vertx.core.json.JsonObject;

/**
 * {@link ModbusRequest}（请求帧定义）与 JSON 的互转。
 *
 * <p>沿用既有约定：值为 null 就不出键；{@code fields} 为空也不出键（库里不留 {@code fields: []}
 * 这种噪音 —— 读方法本来就没有写字段）。{@code decode} 对空数组给的是一个空列表（与
 * {@code ModbusTransactionResponseFieldCodec} 同形），再编回去那个键就没了。</p>
 */
public final class ModbusRequestCodec {

    private ModbusRequestCodec() {
    }

    public static JsonObject encode(ModbusRequest request) {
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
            o.put("fields", ModbusRequestFieldCodec.encode(request.fields));
        }
        return o;
    }

    public static ModbusRequest decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusRequest request = new ModbusRequest();
        request.slaveId = o.getInteger("slaveId");
        request.fc = o.getString("fc");
        request.start = o.getInteger("start");
        request.quantity = o.getInteger("quantity");
        request.fields = ModbusRequestFieldCodec.decode(o.getJsonArray("fields"));
        return request;
    }
}
