package cc.openxiot.matrix.api.dashboard.widget;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * 取数结果 → 线上 JSON：{@code {spaceId, from, to, widgets: [{id, success, data|message}]}}。
 *
 * <p><b>逐卡片的 {@code data} 是各卡片自己拼好的 JSON，本类原样搬运</b>：五种卡片的 data 形状
 * 各不相同（统计是数字、分布是分组数组、曲线是点数组），且都是**只写不读**的一次性形状。
 * 为它们各建一个 record 只会得到五个「建完立刻被编码」的空壳；而卡片实体的 {@code config}
 * 早就是同一种性质的异构结构（见 {@code WebDashboardWidget} / {@code MobileDashboardWidget}
 * 的说明），这里沿用同一取舍。</p>
 *
 * <p>{@code from} / {@code to} 是各卡片窗口的**并集**，仅供参考：一张卡片该用哪个窗口，
 * 以它自己 {@code data} 里的字段为准（{@code line} 的 data 自带 {@code from}/{@code to}，
 * {@code stat} 的 {@code alarms.today} 自带 {@code to}）。</p>
 */
public final class DashboardWidgetDataCodec {

    private DashboardWidgetDataCodec() {
    }

    public static JsonObject encode(DashboardWidgetDataService.WidgetData data) {
        JsonObject o = new JsonObject();
        if (data == null) {
            return o;
        }
        if (data.spaceId() != null) {
            o.put("spaceId", data.spaceId());
        }
        if (data.from() != null) {
            o.put("from", data.from().getTime());
        }
        if (data.to() != null) {
            o.put("to", data.to().getTime());
        }
        JsonArray widgets = new JsonArray();
        for (DashboardWidgetDataService.Item item : data.widgets()) {
            widgets.add(encodeItem(item));
        }
        o.put("widgets", widgets);
        return o;
    }

    /** 成功的卡片带 {@code data}，失败的带 {@code message} —— 两者互斥，不补空值。 */
    private static JsonObject encodeItem(DashboardWidgetDataService.Item item) {
        JsonObject o = new JsonObject()
                .put("id", item.id())
                .put("success", item.success());
        if (item.data() != null) {
            o.put("data", item.data());
        }
        if (item.message() != null) {
            o.put("message", item.message());
        }
        return o;
    }
}
