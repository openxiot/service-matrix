package cc.openxiot.matrix.db.modbus.history.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Date;

import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/** {@link ModbusServiceShadowCodec}：当前值 + 最后成功时刻 + 可选的失败态，失败键缺省不出。 */
class ModbusServiceShadowCodecTest {

    private static final Date AT = new Date(1_700_000_000_000L);

    @Test
    void encode_normal_carriesValueAndRecordedAt() {
        JsonObject o = ModbusServiceShadowCodec.encode(shadow(null));
        assertEquals(1, o.getInteger("registerIndex").intValue());
        assertEquals("温度", o.getString("name"));
        assertEquals("℃", o.getString("unit"));
        assertEquals(23.5, o.getDouble("value").doubleValue());
        assertEquals(AT.getTime(), o.getLong("recordedAt").longValue());
        assertFalse(o.containsKey("lastError"));
        assertFalse(o.containsKey("errorAt"));
    }

    @Test
    void encode_failed_omitsErrorKeysWhenNull_keepsLastKnownValue() {
        ModbusServiceShadow s = shadow(null);
        s.lastError = "modbus timeout";
        s.errorAt = new Date(AT.getTime() + 1000);

        JsonObject o = ModbusServiceShadowCodec.encode(s);

        // 失败态保留最后一次成功的值；故障键出现
        assertEquals(23.5, o.getDouble("value").doubleValue());
        assertEquals("modbus timeout", o.getString("lastError"));
        assertEquals(AT.getTime() + 1000, o.getLong("errorAt").longValue());
    }

    private static ModbusServiceShadow shadow(Object value) {
        ModbusServiceShadow s = new ModbusServiceShadow();
        s.serviceId = "svc-1";
        s.registerIndex = 1;
        s.name = "温度";
        s.unit = "℃";
        s.value = value == null ? 23.5 : value;
        s.recordedAt = AT;
        return s;
    }
}