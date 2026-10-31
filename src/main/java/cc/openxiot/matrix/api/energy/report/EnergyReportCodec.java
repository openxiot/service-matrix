package cc.openxiot.matrix.api.energy.report;

import cc.openxiot.matrix.api.energy.EnergyBucket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * 能源报表的 JSON 组装（键名与前端 {@code EnergyReport} 类型一一对应）。
 *
 * <p>瞬时量下发平均值（{@code power} / {@code soc} / {@code soh} / {@code temperature}），
 * 累计量下发合计（{@code energy} / {@code charge} / {@code discharge} / {@code cost} / {@code revenue}）。
 * {@code capacity} 与 {@code cycles} 是「窗口内最后一个值」，不是合计 —— 容量不该因为看了一个月就乘以小时数。
 * 没有数据的指标给 {@code null} 而不是 0：前端显示「—」，与 0 区分。</p>
 */
public final class EnergyReportCodec {

    private EnergyReportCodec() {
    }

    public static JsonObject encode(EnergyReport report) {
        return new JsonObject()
                .put("spaceId", report.spaceId())
                .put("role", report.role())
                .put("type", report.type())
                .put("from", report.from())
                .put("to", report.to())
                .put("granularity", report.granularity().name())
                .put("buckets", encodeBuckets(report.buckets()))
                .put("totals", encodeTotals(report.totals()))
                .put("previous", encodeTotals(report.previous()))
                .put("lastYear", encodeTotals(report.lastYear()))
                .put("configs", encodeConfigs(report.configs()));
    }

    private static JsonArray encodeBuckets(List<EnergyBucket> buckets) {
        JsonArray array = new JsonArray();
        if (buckets != null) {
            for (EnergyBucket bucket : buckets) {
                array.add(values(new JsonObject().put("at", bucket.at()), bucket));
            }
        }
        return array;
    }

    private static JsonObject encodeTotals(EnergyBucket bucket) {
        return values(new JsonObject(), bucket == null ? EnergyBucket.empty(0) : bucket);
    }

    private static JsonObject values(JsonObject body, EnergyBucket bucket) {
        return body
                .put("energy", bucket.energy())
                .put("charge", bucket.charge())
                .put("discharge", bucket.discharge())
                .put("net", bucket.netStorage())
                .put("cost", bucket.cost())
                .put("revenue", bucket.revenue())
                .put("power", bucket.powerAvg())
                .put("powerMax", bucket.powerMax())
                .put("chargePowerMax", bucket.chargePowerMax())
                .put("dischargePowerMax", bucket.dischargePowerMax())
                .put("soc", bucket.socAvg())
                .put("socMin", bucket.socMin())
                .put("socMax", bucket.socMax())
                .put("capacity", bucket.capacity())
                .put("soh", bucket.sohAvg())
                .put("temperature", bucket.temperatureAvg())
                .put("cycles", bucket.cycles())
                .put("samples", bucket.samples());
    }

    private static JsonArray encodeConfigs(List<EnergyReport.ConfigSummary> configs) {
        JsonArray array = new JsonArray();
        if (configs == null) {
            return array;
        }
        for (EnergyReport.ConfigSummary config : configs) {
            array.add(new JsonObject()
                    .put("id", config.id())
                    .put("name", config.name())
                    .put("role", config.role())
                    .put("type", config.type())
                    .put("price", config.price())
                    .put("chargePrice", config.chargePrice())
                    .put("dischargePrice", config.dischargePrice())
                    .put("periodHours", config.periodHours())
                    .put("enabled", config.enabled())
                    .put("points", config.points()));
        }
        return array;
    }
}
