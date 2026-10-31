package cc.openxiot.matrix.api.energy.summary;

import cc.openxiot.matrix.api.energy.EnergyBucket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * 能源摘要的 JSON 组装（键名与前端 {@code EnergySummary} 类型一一对应）。
 *
 * <p>总计下发稳定 code，不下发界面词条；{@code previous} 是原始值，变化率由前端算。
 * 没有数据的字段**显式写 null**（而不是省略键）：页面上「—」与「确实是 0」必须分得开，
 * 客户端也不必区分「字段缺失」与「值为空」。</p>
 *
 * <p>曲线只带页面用得到的字段：用量组 {@code at / energy / samples}，存储组另带
 * {@code soc / power / charge / discharge}。合计里的成本、极值等摘要页不展示，不重复下发。</p>
 */
public final class EnergySummaryCodec {

    private EnergySummaryCodec() {
    }

    public static JsonObject encode(EnergySummary summary) {
        return new JsonObject()
                .put("spaceId", summary.spaceId())
                .put("from", summary.from())
                .put("to", summary.to())
                .put("zone", summary.zone())
                .put("granularity", summary.granularity().name())
                .put("totals", encodeTotals(summary.totals()))
                .put("series", encodeSeries(summary.series()))
                .put("charge", summary.charge())
                .put("discharge", summary.discharge())
                .put("storage", summary.storage() == null ? null : encodeStorage(summary.storage()));
    }

    private static JsonArray encodeTotals(List<EnergySummary.Total> totals) {
        JsonArray array = new JsonArray();
        if (totals == null) {
            return array;
        }
        for (EnergySummary.Total total : totals) {
            array.add(new JsonObject()
                    .put("key", total.key().name())
                    .put("unit", total.unit())
                    .put("value", total.value())
                    .put("previous", total.previous()));
        }
        return array;
    }

    private static JsonObject encodeSeries(EnergySummary.Series series) {
        if (series == null) {
            return new JsonObject();
        }
        return new JsonObject()
                .put("consumption", encodeUsage(series.consumption()))
                .put("production", encodeUsage(series.production()))
                .put("gas", encodeUsage(series.gas()))
                .put("water", encodeUsage(series.water()))
                .put("storage", encodeStorageSeries(series.storage()));
    }

    private static JsonArray encodeUsage(List<EnergyBucket> buckets) {
        JsonArray array = new JsonArray();
        if (buckets == null) {
            return array;
        }
        for (EnergyBucket bucket : buckets) {
            array.add(new JsonObject()
                    .put("at", bucket.at())
                    .put("energy", bucket.energy())
                    .put("samples", bucket.samples()));
        }
        return array;
    }

    private static JsonArray encodeStorageSeries(List<EnergyBucket> buckets) {
        JsonArray array = new JsonArray();
        if (buckets == null) {
            return array;
        }
        for (EnergyBucket bucket : buckets) {
            array.add(new JsonObject()
                    .put("at", bucket.at())
                    .put("soc", bucket.socAvg())
                    .put("power", bucket.powerAvg())
                    .put("charge", bucket.charge())
                    .put("discharge", bucket.discharge())
                    .put("samples", bucket.samples()));
        }
        return array;
    }

    private static JsonObject encodeStorage(EnergySummary.Storage storage) {
        return new JsonObject()
                .put("soc", storage.soc())
                .put("available", storage.available())
                .put("capacity", storage.capacity())
                .put("power", storage.power());
    }
}
