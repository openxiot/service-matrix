package cc.openxiot.matrix.api.dashboard.mobile.widget;

import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import cc.openxiot.matrix.db.dashboard.mobile.widget.MobileDashboardWidgetSide;
import cc.openxiot.matrix.db.dashboard.mobile.widget.MobileDashboardWidgetSize;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡片的**有效半格**：把「显式写了 side」与「没写」混在一起的那份列表，逐张算出它到底占哪半格。
 *
 * <p>排布规则与 {@link MobileDashboardWidgetSide} 的说明逐字一致，这里只补「缺省怎么推」：
 * <b>从左往右扫一遍</b>（**不是**「奇偶交替」—— 显式与缺省混排时奇偶会推错），维护一个
 * 「当前这一行的右半格是不是空的」；{@code FULL} 把当前行关掉。于是：</p>
 * <ul>
 *   <li>显式 {@code LEFT} → 另起一行（<b>前一张的右半格若空着就空着</b>，这正是「不横向补位」）；</li>
 *   <li>显式 {@code RIGHT} → 右半格空着就填进去，否则另起一行、左半格留空；</li>
 *   <li>没写 → 右半格空着就 {@code RIGHT}，否则 {@code LEFT}。</li>
 * </ul>
 *
 * <p><b>全都没写时退化成 {@code L,R,L,R…}</b>，恰好就是改造前「连续两张 HALF 并排」的样子
 * —— 所以存量文档不需要迁移，长相一模一样。这条性质有单测逐例钉着。</p>
 *
 * <p><b>服务端做这个推导，不违反「服务端不留第二份会过期的真值」</b>：那条针对的是
 * 「档位 → 占几列几行」的<b>占格表</b>（那份表只在前端，服务端存了就会过期）；而有效半格只用
 * 同一份 payload 里的顺序与尺寸推，没有第二个真值。放在 codec 里是为了让 {@code GET} 对
 * <b>所有客户端自描述</b>：读回来的每张 HALF 卡都带显式 {@code side}，客户端不必自己推，
 * 也就不会在松手时把同伴横着翻面。</p>
 */
public final class MobileDashboardWidgetSides {

    private MobileDashboardWidgetSides() {
    }

    /**
     * 逐张算有效半格，返回与入参<b>等长</b>的列表：{@code FULL} 卡（以及档位为空的卡）是 {@code null}。
     * {@code side} 写了但不认识（校验器会拒，这里只防御）按「没写」处理，往下推。
     */
    public static List<MobileDashboardWidgetSide> effective(List<MobileDashboardWidget> widgets) {
        List<MobileDashboardWidgetSide> sides = new ArrayList<>();
        if (widgets == null) {
            return sides;
        }
        // 当前这一行的右半格是不是空的。FULL 卡与「另起一行」都会把它关掉
        boolean rightFree = false;
        for (MobileDashboardWidget widget : widgets) {
            MobileDashboardWidgetSize size = widget == null ? null : MobileDashboardWidgetSize.of(widget.size);
            if (size != MobileDashboardWidgetSize.HALF) {
                // FULL（或档位不认识）：独占一行，当前行到此为止
                rightFree = false;
                sides.add(null);
                continue;
            }
            MobileDashboardWidgetSide explicit = widget.side == null
                    ? null
                    : MobileDashboardWidgetSide.of(widget.side);
            MobileDashboardWidgetSide side;
            if (explicit == MobileDashboardWidgetSide.LEFT) {
                // 另起一行。前一张的右半格若空着就让它空着 —— 这正是「不横向补位」
                side = MobileDashboardWidgetSide.LEFT;
                rightFree = true;
            } else if (explicit == MobileDashboardWidgetSide.RIGHT) {
                // 右半格空着就填进去，否则另起一行（左半格留空）。两种情况下这一行都满了
                side = MobileDashboardWidgetSide.RIGHT;
                rightFree = false;
            } else if (rightFree) {
                side = MobileDashboardWidgetSide.RIGHT;
                rightFree = false;
            } else {
                side = MobileDashboardWidgetSide.LEFT;
                rightFree = true;
            }
            sides.add(side);
        }
        return sides;
    }
}
