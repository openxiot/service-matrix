package cc.openxiot.matrix.api.modbus.history;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Date;

/**
 * 区间曲线（{@code /history/range}）的响应体编码：
 * {@code {serviceId, pollingIndex, field, from, to, maxPoints, downsampled, total, points, carryIn?}}。
 *
 * <p><b>为什么单独一个类</b>：看板的字段曲线（{@code DashboardWidgetDataService}）下发的是**同一份形状**，
 * 只是另外缀上 {@code unit} / {@code step} / {@code failures}。前端两条路共用同一个
 * {@code ModbusHistoryCodec.decodeRange}，后端要是各写一份编码，一边改了键名、或漏了
 * 「没有 carry-in 时不出键」那条规则，另一边的表现是**静默地少一段曲线** —— 图还是画得出来，
 * 只是窗口开头缺一截，看着像那段时间没采到。</p>
 *
 * <p>与 {@link ModbusHistorySampleCodec} 的分工：那个把样本 / 桶编成 JSON，
 * 这个把**一次区间查询的整体结果**编成响应体 —— 键名、点集形态的分流、缺省全在这一层。</p>
 */
public final class ModbusHistoryRangeCodec {

    private ModbusHistoryRangeCodec() {
    }

    /**
     * 编码一个点位的区间查询结果（历史页那条路，包着 {@code RangeResult} 调用下面的底层方法）。
     *
     * <p>{@code serviceId} / {@code pollingIndex} / {@code field} 由调用方传进来，而不是从
     * {@link ModbusHistoryQueryService.RangeResult} 里取：那个记录只有「这两个时刻之间、要多少个点」，
     * 是哪条曲线由调用方（HTTP 的查询参数，或看板那张卡的配置）决定 —— 而且请求里那份是**字符串**，
     * 回显它比回显解析后的值更忠实。</p>
     */
    public static JsonObject encode(
            String serviceId,
            Integer pollingIndex,
            String field,
            ModbusHistoryQueryService.RangeResult result
    ) {
        return encode(serviceId, pollingIndex, field,
                result.from(), result.to(), result.maxPoints(), result.window());
    }

    /**
     * 编码一次区间查询结果。看板的字段曲线（{@code DashboardWidgetDataService}）也走这里
     * （数据源换成逐寄存器历史 {@code ModbusServiceRegistryHistory}，但响应是同一份形状、同一套
     * {@code points} / {@code carryIn} / {@code downsampled} 分流）—— 两条消费方共用同一个
     * {@code ModbusHistoryCodec.decodeRange}，后端要是各写一份编码，一边改了键名、或漏了
     * 「没有 carry-in 时不出键」那条规则，另一边的表现是**静默地少一段曲线**。</p>
     *
     * <p>{@code identity} 在历史页那条路是方法序号（{@code pollingIndex}），在看板那条路是寄存器序号
     * （{@code registerIndex}）—— 二者都只是请求里那份标识的回显，前端画图只用
     * {@code points} / {@code carryIn}，不按它取数据。</p>
     */
    public static JsonObject encode(
            String serviceId,
            Integer identity,
            String field,
            Date from,
            Date to,
            int maxPoints,
            ModbusHistoryWindow.Window window
    ) {
        JsonArray points = new JsonArray();
        if (window.downsampled()) {
            for (ModbusHistoryWindow.Bucket bucket : window.buckets()) {
                points.add(ModbusHistorySampleCodec.encode(bucket));
            }
        } else {
            for (ModbusHistorySample sample : window.samples()) {
                points.add(ModbusHistorySampleCodec.encode(sample));
            }
        }

        JsonObject body = new JsonObject()
                .put("serviceId", serviceId)
                .put("pollingIndex", identity)
                .put("field", field)
                .put("from", from.getTime())
                .put("to", to.getTime())
                .put("maxPoints", maxPoints)
                .put("downsampled", window.downsampled())
                .put("total", window.samples().size())
                .put("points", points);
        // 没有更早的样本时不出键（与 Codec 里「null 不出键」同口径）：它不是一条空样本
        if (window.carryIn() != null) {
            body.put("carryIn", ModbusHistorySampleCodec.encode(window.carryIn()));
        }
        return body;
    }
}
