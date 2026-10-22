package cc.openxiot.matrix.api.modbus.shadow;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusRegisterSnapshotCodec} 的对外 JSON：字段名、null 不出键。
 *
 * <p>这是对前端的契约（{@code /modbus/service/{spaceId}/{serviceId}/shadows} 返回体），
 * 改名字就是改接口 —— 纯单测钉住。前端判「有没有缓存」看 {@code value} 在不在，而不是判显式 null。</p>
 */
class ModbusRegisterSnapshotCodecTest {

    private static final long T0 = 1_700_000_000_000L;

    @Test
    void currentRegisterCarriesValueAndMeta() {
        ModbusRegisterSnapshot r = new ModbusRegisterSnapshot();
        r.name = "进水温度";
        r.area = "holding";
        r.dataType = "float32";
        r.unit = "℃";
        r.access = "R";
        r.pollingIndex = 1;
        r.value = 61.5;
        r.recordedAt = new Date(T0);
        r.status = ModbusRegisterSnapshot.STATUS_CURRENT;

        JsonObject json = ModbusRegisterSnapshotCodec.encode(r);

        assertEquals("进水温度", json.getString("name"));
        assertEquals("holding", json.getString("area"));
        assertEquals("float32", json.getString("dataType"));
        assertEquals("℃", json.getString("unit"));
        assertEquals("R", json.getString("access"));
        assertEquals(1, json.getInteger("pollingIndex"));
        assertEquals(61.5, json.getDouble("value"));
        assertEquals(T0, json.getLong("recordedAt"));
        assertEquals("current", json.getString("status"));

        assertFalse(json.containsKey("lastError"));
        assertFalse(json.containsKey("errorAt"));
    }

    @Test
    void staleRegisterCarriesErrorAndKeepsLastKnownValue() {
        ModbusRegisterSnapshot r = new ModbusRegisterSnapshot();
        r.name = "出水温度";
        r.access = "RW";
        r.pollingIndex = 2;
        r.value = 35.0;
        r.recordedAt = new Date(T0);
        r.status = ModbusRegisterSnapshot.STATUS_STALE;
        r.lastError = "no response";
        r.errorAt = new Date(T0 + 60_000);

        JsonObject json = ModbusRegisterSnapshotCodec.encode(r);

        assertEquals(35.0, json.getDouble("value"));
        assertEquals("stale", json.getString("status"));
        assertEquals("no response", json.getString("lastError"));
        assertEquals(T0 + 60_000, json.getLong("errorAt"));
    }

    @Test
    void noDataOmitsValueKeysButKeepsStatus() {
        ModbusRegisterSnapshot r = new ModbusRegisterSnapshot();
        r.name = "压力";
        r.area = "input";
        r.access = "R";
        r.status = ModbusRegisterSnapshot.STATUS_NO_DATA;

        JsonObject json = ModbusRegisterSnapshotCodec.encode(r);

        assertEquals("no-data", json.getString("status"));
        assertFalse(json.containsKey("value"));
        assertFalse(json.containsKey("recordedAt"));
        assertFalse(json.containsKey("pollingIndex"));
        assertFalse(json.containsKey("dataType"));
        assertFalse(json.containsKey("unit"));
        assertNull(json.getString("unit"));
    }

    @Test
    void encodeListPreservesOrderAndCount() {
        ModbusRegisterSnapshot a = new ModbusRegisterSnapshot();
        a.name = "A";
        a.access = "R";
        a.status = ModbusRegisterSnapshot.STATUS_NO_DATA;
        ModbusRegisterSnapshot b = new ModbusRegisterSnapshot();
        b.name = "B";
        b.access = "R";
        b.status = ModbusRegisterSnapshot.STATUS_CURRENT;
        b.value = 1;

        var arr = ModbusRegisterSnapshotCodec.encode(List.of(a, b));

        assertEquals(2, arr.size());
        assertTrue(arr.getJsonObject(0).getString("name").equals("A"));
        assertEquals("B", arr.getJsonObject(1).getString("name"));
    }
}