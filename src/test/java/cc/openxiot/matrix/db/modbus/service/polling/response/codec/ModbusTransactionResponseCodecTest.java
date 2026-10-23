package cc.openxiot.matrix.db.modbus.service.polling.response.codec;

import cc.openxiot.matrix.db.modbus.service.polling.response.ModbusTransactionResponse;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusTransactionResponseCodec}：应答定义（{@code response.fields[]}）与 JSON 的互转。
 *
 * <p>与 {@code ModbusTransactionRequestCodecTest} 同一套路：语句是 {@code { "fields": [...] }}、
 * 键的顺序由码器定；{@code fields} 为空与整段缺失都读作「没有应答定义」（写方法的样子），
 * 编回去也不留空壳键。</p>
 */
class ModbusTransactionResponseCodecTest {

    private static final JsonObject EXAMPLE = new JsonObject("""
            {
              "fields": [
                { "index": 1, "field": "进水温度", "bytes": 2, "format": "uint16", "byteOrder": "ABCD", "scale": 0.1 },
                { "index": 2, "field": "开关状态", "bytes": 2, "format": "uint16", "byteOrder": "ABCD" }
              ]
            }
            """);

    @Test
    void decodeReadsFields() {
        ModbusTransactionResponse response = ModbusTransactionResponseCodec.decode(EXAMPLE);

        assertEquals(2, response.fields.size());
        assertEquals("进水温度", response.fields.get(0).field);
        assertEquals(1, response.fields.get(0).index.intValue());
        assertNull(response.fields.get(1).unit);      // 没给的项不凭空生成
    }

    @Test
    void encodeDecodeRoundTripIsStable() {
        JsonObject once = ModbusTransactionResponseCodec.encode(ModbusTransactionResponseCodec.decode(EXAMPLE));
        JsonObject twice = ModbusTransactionResponseCodec.encode(ModbusTransactionResponseCodec.decode(once));

        assertEquals(EXAMPLE, once);
        assertEquals(once, twice);
    }

    @Test
    void missingResponseIsNull() {
        // 写方法：整个 response 键都没有 —— 读出来就是 null，与「配了个空」不是一回事
        assertNull(ModbusTransactionResponseCodec.decode(null));
        assertNull(ModbusTransactionResponseCodec.decode(new JsonObject()).fields);
    }

    @Test
    void emptyFieldsDoNotProduceAKey() {
        ModbusTransactionResponse response = new ModbusTransactionResponse();
        response.fields = new ArrayList<>();

        // 空表与 null 编出来是同一个空对象（`fields: []` 是噪音，库里不留）
        assertEquals(new JsonObject(), ModbusTransactionResponseCodec.encode(response));
        assertEquals(new JsonObject(), ModbusTransactionResponseCodec.encode(new ModbusTransactionResponse()));
    }

    @Test
    void encodedFieldsKeepTheirOrder() {
        // 顺序是语义：parser 按 index 顺序、以 bytes 依次累加偏移切分数据区
        JsonObject o = ModbusTransactionResponseCodec.encode(ModbusTransactionResponseCodec.decode(EXAMPLE));
        assertEquals(1, o.getJsonArray("fields").getJsonObject(0).getInteger("index").intValue());
        assertEquals(2, o.getJsonArray("fields").getJsonObject(1).getInteger("index").intValue());
        assertFalse(o.containsKey("extra"));
        assertTrue(o.containsKey("fields"));
    }
}
