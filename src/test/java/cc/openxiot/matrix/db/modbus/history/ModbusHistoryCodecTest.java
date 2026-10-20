package cc.openxiot.matrix.db.modbus.history;

import cc.openxiot.matrix.api.modbus.history.ModbusHistoryWindow.Bucket;
import cc.openxiot.matrix.db.modbus.history.codec.ModbusServiceFailureCodec;
import cc.openxiot.matrix.db.modbus.history.codec.ModbusServiceHistoryCodec;
import cc.openxiot.matrix.db.modbus.history.codec.ModbusServiceShadowCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读侧三个 Codec 的对外 JSON（Modbus-History.md 第五节）：字段名、null 的处理、故障聚合的顺序。
 *
 * <p>这些是对前端的契约，改名字就是改接口 —— 所以哪怕每个 Codec 都只有几行，也要钉住。
 * 纯单测，不需要数据库。</p>
 */
class ModbusHistoryCodecTest {

    private static final long T0 = 1_700_000_000_000L;

    // ===== 故障聚合 =====

    @Test
    void summaryAggregatesByTypeAndRemoteCode() {
        JsonObject summary = ModbusServiceFailureCodec.encodeSummary(List.of(
                failure("DEVICE_ERROR", 408, T0),
                failure("DEVICE_ERROR", 408, T0 + 1000),
                failure("DEVICE_ERROR", 500, T0 + 2000),
                failure("CRC_MISMATCH", null, T0 + 3000),
                failure("NO_RESPONSE", null, T0 + 4000)));

        assertEquals(5, summary.getInteger("total"));

        // 按分类：条数降序，条数相同按名字升序 —— 顺序定死，前端不必再排
        JsonArray byType = summary.getJsonArray("byType");
        assertEquals(3, byType.size());
        assertEquals("DEVICE_ERROR", byType.getJsonObject(0).getString("type"));
        assertEquals(3, byType.getJsonObject(0).getInteger("count"));
        // lastAt 是这一类**最近**一次，不是列表里的第一条（列表是倒序的，这里不跟着倒）
        assertEquals(T0 + 2000, byType.getJsonObject(0).getLong("lastAt"));
        assertEquals("CRC_MISMATCH", byType.getJsonObject(1).getString("type"));
        assertEquals("NO_RESPONSE", byType.getJsonObject(2).getString("type"));

        // 下钻用：只有带远端码的分类出现在这里（CRC_MISMATCH 这类设备没回话的，没有码）
        JsonArray byRemoteCode = summary.getJsonArray("byRemoteCode");
        assertEquals(2, byRemoteCode.size());
        assertEquals(408, byRemoteCode.getJsonObject(0).getInteger("remoteCode"));
        assertEquals(2, byRemoteCode.getJsonObject(0).getInteger("count"));
        assertEquals(T0 + 1000, byRemoteCode.getJsonObject(0).getLong("lastAt"));
        assertEquals(500, byRemoteCode.getJsonObject(1).getInteger("remoteCode"));
    }

    @Test
    void summaryOfEmptyRangeIsZero() {
        // 这段时间没故障是常态，前端要能直接读 total == 0，而不是判两个数组是不是空
        JsonObject summary = ModbusServiceFailureCodec.encodeSummary(List.of());

        assertEquals(0, summary.getInteger("total"));
        assertTrue(summary.getJsonArray("byType").isEmpty());
        assertTrue(summary.getJsonArray("byRemoteCode").isEmpty());
    }

    @Test
    void untypedFailureCountsAsUnknown() {
        // 分类字段是后来才加的：老文档没有 type，聚合时不能把它算丢（它是平台上真实发生过的失败）
        JsonObject summary = ModbusServiceFailureCodec.encodeSummary(
                List.of(failure(null, null, T0), failure("NO_RESPONSE", null, T0 + 1000)));

        assertEquals(2, summary.getInteger("total"));
        assertEquals("UNKNOWN", summary.getJsonArray("byType").getJsonObject(1).getString("type"));
    }

    @Test
    void failureOmitsKeysThatHaveNoValue() {
        // 没有远端码、没有分类时不出现空键（与 ModbusServiceCodec 同口径）：
        // 前端判「有没有码」看键在不在
        JsonObject json = ModbusServiceFailureCodec.encode(failure(null, null, T0));

        assertFalse(json.containsKey("type"));
        assertFalse(json.containsKey("remoteCode"));
        assertEquals("synthetic", json.getString("message"));
        assertEquals(T0, json.getLong("at"));
    }

    // ===== 点 =====

    @Test
    void bucketAlwaysCarriesEveryKeyEvenWhenStatisticsAreNull() {
        // 非数值点位折叠后没有极值可言，但**键必须在**（值为 null）：
        // 前端画图时按键直接读，不该为「这一桶恰好没有数值」写分支
        JsonObject json = ModbusServiceHistoryCodec.encode(
                new Bucket(T0, T0 + 1000, 3, "ON", "OFF", null, null, null));

        assertEquals(T0, json.getLong("at"));
        assertEquals(T0 + 1000, json.getLong("until"));
        assertEquals(3, json.getInteger("count"));
        assertEquals("ON", json.getString("first"));
        assertEquals("OFF", json.getString("last"));
        assertTrue(json.containsKey("min"));
        assertTrue(json.containsKey("max"));
        assertTrue(json.containsKey("avg"));
        assertNull(json.getDouble("min"));
        assertNull(json.getDouble("avg"));
    }

    @Test
    void normalSampleOmitsKeepalive() {
        // 缺省即 false：保活打点是少数，不该让每个点都带一个恒为 false 的键
        JsonObject normal = ModbusServiceHistoryCodec.encode(sample(T0, 61.5, null));
        assertFalse(normal.containsKey("keepalive"));

        JsonObject keepalive = ModbusServiceHistoryCodec.encode(sample(T0, 61.5, true));
        assertTrue(keepalive.getBoolean("keepalive"));
    }

    // ===== 当前值 =====

    @Test
    void shadowKeepsStaleValuesAndOmitsAbsentError() {
        // 有故障时 fields / recordedAt 照旧在（那是最后一次成功的值与时刻），
        // 前端靠 errorAt 判断「这个值从哪一刻起不再更新」
        ModbusServiceShadow shadow = new ModbusServiceShadow();
        shadow.pollingIndex = 1;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("进水温度", 61.5);
        shadow.fields = fields;
        shadow.recordedAt = new Date(T0);
        shadow.lastError = "no response";
        shadow.errorAt = new Date(T0 + 60_000);

        JsonObject failed = ModbusServiceShadowCodec.encode(shadow);
        assertEquals(61.5, failed.getJsonObject("fields").getDouble("进水温度"));
        assertEquals(T0, failed.getLong("recordedAt"));
        assertEquals("no response", failed.getString("lastError"));
        assertEquals(T0 + 60_000, failed.getLong("errorAt"));

        shadow.lastError = null;
        shadow.errorAt = null;
        JsonObject healthy = ModbusServiceShadowCodec.encode(shadow);
        assertFalse(healthy.containsKey("lastError"));
        assertFalse(healthy.containsKey("errorAt"));
    }

    // ===== 夹具 =====

    private static ModbusServiceFailure failure(String type, Integer remoteCode, long at) {
        ModbusServiceFailure row = new ModbusServiceFailure();
        row.type = type;
        row.remoteCode = remoteCode;
        row.message = "synthetic";
        row.at = new Date(at);
        return row;
    }

    private static ModbusServiceHistory sample(long at, Object value, Boolean keepalive) {
        ModbusServiceHistory row = new ModbusServiceHistory();
        row.at = new Date(at);
        row.value = value;
        row.keepalive = keepalive;
        return row;
    }
}
