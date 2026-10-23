package cc.openxiot.matrix.db.modbus.service.transaction.response.codec;

import cc.openxiot.matrix.db.modbus.service.transaction.response.field.codec.ModbusTransactionResponseFieldCodec;
import cc.openxiot.matrix.db.modbus.service.transaction.response.ModbusTransactionResponse;
import io.vertx.core.json.JsonObject;

/**
 * {@link ModbusTransactionResponse}（应答定义）与 JSON 的互转。
 *
 * <p>沿用既有约定：值为 null 就不出键；{@code fields} 为空也不出键（与
 * {@code ModbusTransactionRequestCodec} 对 {@code fields} 同一条口径 —— 库里不留 {@code fields: []}
 * 这种噪音）。调用方 {@code ModbusTransactionCodec} 再往上一层：连整个 {@code response} 都为空时
 * 那个键也不出，写方法因此是「整段没有应答定义」。</p>
 */
public final class ModbusTransactionResponseCodec {

    private ModbusTransactionResponseCodec() {
    }

    public static JsonObject encode(ModbusTransactionResponse response) {
        JsonObject o = new JsonObject();
        if (response == null) {
            return o;
        }
        if (response.fields != null && !response.fields.isEmpty()) {
            o.put("fields", ModbusTransactionResponseFieldCodec.encode(response.fields));
        }
        return o;
    }

    public static ModbusTransactionResponse decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusTransactionResponse response = new ModbusTransactionResponse();
        response.fields = ModbusTransactionResponseFieldCodec.decode(o.getJsonArray("fields"));
        return response;
    }
}
