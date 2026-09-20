package cc.openxiot.matrix.api.dashboard.mobile.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;

import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import org.junit.jupiter.api.Test;

/**
 * {@link MobileDashboardWidgetSides#effective} 的契约：**纯 JUnit**。
 *
 * <p>这是「有效半格」的唯一实现，压着两件事：</p>
 * <ul>
 *   <li><b>存量文档零迁移</b>：全都没写 {@code side} 时必须退化成 {@code L,R,L,R…}，
 *       也就是改造前「连续两张 HALF 并排」的那个样子；</li>
 *   <li><b>不横向补位</b>：显式写了 side 的卡，抽走它旁边那张之后有效半格<b>一个都不变</b>
 *       —— 空出来的半格就空着。</li>
 * </ul>
 *
 * <p>推的规则见 {@code MobileDashboardWidgetSide}（三端逐字一致的那份）。注意它<b>不是</b>
 * 「奇偶交替」：显式与缺省混排时奇偶会推错，所以是「从左往右扫、维护当前行右半格空不空」。
 * 期望值写法：逐张的有效半格，逗号分隔，{@code -} 表示没有半格（整宽卡）。</p>
 */
class MobileDashboardWidgetSidesTest {

    // ===== 退化：全没写 side = 改造前的样子 =====

    @Test
    void allUnset_degeneratesToGreedyPairing() {
        assertSides("LEFT,RIGHT", half(null), half(null));
        assertSides("LEFT,RIGHT,LEFT,RIGHT", half(null), half(null), half(null), half(null));
    }

    /** 奇数张落单：最后那张靠左，右边空着。 */
    @Test
    void allUnset_oddCountLeavesLastOneAlone() {
        assertSides("LEFT,RIGHT,LEFT", half(null), half(null), half(null));
    }

    @Test
    void fullCardClosesTheRow() {
        assertSides("LEFT,-,LEFT", half(null), full(), half(null));
        assertSides("LEFT,RIGHT,-,LEFT,RIGHT",
                half(null), half(null), full(), half(null), half(null));
    }

    // ===== 显式 side =====

    @Test
    void explicitRightFillsTheFreeRightHalf() {
        assertSides("LEFT,RIGHT", half("LEFT"), half("RIGHT"));
    }

    /** 显式 LEFT 时**前一张空着的右半格就空着**（这正是「不横向补位」）。 */
    @Test
    void explicitLeftOpensANewRowEvenIfRightHalfIsFree() {
        assertSides("LEFT,LEFT", half("LEFT"), half("LEFT"));
        assertSides("LEFT,RIGHT,LEFT", half(null), half(null), half("LEFT"));
    }

    /** 显式 RIGHT 但当前行右半格被占：另起一行、**左半格留空**。 */
    @Test
    void explicitRightWithoutFreeHalfOpensARowWithBlankLeft() {
        assertSides("-,RIGHT", full(), half("RIGHT"));
        assertSides("LEFT,RIGHT,RIGHT", half(null), half(null), half("RIGHT"));
    }

    /** FULL 卡把行关掉，所以紧跟的缺省 HALF 从新的一行开始，不会回头补上一行的右半格。 */
    @Test
    void explicitSideAfterFullDoesNotReachBackAcrossIt() {
        assertSides("LEFT,-,LEFT,RIGHT", half("LEFT"), full(), half(null), half(null));
    }

    // ===== 不横向补位：抽走一张，别人的有效半格不变 =====

    /** 抽走一对里的**左**半张 → 右半张原地不动（左半格空着）。用户报的就是这个。 */
    @Test
    void removingTheLeftOfAPair_leavesTheRightOneInPlace() {
        assertSides("LEFT,RIGHT", half("LEFT"), half("RIGHT"));

        assertSides("RIGHT", half("RIGHT"));
    }

    /** 抽走一对里的**右**半张 → 左半张照样在左。 */
    @Test
    void removingTheRightOfAPair_leavesTheLeftOneInPlace() {
        assertSides("LEFT", half("LEFT"));
    }

    // ===== 宽进：不认识的值不让它炸，按「没写」推 =====

    /** 校验器会拒不认识的值；万一漏进来（老客户端、手写请求），按没写推而不是抛。 */
    @Test
    void unrecognizedSideIsTreatedAsUnset() {
        assertSides("LEFT,RIGHT", half("middle"), half(null));
    }

    /** 档位为空/不认识：当整宽卡处理（关掉当前行、自己不算半格）—— 校验器会先拒掉它。 */
    @Test
    void unrecognizedSizeClosesTheRow() {
        assertSides("LEFT,-,LEFT", half(null), card("LARGE"), half(null));
    }

    // ===== 形状 =====

    @Test
    void emptyAndNullInput() {
        assertEquals(List.of(), MobileDashboardWidgetSides.effective(null));
        assertEquals(List.of(), MobileDashboardWidgetSides.effective(List.of()));
    }

    // ===== 夹具 =====

    private static void assertSides(String expected, MobileDashboardWidget... widgets) {
        String got = MobileDashboardWidgetSides.effective(List.of(widgets)).stream()
                .map(side -> side == null ? "-" : side.name())
                .collect(Collectors.joining(","));
        assertEquals(expected, got, "逐张的有效半格（- = 没有半格）");
    }

    private static MobileDashboardWidget half(String side) {
        MobileDashboardWidget w = card("HALF");
        w.side = side;
        return w;
    }

    private static MobileDashboardWidget full() {
        return card("FULL");
    }

    /** 半宽卡只对 stat 开放，但 {@code effective} 不看类型（校验器的事）——这里给 stat 只是为了写实。 */
    private static MobileDashboardWidget card(String size) {
        MobileDashboardWidget w = new MobileDashboardWidget();
        w.type = "stat";
        w.size = size;
        return w;
    }
}
