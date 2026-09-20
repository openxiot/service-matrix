package cc.openxiot.matrix.db.dashboard.mobile.widget;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 移动端**半宽卡**占这一行的哪半格：{@code LEFT}（左半格）／{@code RIGHT}（右半格）。
 *
 * <p>手机是竖排堆叠，一行最多两张半宽卡；占哪半格<b>必须是卡片自己的属性</b>，不能像以前那样
 * 由「连续两张 HALF 就配对」推出来 —— 那样抽走一张，同伴会被重新配对、从右半格滑到左半格
 * （用户报的「横向补位」），而「左半格空着、右半格还有卡」这个状态也根本写不出来。
 * 只有 {@code HALF} 卡有这个字段；{@code FULL} 卡独占一行，不带也不看它。</p>
 *
 * <h3>排布规则（唯一真源，三端逐字一致：这里 / {@code DashboardLayout.kt} / iPhone）</h3>
 * <ul>
 *   <li>{@code FULL} → 独占一行；</li>
 *   <li>{@code HALF} + {@code LEFT} → 另起一行，占左半格（右边没别的卡就是空的）；</li>
 *   <li>{@code HALF} + {@code RIGHT} → 当前这一行的右半格空着就填进去；否则另起一行、
 *       占右半格（左半格空着）。</li>
 * </ul>
 * <p><b>没写 {@code side} 的按状态机推</b>（不是「奇偶交替」——显式与缺省混排时奇偶会推错），
 * 见 {@code MobileDashboardWidgetSides.effective}：全没写时退化成 {@code L,R,L,R…}，
 * 也就是改造前「连续两张 HALF 并排」的样子，所以<b>存量文档零迁移、长相不变</b>。</p>
 *
 * <p><b>与 {@code MobileDashboardWidgetSize} 同一包、同一种写法</b>：全等 {@code of()}，
 * 没有别名表 —— 这是个新概念，没有历史遗留名要认。</p>
 */
@Schema(description = "移动端半宽卡占哪半格：左 LEFT / 右 RIGHT（仅 HALF 卡）")
public enum MobileDashboardWidgetSide {

    /** 左半格：另起一行。 */
    LEFT,

    /** 右半格：填进当前行空着的右半格，否则另起一行（左半格留空）。 */
    RIGHT;

    /** 按枚举名解析；不认识（或 null）返回 {@code null}。全等比较，不悄悄接受小写。 */
    public static MobileDashboardWidgetSide of(String side) {
        if (side == null) {
            return null;
        }
        for (MobileDashboardWidgetSide value : values()) {
            if (value.name().equals(side)) {
                return value;
            }
        }
        return null;
    }
}
