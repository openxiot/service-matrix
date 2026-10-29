package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.api.modbus.history.ModbusRegistryHistoryWindow.Bucket;
import cc.openxiot.matrix.api.modbus.history.ModbusRegistryHistoryWindow.Window;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区间曲线响应体（{@code ModbusRegistryHistoryRangeCodec}）的对外 JSON：键名、点集形态的分流
 * （采样 vs 降采样桶）、「没有 carry-in 就不出键」那条规则、请求身份（identity）的回显。
 *
 * <p>这些是对前端的契约，改键名就是改接口 —— 所以哪怕只有几行也要钉住。纯单测，不需要数据库。</p>
 */
class ModbusRegistryHistoryRangeCodecTest {

    private static final long T0 = 1_700_000_000_000L;

    private static ModbusRegistryHistoryRange range(Window window) {
        return new ModbusRegistryHistoryRange("svc-1", 2, "进水温度",
                new Date(T0), new Date(T0 + 10_000), 8, window);
    }

    private static ModbusRegistryHistorySample sample(long at, double value) {
        return new ModbusRegistryHistorySample(new Date(at), value);
    }

    private static Bucket bucket(long at) {
        return new Bucket(at, at + 1000, 2, 75.0, 77.0, 75.0, 77.0, 76.0, null);
    }

    @Test
    void notDownsampledSamplesBecomePointsAndEchoIdentity() {
        Window window = new Window(
                sample(T0, 75.0),                       // carry-in
                List.of(sample(T0 + 1000, 76.0), sample(T0 + 2000, 77.0)),
                List.of(),                              // buckets 为空，非降采样
                false);
        JsonObject body = ModbusRegistryHistoryRangeCodec.encode(range(window));

        // 请求带下来的身份/坐标原样回显
        assertEquals("svc-1", body.getString("fdid"));
        assertEquals(2, body.getInteger("pollIndex"));      // identity 落在这里
        assertEquals("进水温度", body.getString("field"));
        assertEquals(T0, body.getLong("from"));
        assertEquals(T0 + 10_000, body.getLong("to"));
        assertEquals(8, body.getInteger("maxPoints"));

        // 形态：没降采样，点就是原始样本
        assertFalse(body.getBoolean("downsampled"));
        assertEquals(2, body.getInteger("total"));

        JsonArray points = body.getJsonArray("points");
        assertEquals(2, points.size());
        // 样本被 ModbusRegistryHistorySampleCodec 编成对象（会带上 at / value 键）
        assertEquals(T0 + 1000, points.getJsonObject(0).getLong("at"));
        assertEquals(76.0, points.getJsonObject(0).getValue("value"));

        // carry-in 有值就带键
        JsonObject carryIn = body.getJsonObject("carryIn");
        assertEquals(T0, carryIn.getLong("at"));
        assertNull(carryIn.getLong("carryIn"));            // 不是个嵌套的空样本
    }

    @Test
    void noCarryInMeansNoCarryInKey() {
        Window window = new Window(
                null,                                    // 没有更早的样本
                List.of(sample(T0, 75.0)),
                List.of(),
                false);
        JsonObject body = ModbusRegistryHistoryRangeCodec.encode(range(window));
        assertNull(body.getJsonObject("carryIn"));
        assertFalse(body.containsKey("carryIn"));        // 关键是「不出键」，而不是发一条 null
    }

    @Test
    void downsampledBucketsBecomePointsWhileTotalCountsSamples() {
        // 降采样窗口：原始样本仍在、点改成桶（与 ModbusRegistryHistoryWindow.of 的实际产物一致）
        Window window = new Window(
                sample(T0, 75.0),
                List.of(sample(T0 + 1000, 76.0), sample(T0 + 2000, 77.0)),
                List.of(bucket(T0 + 1000)),
                true);
        JsonObject body = ModbusRegistryHistoryRangeCodec.encode(range(window));

        assertTrue(body.getBoolean("downsampled"));
        // total 数的是原始样本（曲线点数的口径），不是桶数
        assertEquals(2, body.getInteger("total"));

        JsonArray points = body.getJsonArray("points");
        assertEquals(1, points.size());
        JsonObject point = points.getJsonObject(0);
        assertEquals(T0 + 1000, point.getLong("at"));
        assertEquals(76.0, point.getValue("avg"));       // 桶的折叠量快照进来
        assertNull(point.getValue("value"));             //「桶」形态没有平铺 value 键
    }
}