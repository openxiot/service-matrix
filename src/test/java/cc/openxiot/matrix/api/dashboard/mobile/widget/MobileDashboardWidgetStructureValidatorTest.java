package cc.openxiot.matrix.api.dashboard.mobile.widget;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cc.openxiot.matrix.api.dashboard.mobile.params.MobileDashboardLayoutDto;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetStructureValidator;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import org.junit.jupiter.api.Test;

/**
 * 移动端卡片配置的结构性校验。**纯 JUnit，不连库**（这正是把纯校验从查库的
 * {@code DashboardWidgetOwnershipValidator} 里切出来的原因）。
 *
 * <p>覆盖的重点是移动端外壳相对共享核心<b>多出来的那几条</b>：{@code FULL/HALF} 档位、
 * {@code HALF} 只对 stat / service 卡开放、半宽卡占哪半格（{@code side}）。身份与 config 的规则全在共享核心
 * （Web 同款），这里只挑几条代表，证明「委托到共享核心」这条链路是通的，不逐条重抄 Web 那份测试。</p>
 */
class MobileDashboardWidgetStructureValidatorTest {

    // ===== 布局外壳 =====

    @Test
    void validate_ok() {
        assertNull(MobileDashboardWidgetStructureValidator.validate(body(0L, stat("devices.total"))));
    }

    @Test
    void validate_versionRequired() {
        assertTrue(MobileDashboardWidgetStructureValidator.validate(body(null, stat("devices.total")))
                .contains("version is required"));
    }

    @Test
    void validate_tooManyWidgets() {
        List<MobileDashboardWidget> widgets = new ArrayList<>();
        for (int i = 0; i <= DashboardWidgetStructureValidator.MAX_WIDGETS; i++) {
            widgets.add(stat("devices.total"));
        }
        assertTrue(MobileDashboardWidgetStructureValidator.validate(body(0L, widgets))
                .contains("too many widgets: at most " + DashboardWidgetStructureValidator.MAX_WIDGETS));
    }

    @Test
    void validate_duplicatedId() {
        MobileDashboardWidget a = stat("devices.total");
        MobileDashboardWidget b = stat("services.total");
        b.id = a.id;
        assertTrue(MobileDashboardWidgetStructureValidator.validate(body(0L, List.of(a, b)))
                .contains("duplicated widget id"));
    }

    @Test
    void validate_errorCarriesWidgetIndex() {
        MobileDashboardWidget ok = stat("devices.total");
        MobileDashboardWidget bad = stat("devices.total");
        bad.type = "pie";
        String err = MobileDashboardWidgetStructureValidator.validate(body(0L, List.of(ok, bad)));
        assertNotNull(err);
        assertTrue(err.startsWith("widgets[1]: "), () -> "unexpected message: " + err);
    }

    // ===== 档位：FULL / HALF（移动端特有的那一条） =====

    @Test
    void size_fullAndHalfAreValid() {
        assertValid(stat("devices.total"));            // 缺省就设为 FULL 也行
        MobileDashboardWidget full = stat("devices.total");
        full.size = "FULL";
        assertValid(full);
        MobileDashboardWidget half = stat("devices.total");
        half.size = "HALF";
        assertValid(half);
        MobileDashboardWidget halfService = service();
        halfService.size = "HALF";
        assertValid(halfService);
    }

    @Test
    void sizeRequired() {
        MobileDashboardWidget w = stat("devices.total");
        w.size = null;
        assertInvalid("size must be one of FULL/HALF", w);
    }

    @Test
    void sizeCaseSensitive() {
        MobileDashboardWidget w = stat("devices.total");
        w.size = "full";
        assertInvalid("size must be one of FULL/HALF", w);
    }

    @Test
    void sizeUnknown() {
        MobileDashboardWidget w = stat("devices.total");
        w.size = "W6H200";            // Web 的档位名，移动端不认
        assertInvalid("size must be one of FULL/HALF", w);
    }

    /**
     * HALF 只对 stat / service 开放：曲线/分布图半宽画不开，存进去也只是渲染时退化，
     * 不如保存时就说清楚。设备卡也是一行、半宽放得下，但开不开是产品口径 —— 这条测试钉的是
     * **当前**口径，改口径时要连着它一起改（不是碰巧漏了 device）。
     */
    @Test
    void sizeHalfIsOnlyForStatAndService() {
        for (String type : List.of("line", "distribution", "device")) {
            MobileDashboardWidget w = typed(type);
            w.size = "HALF";
            assertInvalid("size HALF is only valid for stat and service cards", w);
        }
    }

    // ===== 半格：只有 HALF 卡有（移动端特有的又一条） =====

    @Test
    void side_leftAndRightAreValid() {
        MobileDashboardWidget left = stat("devices.total");
        left.side = "LEFT";
        assertValid(left);

        MobileDashboardWidget right = stat("devices.total");
        right.side = "RIGHT";
        assertValid(right);
    }

    /** 不写 side 也合法：服务端按数组顺序推（全没写 = 两张并排的老样子）。 */
    @Test
    void side_omittedIsValid() {
        assertValid(stat("devices.total"));
    }

    @Test
    void side_unknownValueRejected() {
        MobileDashboardWidget w = stat("devices.total");
        w.side = "middle";
        assertInvalid("side must be one of LEFT/RIGHT", w);
    }

    @Test
    void side_caseSensitive() {
        MobileDashboardWidget w = stat("devices.total");
        w.side = "left";
        assertInvalid("side must be one of LEFT/RIGHT", w);
    }

    /**
     * FULL 卡带着 side <b>不报错</b>，下游一律忽略（codec 不发、排布不看）。这不是漏网：
     * {@code render} 也用这套校验校验编辑器里那份未保存的草稿，而编辑页的实时预览<b>吞掉</b>
     * render 失败 —— 硬拒会让预览无声冻住，比「一个没人看的字符串躺在实体上」糟得多。
     */
    @Test
    void side_onFullCardIsIgnoredNotRejected() {
        MobileDashboardWidget full = stat("devices.total");
        full.size = "FULL";
        full.side = "LEFT";
        assertValid(full);

        // 连值都不认识也放过去：这张卡根本没有半格，它身上那个字符串谁也不读
        MobileDashboardWidget garbage = stat("devices.total");
        garbage.size = "FULL";
        garbage.side = "middle";
        assertValid(garbage);
    }

    // ===== 委托到共享核心（挑几条代表） =====

    @Test
    void stat_metricRequired() {
        assertInvalid("config.metric must be one of", typed("stat"));
    }

    @Test
    void stat_windowedMetricRequiresWindow() {
        assertInvalid("config.window is required", stat("alarms.today"));
    }

    @Test
    void line_serviceFieldTripleRequired() {
        MobileDashboardWidget line = new MobileDashboardWidget();
        line.id = "w1";
        line.type = "line";
        line.size = "FULL";
        line.config = config("source", "serviceField", "window", last(24));
        assertInvalid("config.serviceId is required", line);
    }

    @Test
    void service_fieldsMustNotBeEmpty() {
        MobileDashboardWidget svc = new MobileDashboardWidget();
        svc.id = "w1";
        svc.type = "service";
        svc.size = "FULL";
        svc.config = config("serviceId", "s1", "pollingIndex", 0, "fields", List.of());
        assertInvalid("config.fields must not be empty", svc);
    }

    // ===== helpers =====

    private static MobileDashboardLayoutDto body(Long version, MobileDashboardWidget widget) {
        return body(version, new ArrayList<>(List.of(widget)));
    }

    private static MobileDashboardLayoutDto body(Long version, List<MobileDashboardWidget> widgets) {
        MobileDashboardLayoutDto dto = new MobileDashboardLayoutDto();
        dto.version = version;
        dto.widgets = widgets;
        return dto;
    }

    private static MobileDashboardWidget stat(String metric) {
        MobileDashboardWidget w = typed("stat");
        w.config = config("metric", metric);
        return w;
    }

    /** 一张 config 齐全的服务卡（身份 / 档位 / 半格之外，config 那层也过得去）。 */
    private static MobileDashboardWidget service() {
        MobileDashboardWidget w = typed("service");
        w.config = config("serviceId", "0x01", "pollingIndex", 1, "fields", List.of("temperature"));
        return w;
    }

    private static MobileDashboardWidget typed(String type) {
        MobileDashboardWidget w = new MobileDashboardWidget();
        w.id = "w1";
        w.type = type;
        w.size = type.equals("stat") ? "HALF" : "FULL";
        w.config = config();
        return w;
    }

    private static Map<String, Object> config(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> last(int hours) {
        return config("kind", "last", "hours", hours);
    }

    private static void assertValid(MobileDashboardWidget widget) {
        String err = MobileDashboardWidgetStructureValidator.validate(body(0L, widget));
        assertNull(err, () -> "expected valid but got: " + err);
    }

    private static void assertInvalid(String expected, MobileDashboardWidget widget) {
        String err = MobileDashboardWidgetStructureValidator.validate(body(0L, widget));
        assertNotNull(err, () -> "expected an error containing <" + expected + ">");
        assertTrue(err.contains(expected), () -> "expected <" + expected + "> but was <" + err + ">");
    }
}