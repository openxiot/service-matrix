package cc.openxiot.matrix.api.modbus.history;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * 区间曲线（{@code /history/range}）的响应体编码：
 * {@code {fdid, pollIndex, field, from, to, maxPoints, downsampled, total, points, carryIn?}}。
 *
 * <p><b>为什么单独一个类</b>：看板的字段曲线（{@code DashboardWidgetDataService}）下发的是**同一份形状**，
 * 只是另外缀上 {@code unit} / {@code step} / {@code failures}。前端两条路共用同一个
 * {@code ModbusRegistryHistoryRangeCodec.decodeRange}，后端要是各写一份编码，一边改了键名、或漏了
 * 「没有 carry-in 时不出键」那条规则，另一边的表现是**静默地少一段曲线** —— 图还是画得出来，
 * 只是窗口开头缺一截，看着像那段时间没采到。</p>
 *
 * <p>与 {@link ModbusRegistryHistorySampleCodec} 的分工：那个把样本 / 桶编成 JSON，
 * 这个把**一次区间查询的整体结果**编成响应体 —— 键名、点集形态的分流、缺省全在这一层。</p>
 *
 * <p>入参是一个 {@link ModbusRegistryHistoryRange}（在哪条曲线 + 窗口长什么样），不是一串位置参数：
 * request 的曲线身份与窗口结果虽然是两个来源（见 {@code ModbusRegistryHistoryRange}），
 * 但合并成一个类型后，调用方不用再盯着七个位置参数、也不会把 {@code from}/{@code to} 或
 * {@code fdid}/{@code identity} 对调。</p>
 */
public final class ModbusRegistryHistoryRangeCodec {

    private ModbusRegistryHistoryRangeCodec() {
    }

    /**
     * 编码一次区间查询结果。
     *
     * <p>看板的字段曲线（{@code DashboardWidgetDataService}）也走这里（数据源换成逐寄存器历史
     * {@code ModbusRegistryHistory}，但响应是同一份形状、同一套 {@code points} / {@code carryIn} /
     * {@code downsampled} 分流）—— 两条消费方共用同一个 {@code ModbusRegistryHistoryRangeCodec.decodeRange}，
     * 后端要是各写一份编码，一边改了键名、或漏了「没有 carry-in 时不出键」那条规则，
     * 另一边的表现是**静默地少一段曲线**。</p>
     *
     * <p>{@code identity}（历史页是 pollIndex、看板是 registerIndex）只是请求那份标识的回显，
     * 落在响应的 {@code pollIndex} 键上，前端画图只用 {@code points} / {@code carryIn}，不按它取数据。</p>
     */
    public static JsonObject encode(ModbusRegistryHistoryRange range) {
        ModbusRegistryHistoryWindow.Window window = range.window();
        JsonArray points = new JsonArray();
        if (window.downsampled()) {
            for (ModbusRegistryHistoryWindow.Bucket bucket : window.buckets()) {
                points.add(ModbusRegistryHistorySampleCodec.encode(bucket));
            }
        } else {
            for (ModbusRegistryHistorySample sample : window.samples()) {
                points.add(ModbusRegistryHistorySampleCodec.encode(sample));
            }
        }

        JsonObject body = new JsonObject()
                .put("fdid", range.fdid())
                .put("pollIndex", range.identity())
                .put("field", range.field())
                .put("from", range.from().getTime())
                .put("to", range.to().getTime())
                .put("maxPoints", range.maxPoints())
                .put("downsampled", window.downsampled())
                .put("total", window.samples().size())
                .put("points", points);
        // 没有更早的样本时不出键（与 Codec 里「null 不出键」同口径）：它不是一条空样本
        if (window.carryIn() != null) {
            body.put("carryIn", ModbusRegistryHistorySampleCodec.encode(window.carryIn()));
        }
        return body;
    }
}