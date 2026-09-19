package cc.openxiot.matrix.api.dashboard.web.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * {@link WebDashboardWidgetDataCodec} 的线上形状。**纯 JUnit，不连库**。
 *
 * <p>重点在「什么都不补」这条约定：{@code from}/{@code to} 没给就是没给（空看板没有窗口可言），
 * 一律<b>不出现在 JSON 里</b>。补成 0 会让「没有窗口」和「窗口从 1970 年开始」再也分不开。</p>
 *
 * <p>布局那一份 codec 在
 * {@code cc.openxiot.matrix.api.dashboard.web.layout.DashboardLayoutCodecTest}。</p>
 */
class WidgetDataCodecTest {

    @Test
    void encodeData_successCarriesDataOnly() {
        JsonObject payload = new JsonObject().put("value", 12);
        WebDashboardWidgetDataService.WidgetData data = new WebDashboardWidgetDataService.WidgetData(
                "space-1", new Date(1_000L), new Date(2_000L),
                List.of(ok("w1", payload)));

        JsonObject o = WebDashboardWidgetDataCodec.encode(data);
        assertEquals("space-1", o.getString("spaceId"));
        assertEquals(1_000L, o.getLong("from").longValue());
        assertEquals(2_000L, o.getLong("to").longValue());
        JsonObject item = o.getJsonArray("widgets").getJsonObject(0);
        assertEquals("w1", item.getString("id"));
        assertTrue(item.getBoolean("success"));
        assertEquals(12, item.getJsonObject("data").getInteger("value"));
        // 两者互斥：成功的卡片没有 message，失败的没有 data
        assertFalse(item.containsKey("message"));
    }

    @Test
    void encodeData_failureCarriesMessageOnly() {
        WebDashboardWidgetDataService.WidgetData data = new WebDashboardWidgetDataService.WidgetData(
                "space-1", null, null, List.of(failed("w2", "widget type is not supported yet: device")));

        JsonObject o = WebDashboardWidgetDataCodec.encode(data);
        JsonObject item = o.getJsonArray("widgets").getJsonObject(0);
        assertFalse(item.getBoolean("success"));
        assertFalse(item.containsKey("data"));
        assertEquals("widget type is not supported yet: device", item.getString("message"));
    }

    /**
     * 每张卡片的结果独立、顺序与请求一致：前端按 {@code id} 对应卡片，按下标也行 ——
     * 但顺序一致才是「一张卡失败只黑一张」能成立的前提（否则错了位，黑的就换了一张卡）。
     */
    @Test
    void encodeData_preservesOrderAndMixedOutcomes() {
        WebDashboardWidgetDataService.WidgetData data = new WebDashboardWidgetDataService.WidgetData(
                "space-1", new Date(1L), new Date(2L),
                List.of(ok("a", new JsonObject().put("value", 1)),
                        failed("b", "boom"),
                        ok("c", new JsonObject().put("value", 3))));

        JsonArray widgets = WebDashboardWidgetDataCodec.encode(data).getJsonArray("widgets");
        assertEquals(3, widgets.size());
        assertEquals("a", widgets.getJsonObject(0).getString("id"));
        assertEquals("b", widgets.getJsonObject(1).getString("id"));
        assertEquals("c", widgets.getJsonObject(2).getString("id"));
        assertFalse(widgets.getJsonObject(1).getBoolean("success"));
        assertTrue(widgets.getJsonObject(2).getBoolean("success"));
    }

    @Test
    void encodeData_emptyWidgetsStillHasArray() {
        WebDashboardWidgetDataService.WidgetData data = new WebDashboardWidgetDataService.WidgetData("space-1", null, null, List.of());
        JsonObject o = WebDashboardWidgetDataCodec.encode(data);
        assertTrue(o.containsKey("widgets"));
        assertEquals(0, o.getJsonArray("widgets").size());
        // from/to 为 null 时不下发（空看板没有窗口可言）
        assertFalse(o.containsKey("from"));
        assertFalse(o.containsKey("to"));
    }

    @Test
    void encodeData_nullIsEmptyObject() {
        assertEquals(0, WebDashboardWidgetDataCodec.encode(null).size());
        assertNull(WebDashboardWidgetDataCodec.encode(null).getJsonArray("widgets"));
    }

    // ===== helpers =====

    private static WebDashboardWidgetDataService.Item ok(String id, JsonObject data) {
        return new WebDashboardWidgetDataService.Item(id, true, data, null);
    }

    private static WebDashboardWidgetDataService.Item failed(String id, String message) {
        return new WebDashboardWidgetDataService.Item(id, false, null, message);
    }
}
