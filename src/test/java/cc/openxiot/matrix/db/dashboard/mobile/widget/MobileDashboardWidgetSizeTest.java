package cc.openxiot.matrix.db.dashboard.mobile.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * {@link MobileDashboardWidgetSize} 的解析契约：**纯 JUnit，不可变单例，根本不用连库**。
 *
 * <p>覆盖重点：全等比对、大小写敏感、未知/null 一律返回 {@code null}（拒收而不是悄悄回落）。
 * 与 Web 的 {@code WebDashboardWidgetSize.of} 同口径 —— 移动端档位不含历史遗留名，所以没有
 * 「旧名映射」这一说。</p>
 */
class MobileDashboardWidgetSizeTest {

    @Test
    void of_knownNames() {
        assertSame(MobileDashboardWidgetSize.FULL, MobileDashboardWidgetSize.of("FULL"));
        assertSame(MobileDashboardWidgetSize.HALF, MobileDashboardWidgetSize.of("HALF"));
    }

    @Test
    void of_caseSensitive() {
        assertNull(MobileDashboardWidgetSize.of("full"));
        assertNull(MobileDashboardWidgetSize.of("half"));
        assertNull(MobileDashboardWidgetSize.of("Full"));
    }

    @Test
    void of_webSizeNamesRejected() {
        // Web 的档位名（W{col}H{px}）不是移动端的：语义不同，不共用解析
        assertNull(MobileDashboardWidgetSize.of("W6H200"));
        assertNull(MobileDashboardWidgetSize.of("W12H200"));
    }

    @Test
    void of_unknownAndNull() {
        assertNull(MobileDashboardWidgetSize.of("LARGE"));
        assertNull(MobileDashboardWidgetSize.of(""));
        assertNull(MobileDashboardWidgetSize.of(null));
    }

    @Test
    void values_friendlyName() {
        assertEquals("FULL/HALF", String.join("/", java.util.Arrays.stream(
                MobileDashboardWidgetSize.values()).map(Enum::name).toList()));
    }
}