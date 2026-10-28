package cc.openxiot.matrix.api.dashboard.mobile.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardLayoutEntity;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * 移动端布局 codec：编码的外壳 + 卡片逐帧。**纯 JUnit**。
 *
 * <p>与 Web codec 的差异在 {@code encodeWidget} 取的实体不同：移动端卡片<b>没有坐标这回事</b>
 * （位置真值就是数组顺序；半宽卡占哪半格是 {@code side}，见下一步）。外壳字段
 * （spaceId/version/creator/updater）与 Web 相同。</p>
 */
class MobileDashboardLayoutCodecTest {

    @Test
    void encode_layoutShell() {
        Person who = new Person();
        who.id = "u1";
        who.name = "october";

        MobileDashboardLayoutEntity layout = new MobileDashboardLayoutEntity();
        layout.spaceId = "s1";
        layout.version = 42L;
        layout.creator = who;
        layout.updater = who;
        layout.widgets = List.of(card("w1", "stat", "FULL", config("metric", "devices.total")));

        JsonObject json = MobileDashboardLayoutCodec.encode(layout);
        assertEquals("s1", json.getString("spaceId"));
        assertEquals(42L, json.getLong("version"));
        assertEquals("u1", json.getJsonObject("creator").getString("id"));
        assertEquals("october", json.getJsonObject("updater").getString("name"));
    }

    @Test
    void encode_identityFieldsPresent() {
        MobileDashboardLayoutEntity layout = new MobileDashboardLayoutEntity();
        layout.spaceId = "s1";
        layout.version = 0L;
        MobileDashboardWidget w = card("w1", "fd", "FULL", config("fdid", "s1"));
        w.title = "我的服务";
        w.titleKey = "dashboard.service.title";
        layout.widgets = List.of(w);

        JsonObject json = MobileDashboardLayoutCodec.encode(layout);
        JsonObject wj = json.getJsonArray("widgets").getJsonObject(0);
        assertEquals("我的服务", wj.getString("title"));
        assertEquals("dashboard.service.title", wj.getString("titleKey"));
    }

    @Test
    void encode_emptyWidgetsBecomesEmptyArray() {
        MobileDashboardLayoutEntity layout = new MobileDashboardLayoutEntity();
        layout.spaceId = "s1";
        layout.version = 0L;
        layout.widgets = null;

        JsonObject json = MobileDashboardLayoutCodec.encode(layout);
        assertTrue(json.getJsonArray("widgets").isEmpty(), "should encode empty array, not null");
    }

    @Test
    void encode_nullOptionalFieldsDropped() {
        MobileDashboardLayoutEntity layout = new MobileDashboardLayoutEntity();
        layout.spaceId = "s1";
        layout.version = 0L;
        layout.creator = null;
        layout.updater = null;
        layout.widgets = List.of();

        JsonObject json = MobileDashboardLayoutCodec.encode(layout);
        assertEquals("s1", json.getString("spaceId"));
        assertFalse(json.containsKey("creator"));
        assertFalse(json.containsKey("updater"));
    }

    // ===== side：下发的是「有效半格」 =====

    /**
     * 没写 side 的两张 HALF：服务端推出来再下发 —— {@code GET} 因此对<b>所有</b>客户端自描述，
     * 客户端不必自己推（自己推就会在拖走一张后把同伴横着翻面）。
     */
    @Test
    void encode_derivesSideWhenAbsent() {
        MobileDashboardLayoutEntity layout = layout(
                half("m1", null), half("m2", null), full("m3"));

        JsonArray widgets = MobileDashboardLayoutCodec.encode(layout).getJsonArray("widgets");
        assertEquals("LEFT", sideOf(widgets, 0));
        assertEquals("RIGHT", sideOf(widgets, 1));
        assertFalse(widgets.getJsonObject(2).containsKey("side"), "FULL 卡没有半格，不带这个键");
    }

    /** 写了 side 就原样下发，不被重新推：两张都写 LEFT 就是两行、各自靠左（右半格空着）。 */
    @Test
    void encode_keepsExplicitSide() {
        MobileDashboardLayoutEntity layout = layout(half("m1", "LEFT"), half("m2", "LEFT"));

        JsonArray widgets = MobileDashboardLayoutCodec.encode(layout).getJsonArray("widgets");
        assertEquals("LEFT", sideOf(widgets, 0));
        assertEquals("LEFT", sideOf(widgets, 1));
    }

    /**
     * 单张编码的那个重载**没有上下文，所以不发 side**：就算实体上写着 LEFT 也不发 ——
     * 「不发」= 客户端按缺省推（全没写 = 两张并排的老样子），而补一个瞎猜的值会从线上格式里长出假真值。
     */
    @Test
    void encode_singleWidgetOmitsSide() {
        MobileDashboardWidget w = half("m1", "LEFT");

        JsonObject one = MobileDashboardLayoutCodec.encodeWidget(w);

        assertFalse(one.containsKey("side"), "无上下文的单张编码不下发 side");
    }

    private static MobileDashboardLayoutEntity layout(MobileDashboardWidget... widgets) {
        MobileDashboardLayoutEntity layout = new MobileDashboardLayoutEntity();
        layout.spaceId = "s1";
        layout.version = 0L;
        layout.widgets = List.of(widgets);
        return layout;
    }

    private static String sideOf(JsonArray widgets, int index) {
        return widgets.getJsonObject(index).getString("side");
    }

    private static MobileDashboardWidget half(String id, String side) {
        MobileDashboardWidget w = card(id, "stat", "HALF", config("metric", "devices.total"));
        w.side = side;
        return w;
    }

    private static MobileDashboardWidget full(String id) {
        return card(id, "line", "FULL", config("source", "alarmCount"));
    }

    private static MobileDashboardWidget card(String id, String type, String size,
                                              Map<String, Object> config) {
        MobileDashboardWidget w = new MobileDashboardWidget();
        w.id = id;
        w.type = type;
        w.size = size;
        w.config = config;
        return w;
    }

    private static Map<String, Object> config(String k, String v) {
        return Map.of(k, v);
    }
}