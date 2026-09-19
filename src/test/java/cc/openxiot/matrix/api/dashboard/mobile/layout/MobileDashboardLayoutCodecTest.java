package cc.openxiot.matrix.api.dashboard.mobile.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.dashboard.DashboardWidget;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardLayoutEntity;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * 移动端布局 codec：编码的外壳 + 卡片逐帧。**纯 JUnit**。
 *
 * <p>与 Web codec 的唯一差异在 {@code encodeWidget}：移动端<b>不下发 x/y</b>
 * （位置真值就是数组顺序）。外壳字段（spaceId/version/creator/updater）与 Web 相同。</p>
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
        layout.widgets = List.of(card("w1", "stat", "FULL", config("metric", "devices.total"), 6, 3));

        JsonObject json = MobileDashboardLayoutCodec.encode(layout);
        assertEquals("s1", json.getString("spaceId"));
        assertEquals(42L, json.getLong("version"));
        assertEquals("u1", json.getJsonObject("creator").getString("id"));
        assertEquals("october", json.getJsonObject("updater").getString("name"));
    }

    /** 移动端卡片不下发坐标：就算实体里躺着 x/y（比如 Web 侧同 widget 曾存过），codec 也要把它丢掉。 */
    @Test
    void encode_widgetOmitsCoordinates() {
        MobileDashboardLayoutEntity layout = new MobileDashboardLayoutEntity();
        layout.spaceId = "s1";
        layout.version = 0L;
        layout.widgets = List.of(card("w1", "stat", "HALF", config("metric", "services.total"), 7, 4));

        JsonObject json = MobileDashboardLayoutCodec.encode(layout);
        JsonObject w = json.getJsonArray("widgets").getJsonObject(0);
        assertEquals("w1", w.getString("id"));
        assertEquals("stat", w.getString("type"));
        assertEquals("HALF", w.getString("size"));
        // config 内容非本类关注点（共享引擎的事）；这里只验外壳与坐标的有无
        assertFalse(w.containsKey("x"), "mobile widget must not carry x");
        assertFalse(w.containsKey("y"), "mobile widget must not carry y");
    }

    @Test
    void encode_identityFieldsPresent() {
        MobileDashboardLayoutEntity layout = new MobileDashboardLayoutEntity();
        layout.spaceId = "s1";
        layout.version = 0L;
        DashboardWidget w = card("w1", "service", "FULL", config("serviceId", "s1"), null, null);
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

    private static DashboardWidget card(String id, String type, String size,
                                        Map<String, Object> config, Integer x, Integer y) {
        DashboardWidget w = new DashboardWidget();
        w.id = id;
        w.type = type;
        w.size = size;
        w.config = config;
        w.x = x;
        w.y = y;
        return w;
    }

    private static Map<String, Object> config(String k, String v) {
        return Map.of(k, v);
    }
}