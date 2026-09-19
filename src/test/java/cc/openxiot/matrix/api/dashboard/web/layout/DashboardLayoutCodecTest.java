package cc.openxiot.matrix.api.dashboard.web.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.dashboard.web.DashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.web.widget.DashboardWidget;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * {@link DashboardLayoutCodec} 的线上形状。**纯 JUnit，不连库**。
 *
 * <p>重点在「什么都不补」这条约定：缺省项（{@code title} 没设、{@code x}/{@code y} 没给、
 * 预置布局没有 id/creator/updater）一律<b>不出现在 JSON 里</b>。补成空串或 0 会让「没设」
 * 和「设成了空」再也分不开，前端也就没法区分「用默认标题」和「标题被清空了」。</p>
 *
 * <p>卡片数据的另一份 codec 在
 * {@code cc.openxiot.matrix.api.dashboard.web.widget.WidgetDataCodecTest}。</p>
 */
class DashboardLayoutCodecTest {

    @Test
    void encode_nullLayoutIsEmptyObject() {
        assertEquals(0, DashboardLayoutCodec.encode(null).size());
    }

    @Test
    void encode_presetHasNoIdOrCreator() {
        JsonObject o = DashboardLayoutCodec.encode(PresetLayouts.build("space-1"));

        assertEquals("space-1", o.getString("spaceId"));
        // 预置布局的版本号是 0 —— 这同时就是「首次保存要提交的版本号」，
        // 所以前端不需要知道手里这份是预置还是真的（原样回传即可）
        assertEquals(0L, o.getLong("version").longValue());
        assertEquals(8, o.getJsonArray("widgets").size());
        // 布局对外的身份是 spaceId：暴露一个前端用不上的 ObjectId 只会让人以为它能寻址
        assertFalse(o.containsKey("id"));
        assertFalse(o.containsKey("creator"));
        assertFalse(o.containsKey("updater"));
    }

    @Test
    void encode_presetWidgetsCarryTitleKeyNotTitle() {
        JsonArray widgets = DashboardLayoutCodec.encode(PresetLayouts.build("space-1")).getJsonArray("widgets");
        for (int i = 0; i < widgets.size(); i++) {
            JsonObject widget = widgets.getJsonObject(i);
            // 预置布局是服务端造的：中文只能走 titleKey（页面文案，前端翻译），
            // 而 title 属于用户数据、前端不许翻译
            assertTrue(widget.containsKey("titleKey"), "widget " + i + " has no titleKey");
            assertFalse(widget.containsKey("title"), "widget " + i + " must not carry a hard-coded title");
        }
        JsonObject first = widgets.getJsonObject(0);
        assertEquals("设备总量", first.getString("titleKey"));
        assertEquals("stat", first.getString("type"));
        // 统计卡是 W6H92（一行高 92px）：它没有卡头、按内容自然高约 90px，塞在 200px 的
        // 档位里下面会空 110px。这一条同时钉住「预置布局的档位不许被顺手改回 W6H200」
        assertEquals("W6H92", first.getString("size"));
    }

    @Test
    void encode_presetWidgetsAreInVisualOrder() {
        JsonArray widgets = DashboardLayoutCodec.encode(PresetLayouts.build("space-1")).getJsonArray("widgets");
        // **数组顺序是阅读顺序**（先行后列，也正是视觉顺序）：4 张统计卡在前（各占 6 列、
        // 一行高），后 4 张 W12H308 两两一行。预置布局一个坐标都没给，位置由前端按这个顺序铺出来，
        // 所以改动 PresetLayouts 时把顺序弄乱，用户看到的预置首屏就会跟着变
        assertEquals(8, widgets.size());
        for (int i = 0; i < 4; i++) {
            assertEquals("stat", widgets.getJsonObject(i).getString("type"), "widget " + i);
            assertEquals("W6H92", widgets.getJsonObject(i).getString("size"), "widget " + i);
            // 预置布局**不带坐标**：坐标得配着 w 一起写才对，而 w 只在前端（见 PresetLayouts）
            assertFalse(widgets.getJsonObject(i).containsKey("x"), "widget " + i);
            assertFalse(widgets.getJsonObject(i).containsKey("y"), "widget " + i);
        }
        for (int i = 4; i < 8; i++) {
            assertEquals("W12H308", widgets.getJsonObject(i).getString("size"), "widget " + i);
        }
    }

    @Test
    void encodeWidget_userTitleWins() {
        DashboardWidget w = widget();
        w.title = "东区温度";
        w.titleKey = "设备总量";
        JsonObject o = DashboardLayoutCodec.encodeWidget(w);
        // 两个都下发，取值顺序（title → titleKey → 按 type 的默认名）由前端定：
        // 服务端替它挑一个，改规则时就得同时改两边
        assertEquals("东区温度", o.getString("title"));
        assertEquals("设备总量", o.getString("titleKey"));
    }

    @Test
    void encodeWidget_omitsUnsetOptionals() {
        DashboardWidget w = widget();
        JsonObject o = DashboardLayoutCodec.encodeWidget(w);
        assertFalse(o.containsKey("title"));
        assertFalse(o.containsKey("titleKey"));
        assertTrue(o.containsKey("id"));
        assertTrue(o.containsKey("type"));
        assertTrue(o.containsKey("size"));
        // 一个坐标键都不发：没给坐标的卡片（老文档）就是「还没摆过」，
        // 前端据此整份重铺。老文档里那个嵌套的 `layout` 是历史残留，不读也不写，
        // 而平铺的 `x`/`y` 是**认识**的键 —— 区别就在下面这条断言与下一条用例之间
        assertFalse(o.containsKey("layout"));
        assertFalse(o.containsKey("x"));
        assertFalse(o.containsKey("y"));
    }

    @Test
    void encodeWidget_positionIsEmitted() {
        DashboardWidget w = widget();
        w.x = 6;
        w.y = 4;
        JsonObject o = DashboardLayoutCodec.encodeWidget(w);
        // 位置是必须发的：不发就等于每次保存都退回「没有坐标」，用户刷新一次整屏重排
        assertEquals(6, o.getInteger("x"));
        assertEquals(4, o.getInteger("y"));
    }

    @Test
    void encodeWidget_configNeverNull() {
        DashboardWidget w = widget();
        w.config = null;
        // config 是必填的，但 codec 不替校验器兜底：这里给空对象而不是 null，
        // 是为了让前端少一个判空分支（缺 config 的卡片在到达 codec 之前就被拒了）
        assertEquals(new JsonObject(), DashboardLayoutCodec.encodeWidget(w).getJsonObject("config"));
    }

    @Test
    void encodeWidget_configPassesThroughNestedStructures() {
        DashboardWidget w = widget();
        w.config = new LinkedHashMap<>(Map.of(
                "metric", "alarms.today",
                "window", Map.of("kind", "last", "hours", 24),
                "fields", List.of("temp", "status.bit0")));
        JsonObject config = DashboardLayoutCodec.encodeWidget(w).getJsonObject("config");
        assertEquals("alarms.today", config.getString("metric"));
        assertEquals(24, config.getJsonObject("window").getInteger("hours"));
        assertEquals("status.bit0", config.getJsonArray("fields").getString(1));
    }

    @Test
    void encode_nullWidgetIsEmptyObject() {
        assertEquals(0, DashboardLayoutCodec.encodeWidget(null).size());
        assertEquals(0, DashboardLayoutCodec.encodeWidgets(null).size());
    }

    @Test
    void encode_creatorEncodedWhenPresent() {
        DashboardLayoutEntity layout = PresetLayouts.build("space-1");
        layout.creator = Person.of("acc-1", "张三");
        JsonObject o = DashboardLayoutCodec.encode(layout);
        assertEquals("acc-1", o.getJsonObject("creator").getString("id"));
        assertEquals("张三", o.getJsonObject("creator").getString("name"));
        // 时间戳按毫秒输出，与前端 new Date(ms) 同口径
        assertTrue(o.getJsonObject("creator").getLong("timestamp") > 0);
        assertFalse(o.containsKey("updater"));
    }

    // ===== helpers =====

    private static DashboardWidget widget() {
        DashboardWidget w = new DashboardWidget();
        w.id = "w1";
        w.type = "stat";
        w.size = "W6H200";
        w.config = new LinkedHashMap<>(Map.of("metric", "devices.total"));
        return w;
    }
}
