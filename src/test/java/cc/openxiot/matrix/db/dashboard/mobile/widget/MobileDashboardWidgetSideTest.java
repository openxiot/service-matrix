package cc.openxiot.matrix.db.dashboard.mobile.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * {@link MobileDashboardWidgetSide} 的解析契约：**纯 JUnit**。
 *
 * <p>与 {@code MobileDashboardWidgetSize.of} 同口径：全等比对、大小写敏感、未知/null 一律
 * 返回 {@code null}（拒收而不是悄悄回落）。半格是个新概念，没有历史遗留名要认。</p>
 */
class MobileDashboardWidgetSideTest {

    @Test
    void of_knownNames() {
        assertSame(MobileDashboardWidgetSide.LEFT, MobileDashboardWidgetSide.of("LEFT"));
        assertSame(MobileDashboardWidgetSide.RIGHT, MobileDashboardWidgetSide.of("RIGHT"));
    }

    @Test
    void of_caseSensitive() {
        assertNull(MobileDashboardWidgetSide.of("left"));
        assertNull(MobileDashboardWidgetSide.of("Right"));
    }

    @Test
    void of_unknownAndNull() {
        assertNull(MobileDashboardWidgetSide.of("CENTER"));
        assertNull(MobileDashboardWidgetSide.of(""));
        assertNull(MobileDashboardWidgetSide.of(null));
    }

    /** 校验文案里的名单是从枚举生成的，所以这里钉住顺序与写法。 */
    @Test
    void values_friendlyName() {
        assertEquals("LEFT/RIGHT", String.join("/", Arrays.stream(
                MobileDashboardWidgetSide.values()).map(Enum::name).toList()));
    }
}
