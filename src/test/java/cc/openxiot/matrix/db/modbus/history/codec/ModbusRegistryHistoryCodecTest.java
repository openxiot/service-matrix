package cc.openxiot.matrix.db.modbus.history.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import cc.openxiot.matrix.db.modbus.history.ModbusRegistryHistory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/** {@link ModbusRegistryHistoryCodec} 的序列化：成功带值、失败带标记，互斥不混。 */
class ModbusRegistryHistoryCodecTest {

    private static final Date AT = new Date(1_700_000_000_000L);

    @Test
    void encode_success_carriesValue() {
        JsonObject o = ModbusRegistryHistoryCodec.encode(event(null, false, 23.5, null));
        assertEquals(AT.getTime(), o.getLong("at").longValue());
        assertEquals(23.5, o.getDouble("value").doubleValue());
        assertFalse(o.containsKey("failure"));
        assertFalse(o.containsKey("message"));
    }

    @Test
    void encode_failure_carriesMarkerAndMessage_notValue() {
        JsonObject o = ModbusRegistryHistoryCodec.encode(event(null, true, null, "modbus timeout"));
        assertTrue(o.getBoolean("failure"));
        assertEquals("modbus timeout", o.getString("message"));
        assertFalse(o.containsKey("value"));
    }

    @Test
    void encode_successButNullValue_givesExplicitNull() {
        // 「这次读没取到值」与「没有这一条」区分开：成功但 value 缺失给显式 null
        JsonObject o = ModbusRegistryHistoryCodec.encode(event(null, false, null, null));
        assertTrue(o.containsKey("value"));
        assertNull(o.getValue("value"));
        assertFalse(o.containsKey("failure"));
    }

    @Test
    void encodeList_preservesOrder() {
        JsonArray arr = ModbusRegistryHistoryCodec.encode(List.of(
                event(1, false, 1.0, null),
                event(2, true, null, "err"),
                event(3, false, 2.0, null)));
        assertEquals(3, arr.size());
        assertTrue(arr.getJsonObject(0).containsKey("value"));
        assertTrue(arr.getJsonObject(1).getBoolean("failure"));
        assertTrue(arr.getJsonObject(2).containsKey("value"));
    }

    private static ModbusRegistryHistory event(Integer index, boolean failure, Double value, String message) {
        ModbusRegistryHistory e = new ModbusRegistryHistory();
        e.serviceId = "svc-1";
        e.registerIndex = index == null ? 1 : index;
        e.name = "温度";
        e.unit = "℃";
        e.at = AT;
        e.failure = failure;
        e.value = value;
        e.message = message;
        return e;
    }
}