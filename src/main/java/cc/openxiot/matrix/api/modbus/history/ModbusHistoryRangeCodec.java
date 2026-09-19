package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistory;
import cc.openxiot.matrix.db.modbus.history.codec.ModbusServiceHistoryCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * 区间曲线（{@code /history/range}）的响应体编码：
 * {@code {serviceId, functionIndex, field, from, to, maxPoints, downsampled, total, points, carryIn?}}。
 *
 * <p><b>为什么单独一个类</b>：看板的字段曲线（{@code DashboardWidgetDataService}）下发的是**同一份形状**，
 * 只是另外缀上 {@code unit} / {@code step} / {@code failures}。前端两条路共用同一个
 * {@code ModbusHistoryCodec.decodeRange}，后端要是各写一份编码，一边改了键名、或漏了
 * 「没有 carry-in 时不出键」那条规则，另一边的表现是**静默地少一段曲线** —— 图还是画得出来，
 * 只是窗口开头缺一截，看着像那段时间没采到。</p>
 *
 * <p>与 {@link ModbusServiceHistoryCodec} 的分工：那个把库实体（样本 / 桶）编成 JSON，
 * 这个把**一次区间查询的整体结果**编成响应体 —— 键名、点集形态的分流、缺省全在这一层。</p>
 */
public final class ModbusHistoryRangeCodec {

    private ModbusHistoryRangeCodec() {
    }

    /**
     * 编码一个点位的区间查询结果。
     *
     * <p>{@code serviceId} / {@code functionIndex} / {@code field} 由调用方传进来，而不是从
     * {@link ModbusHistoryQueryService.RangeResult} 里取：那个记录只有「这两个时刻之间、要多少个点」，
     * 是哪条曲线由调用方（HTTP 的查询参数，或看板那张卡的配置）决定 —— 而且请求里那份是**字符串**，
     * 回显它比回显解析后的值更忠实。</p>
     */
    public static JsonObject encode(String serviceId, Integer functionIndex, String field,
                                    ModbusHistoryQueryService.RangeResult result) {
        ModbusHistoryWindow.Window window = result.window();
        JsonArray points = new JsonArray();
        if (window.downsampled()) {
            for (ModbusHistoryWindow.Bucket bucket : window.buckets()) {
                points.add(ModbusServiceHistoryCodec.encode(bucket));
            }
        } else {
            for (ModbusServiceHistory sample : window.samples()) {
                points.add(ModbusServiceHistoryCodec.encode(sample));
            }
        }

        JsonObject body = new JsonObject()
                .put("serviceId", serviceId)
                .put("functionIndex", functionIndex)
                .put("field", field)
                .put("from", result.from().getTime())
                .put("to", result.to().getTime())
                .put("maxPoints", result.maxPoints())
                .put("downsampled", window.downsampled())
                .put("total", window.samples().size())
                .put("points", points);
        // 没有更早的样本时不出键（与 Codec 里「null 不出键」同口径）：它不是一条空样本
        if (window.carryIn() != null) {
            body.put("carryIn", ModbusServiceHistoryCodec.encode(window.carryIn()));
        }
        return body;
    }
}
