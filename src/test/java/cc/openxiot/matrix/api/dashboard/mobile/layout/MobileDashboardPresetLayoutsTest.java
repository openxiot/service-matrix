package cc.openxiot.matrix.api.dashboard.mobile.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import cc.openxiot.matrix.api.dashboard.mobile.params.MobileDashboardLayoutDto;
import cc.openxiot.matrix.api.dashboard.mobile.widget.MobileDashboardWidgetStructureValidator;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardLayoutEntity;
import io.vertx.core.json.JsonArray;
import org.junit.jupiter.api.Test;

/**
 * 移动端预置布局：一份竖屏从上到下的有序卡片，**纯函数，不落库**。
 *
 * <p>断言两件事：结构固定（数量/顺序/档位/id），以及<b>整份预置必须能通过结构校验</b> ——
 * 预置是每个还没配置过的空间所见的第一屏，它自己不合格会是开屏即错。</p>
 */
class MobileDashboardPresetLayoutsTest {

    @Test
    void preset_isOrderedPortraitStack() {
        MobileDashboardLayoutEntity preset = MobileDashboardPresetLayouts.build("s1");
        assertEquals("s1", preset.spaceId);
        assertEquals(MobileDashboardPresetLayouts.PRESET_VERSION, preset.version);

        List<MobileDashboardWidget> ws = preset.widgets;
        assertEquals(6, ws.size());

        // 前四张是 HALF 统计卡；两张一个小数字行
        for (int i = 0; i < 4; i++) {
            assertEquals("stat", ws.get(i).type, "ws[" + i + "] type");
            assertEquals("HALF", ws.get(i).size, "ws[" + i + "] size");
        }
        // 曲线/分布图必须整宽才画得开：HALF 只对 stat 开放
        assertEquals("distribution", ws.get(4).type);
        assertEquals("FULL", ws.get(4).size);
        assertEquals("line", ws.get(5).type);
        assertEquals("FULL", ws.get(5).size);

        // 顺序（=视觉顺序）：id 在预设里就是文档，别被排序或补位打乱
        assertEquals("m_preset_devices", ws.get(0).id);
        assertEquals("m_preset_services", ws.get(1).id);
        assertEquals("m_preset_alarms", ws.get(2).id);
        assertEquals("m_preset_failures", ws.get(3).id);
        assertEquals("m_preset_device_type", ws.get(4).id);
        assertEquals("m_preset_alarm_curve", ws.get(5).id);
    }

    /** 预置用 i18n key（titleKey）而不是用户数据（title）：预置是服务端造的，标题必须能翻译。 */
    @Test
    void preset_usesTitleKeysNotUserTitles() {
        for (MobileDashboardWidget w : MobileDashboardPresetLayouts.build("s1").widgets) {
            assertNull(w.title, "preset must not hardcode user data in title");
            assertNotNull(w.titleKey, "preset must carry an i18n key: " + w.id);
        }
    }

    /** 预置必须合法：它是每个新空间看到的第一屏。 */
    @Test
    void preset_passesStructureValidation() {
        MobileDashboardLayoutDto dto = new MobileDashboardLayoutDto();
        dto.version = MobileDashboardPresetLayouts.PRESET_VERSION;
        dto.widgets = MobileDashboardPresetLayouts.build("s1").widgets;
        assertNull(MobileDashboardWidgetStructureValidator.validate(dto),
                "preset layout must be structurally valid");
    }

    /**
     * 预置那四张 HALF 统计卡<b>不写 side</b>，靠推导就是 {@code L,R,L,R}（两行并排）——
     * 与改造前「连续两张 HALF 并排」的长相逐格一致。所以预置布局这一份一个字都不用改。
     */
    @Test
    void preset_derivesTwoSideBySideRows() {
        MobileDashboardLayoutEntity preset = MobileDashboardPresetLayouts.build("s1");

        JsonArray widgets = MobileDashboardLayoutCodec.encode(preset).getJsonArray("widgets");
        assertEquals("LEFT", sideOf(widgets, 0));
        assertEquals("RIGHT", sideOf(widgets, 1));
        assertEquals("LEFT", sideOf(widgets, 2));
        assertEquals("RIGHT", sideOf(widgets, 3));
        // 整宽的两张：没有半格这回事，键都不出现
        assertFalse(widgets.getJsonObject(4).containsKey("side"));
        assertFalse(widgets.getJsonObject(5).containsKey("side"));
    }

    private static String sideOf(JsonArray widgets, int index) {
        return widgets.getJsonObject(index).getString("side");
    }
}