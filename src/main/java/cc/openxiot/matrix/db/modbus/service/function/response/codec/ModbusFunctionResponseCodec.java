package cc.openxiot.matrix.db.modbus.service.function.response.codec;

import cc.openxiot.matrix.db.modbus.service.function.response.field.codec.ModbusFunctionResponseFieldCodec;
import cc.openxiot.matrix.db.modbus.service.function.response.ModbusFunctionResponse;
import io.vertx.core.json.JsonObject;

/**
 * {@link ModbusFunctionResponse}（应答定义）与 JSON 的互转。
 *
 * <p>沿用既有约定：值为 null 就不出键；{@code fields} 为空也不出键（与
 * {@code ModbusFunctionRequestCodec} 对 {@code fields} 同一条口径 —— 库里不留 {@code fields: []}
 * 这种噪音）。调用方 {@code ModbusFunctionCodec} 再往上一层：连整个 {@code response} 都为空时
 * 那个键也不出，写方法因此是「整段没有应答定义」。</p>
 */
public final class ModbusFunctionResponseCodec {

    private ModbusFunctionResponseCodec() {
    }

    public static JsonObject encode(ModbusFunctionResponse response) {
        JsonObject o = new JsonObject();
        if (response == null) {
            return o;
        }
        if (response.fields != null && !response.fields.isEmpty()) {
            o.put("fields", ModbusFunctionResponseFieldCodec.encode(response.fields));
        }
        return o;
    }

    public static ModbusFunctionResponse decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusFunctionResponse response = new ModbusFunctionResponse();
        response.fields = ModbusFunctionResponseFieldCodec.decode(o.getJsonArray("fields"));
        return response;
    }
}
