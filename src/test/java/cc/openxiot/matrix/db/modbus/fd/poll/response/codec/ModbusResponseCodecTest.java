package cc.openxiot.matrix.db.modbus.fd.poll.response.codec;

import cc.openxiot.matrix.db.modbus.fd.poll.response.ModbusResponse;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusResponseCodec}：应答定义（{@code response.fields[]}）与 JSON 的互转。
 *
 * <p>字段身份是 {@code registryIndex}（引用点表寄存器）。{@code fields} 为空与整段缺失都读作
 * 「没有应答定义」（写方法的样子），编回去也不留空壳键。</p>
 */
class ModbusResponseCodecTest {

    private static final JsonObject EXAMPLE = new JsonObject("""
            {
              "fields": [
                { "registryIndex": 1 },
                { "registryIndex": 2 }
              ]
            }
            """);

    @Test
    void decodeReadsFields() {
        ModbusResponse response = ModbusResponseCodec.decode(EXAMPLE);

        assertEquals(2, response.fields.size());
        assertEquals(1, response.fields.get(0).registryIndex.intValue());
        assertEquals(2, response.fields.get(1).registryIndex.intValue());
    }

    @Test
    void encodeDecodeRoundTripIsStable() {
        JsonObject once = ModbusResponseCodec.encode(ModbusResponseCodec.decode(EXAMPLE));
        JsonObject twice = ModbusResponseCodec.encode(ModbusResponseCodec.decode(once));

        assertEquals(EXAMPLE, once);
        assertEquals(once, twice);
    }

    @Test
    void missingResponseIsNull() {
        assertNull(ModbusResponseCodec.decode(null));
        assertNull(ModbusResponseCodec.decode(new JsonObject()).fields);
    }

    @Test
    void emptyFieldsDoNotProduceAKey() {
        ModbusResponse response = new ModbusResponse();
        response.fields = new ArrayList<>();

        assertEquals(new JsonObject(), ModbusResponseCodec.encode(response));
        assertEquals(new JsonObject(), ModbusResponseCodec.encode(new ModbusResponse()));
    }

    @Test
    void encodedFieldsKeepTheirOrder() {
        JsonObject o = ModbusResponseCodec.encode(ModbusResponseCodec.decode(EXAMPLE));
        assertEquals(1, o.getJsonArray("fields").getJsonObject(0).getInteger("registryIndex").intValue());
        assertEquals(2, o.getJsonArray("fields").getJsonObject(1).getInteger("registryIndex").intValue());
        assertTrue(o.containsKey("fields"));
    }
}