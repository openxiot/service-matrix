package cc.openxiot.matrix.api.energy;

import cc.openxiot.matrix.db.energy.EnergyConfig;
import cc.openxiot.matrix.db.energy.EnergyMetric;
import cc.openxiot.matrix.db.energy.EnergyPoint;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergySourceKind;
import cc.openxiot.matrix.db.energy.EnergyType;
import cc.openxiot.matrix.db.energy.EnergyValueMode;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnergyConfigCodec} / {@link EnergyReportCodec}：请求体到实体、实体到前端 JSON 的形状。
 */
class EnergyConfigCodecTest {

    @Test
    void decodeDefaultsEnabledAndCopiesPoints() {
        EnergyConfigCodec.Request request = new EnergyConfigCodec.Request();
        request.name = "一号总表";
        request.role = EnergyRole.CONSUMPTION;
        request.type = EnergyType.ELECTRICITY;
        request.periodHours = 24;
        request.points = List.of(point());

        EnergyConfig config = EnergyConfigCodec.decode(request);

        assertTrue(config.enabled);
        assertEquals("一号总表", config.name);
        assertEquals(1, config.points.size());
    }

    @Test
    void decodeHonoursExplicitDisable() {
        EnergyConfigCodec.Request request = new EnergyConfigCodec.Request();
        request.enabled = false;
        request.points = List.of(point());
        assertFalse(EnergyConfigCodec.decode(request).enabled);
    }

    @Test
    void encodeExposesIdAndPointFields() {
        EnergyConfig config = new EnergyConfig();
        config.id = new ObjectId();
        config.spaceId = "space-1";
        config.name = "一号总表";
        config.role = EnergyRole.CONSUMPTION;
        config.type = EnergyType.ELECTRICITY;
        config.periodHours = 24;
        config.points = List.of(point());

        JsonObject body = EnergyConfigCodec.encode(config);

        assertEquals(config.id.toHexString(), body.getString("id"));
        assertEquals("CONSUMPTION", body.getString("role"));
        assertEquals("ELECTRICITY", body.getString("type"));
        JsonObject point = body.getJsonArray("points").getJsonObject(0);
        assertEquals("SERVICE", point.getString("source"));
        assertEquals("ENERGY", point.getString("metric"));
        assertEquals("ACTUAL", point.getString("mode"));
        assertEquals("kWh", point.getString("unit"));
    }

    @Test
    void reportEncoderUsesNullForMissingStateValues() {
        EnergyReport report = new EnergyReport(
                "space-1", "STORAGE", "ELECTRICITY", 0, 3_600_000,
                EnergyGranularity.HOUR,
                List.of(EnergyBucket.empty(0)),
                EnergyBucket.empty(0),
                EnergyBucket.empty(0),
                EnergyBucket.empty(0),
                List.of(new EnergyReport.ConfigSummary("id-1", "电池", "STORAGE", "ELECTRICITY",
                        null, 0.5, 1.0, 24, true, 3)));

        JsonObject body = EnergyReportCodec.encode(report);

        assertEquals("HOUR", body.getString("granularity"));
        assertEquals(1, body.getJsonArray("buckets").size());
        JsonObject totals = body.getJsonObject("totals");
        assertTrue(totals.containsKey("soc"));
        assertNull(totals.getValue("soc"));
        assertEquals(1, body.getJsonArray("configs").size());
    }

    private static EnergyPoint point() {
        EnergyPoint point = new EnergyPoint();
        point.source = EnergySourceKind.SERVICE;
        point.did = "did-1";
        point.siid = 1;
        point.piid = 2;
        point.metric = EnergyMetric.ENERGY;
        point.mode = EnergyValueMode.ACTUAL;
        point.name = "正向有功电能";
        point.unit = "kWh";
        return point;
    }
}
