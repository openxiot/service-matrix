package cc.openxiot.matrix.api.statistics;

import cc.openxiot.matrix.api.statistics.StatisticsQueryService.AlarmStats;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.Bucket;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.Count;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.DeviceStats;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.FailureStats;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.Overview;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.ServiceStats;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * 看板数字的 JSON 组装：{@link Overview} → 前端的类型定义（键名一一对应）。
 *
 * <p>与别的 Codec 分开放在资源旁边（{@code ModbusAlarmCodec} 在 db 包里）：这个形状**只属于本接口**，
 * 没有第二个消费者，也就没有「两处共用一份编码」的理由。时间一律下发 **epoch 毫秒**
 * （{@code from} / {@code to} / 桶的 {@code at}），与告警、历史两个接口同口径，省掉两端各写一遍格式解析。</p>
 *
 * <p>{@code byType} 的 {@code type} 是 URN 第 4 段、{@code byConfig} 的 {@code configId} 是点表 id、
 * {@code byText} 的 {@code text} 是用户填的告警文本 —— 三者都是**数据**，原样下发、**永不翻译**。</p>
 */
public final class StatisticsCodec {

    private StatisticsCodec() {
    }

    /** 整个看板的响应体：{@code {from, to, devices, services, alarms, failures}}。 */
    public static JsonObject encode(Overview overview) {
        JsonObject body = new JsonObject()
                .put("from", overview.from().getTime())
                .put("to", overview.to().getTime())
                .put("devices", encodeDevices(overview.devices()))
                .put("services", encodeServices(overview.services()))
                .put("alarms", encodeAlarms(overview.alarms()))
                .put("failures", encodeFailures(overview.failures()));
        return body;
    }

    /** 设备：{@code {total, online, byType: [{type, count}]}}。 */
    private static JsonObject encodeDevices(DeviceStats devices) {
        return new JsonObject()
                .put("total", devices.total())
                .put("online", devices.online())
                .put("byType", counts(devices.byType(), "type"));
    }

    /** 服务：{@code {total, byConfig: [{configId, count}]}}。 */
    private static JsonObject encodeServices(ServiceStats services) {
        return new JsonObject()
                .put("total", services.total())
                .put("byConfig", counts(services.byConfig(), "configId"));
    }

    /** 告警：{@code {total, byText: [{text, count}], hourly: [{at, count}]}}。 */
    private static JsonObject encodeAlarms(AlarmStats alarms) {
        return new JsonObject()
                .put("total", alarms.total())
                .put("byText", counts(alarms.byText(), "text"))
                .put("hourly", buckets(alarms.hourly()));
    }

    /** 故障：{@code {total, hourly: [{at, count}]}}。 */
    private static JsonObject encodeFailures(FailureStats failures) {
        return new JsonObject()
                .put("total", failures.total())
                .put("hourly", buckets(failures.hourly()));
    }

    /** 分组数组：{@code keyName} 是那个键叫什么（类型 / 点表 id / 告警文本各不同）。 */
    private static JsonArray counts(List<Count> counts, String keyName) {
        JsonArray arr = new JsonArray();
        for (Count count : counts) {
            arr.add(new JsonObject().put(keyName, count.key()).put("count", count.count()));
        }
        return arr;
    }

    /** 整点桶数组：{@code at} 是桶起点的 epoch 毫秒。 */
    private static JsonArray buckets(List<Bucket> buckets) {
        JsonArray arr = new JsonArray();
        for (Bucket bucket : buckets) {
            arr.add(new JsonObject().put("at", bucket.at()).put("count", bucket.count()));
        }
        return arr;
    }
}
