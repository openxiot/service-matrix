package cc.openxiot.matrix.api.energy;

import cc.openxiot.matrix.db.energy.EnergyStat;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摘要 JSON：总计 code 顺序、没有数据时的显式 null、曲线只带页面用得到的字段。
 */
class EnergySummaryCodecTest {

    @Test
    void encodesStableCodesInFixedOrder() {
        JsonObject json = EnergySummaryCodec.encode(summary());

        JsonArray totals = json.getJsonArray("totals");
        assertEquals(5, totals.size());
        assertEquals(List.of("SOLAR", "BATTERY", "GRID", "GAS", "WATER"),
                totals.getList().stream()
                        .map(row -> ((JsonObject) row).getString("key"))
                        .toList());
        assertEquals("kWh", totals.getJsonObject(0).getString("unit"));
        assertEquals("m³", totals.getJsonObject(3).getString("unit"));
    }

    @Test
    void keepsNullValuesAsExplicitKeys() {
        JsonObject gas = EnergySummaryCodec.encode(summary()).getJsonArray("totals").getJsonObject(3);

        assertTrue(gas.containsKey("value"));
        assertNull(gas.getValue("value"));
        assertTrue(gas.containsKey("previous"));
        assertNull(gas.getValue("previous"));
    }

    @Test
    void encodesNullableChargeAndStorage() {
        JsonObject json = EnergySummaryCodec.encode(summary());

        assertTrue(json.containsKey("charge"));
        assertNull(json.getValue("charge"));
        assertTrue(json.containsKey("storage"));
        assertNull(json.getValue("storage"));
    }

    @Test
    void usageBucketsOnlyCarryPageFields() {
        JsonObject bucket = EnergySummaryCodec.encode(summary())
                .getJsonObject("series").getJsonArray("consumption").getJsonObject(0);

        assertEquals(3, bucket.size());
        assertEquals(1_704_038_400_000L, bucket.getLong("at"));
        assertEquals(2.5, bucket.getDouble("energy"), 1e-9);
        assertEquals(3, bucket.getLong("samples"));
        assertFalse(bucket.containsKey("cost"));
    }

    @Test
    void storageBucketsCarrySocPowerAndCharge() {
        JsonObject bucket = EnergySummaryCodec.encode(summary())
                .getJsonObject("series").getJsonArray("storage").getJsonObject(0);

        assertEquals(6, bucket.size());
        assertEquals(42.0, bucket.getDouble("soc"), 1e-9);
        assertEquals(-2.1, bucket.getDouble("power"), 1e-9);
        assertEquals(0.8, bucket.getDouble("charge"), 1e-9);
        assertEquals(0.2, bucket.getDouble("discharge"), 1e-9);
    }

    private static EnergySummary summary() {
        EnergyStat stat = new EnergyStat();
        stat.bucketAt = 1_704_038_400_000L;
        stat.energy = 2.5;
        stat.charge = 0.8;
        stat.discharge = 0.2;
        stat.powerSum = -2.1;
        stat.powerCount = 1;
        stat.socSum = 42;
        stat.socCount = 1;
        stat.samples = 3;

        return new EnergySummary(
                "space",
                1_704_038_400_000L,
                1_704_045_600_000L,
                "Asia/Shanghai",
                EnergyGranularity.HOUR,
                List.of(
                        new EnergySummary.Total(EnergySummary.Key.SOLAR, 123.4, "kWh", 118.2),
                        new EnergySummary.Total(EnergySummary.Key.BATTERY, 88.6, "kWh", 80.1),
                        new EnergySummary.Total(EnergySummary.Key.GRID, 45.2, "kWh", 52.7),
                        new EnergySummary.Total(EnergySummary.Key.GAS, null, "m³", null),
                        new EnergySummary.Total(EnergySummary.Key.WATER, 8.4, "m³", 8.0)),
                new EnergySummary.Series(
                        List.of(EnergyBucket.empty(stat.bucketAt).plus(stat)),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(EnergyBucket.empty(stat.bucketAt).plus(stat))),
                null,
                null,
                null);
    }
}
