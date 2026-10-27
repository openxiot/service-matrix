package cc.openxiot.matrix.db.modbus.fd.poll.response.codec;

import cc.openxiot.matrix.db.modbus.fd.poll.response.field.codec.ModbusResponseFieldCodec;
import cc.openxiot.matrix.db.modbus.fd.poll.response.ModbusResponse;
import io.vertx.core.json.JsonObject;

/**
 * {@link ModbusResponse}（应答定义）与 JSON 的互转。
 *
 * <p>沿用既有约定：值为 null 就不出键；{@code fields} 为空也不出键（与
 * {@code ModbusPollRequestCodec} 对 {@code fields} 同一条口径 —— 库里不留 {@code fields: []}
 * 这种噪音）。调用方 {@code ModbusPollCodec} 再往上一层：连整个 {@code response} 都为空时
 * 那个键也不出，写方法因此是「整段没有应答定义」。</p>
 */
public final class ModbusResponseCodec {

    private ModbusResponseCodec() {
    }

    public static JsonObject encode(ModbusResponse response) {
        JsonObject o = new JsonObject();
        if (response == null) {
            return o;
        }
        if (response.fields != null && !response.fields.isEmpty()) {
            o.put("fields", ModbusResponseFieldCodec.encode(response.fields));
        }
        return o;
    }

    public static ModbusResponse decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusResponse response = new ModbusResponse();
        response.fields = ModbusResponseFieldCodec.decode(o.getJsonArray("fields"));
        return response;
    }
}
