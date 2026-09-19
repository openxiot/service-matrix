package cc.openxiot.matrix.db.dashboard.mobile.widget;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 移动端卡片的**宽幅档位**：只有两个 —— {@code FULL}（整宽）／{@code HALF}（半宽）。
 *
 * <p>手机是竖排堆叠，没有 Web 那套 24 列网格，所以档位只有「占满一行」还是「占一半、
 * 可跟另一张 {@code HALF} 并排」两种。{@code HALF} 目前只对 {@code stat} 类卡开放
 * （两块小数字并列很常见；曲线/分布图半宽根本画不开）—— 这条约束由
 * {@code MobileDashboardWidgetStructureValidator} 在「身份 → 尺寸 → config」之间收口。</p>
 *
 * <p><b>与 Web 的 {@code WebDashboardWidgetSize} 是两个枚举</b>：不共用。Web 的档位名是
 * {@code W{col}H{px}}（宽度/高度），Mobile 是 {@code FULL/HALF}（宽幅），语义完全不同；
 * 共用一个枚举会把「档位名」和「占格含义」耦合死。卡片实体上的 {@code size} 字段是普通字符串，
 * 两种看板各自用各自的枚举去 {@code of()} 解析 —— 互不干扰。</p>
 */
@Schema(description = "移动端卡片宽幅档位：整宽 FULL / 半宽 HALF（仅 stat 卡）")
public enum MobileDashboardWidgetSize {

    /** 整宽：占满一行，卡片默认档位。 */
    FULL,

    /** 半宽：可与另一张 HALF 并排。仅 stat 类卡开放（曲线/分布图半宽画不开）。 */
    HALF;

    /**
     * 按枚举名解析；不认识（或 null）返回 {@code null}。全等比较，不悄悄接受小写 / 别名字符串
     * —— 与 {@code WebDashboardWidgetSize.of} 同口径。移动端档位是新增概念，没有历史遗留名。
     */
    public static MobileDashboardWidgetSize of(String size) {
        if (size == null) {
            return null;
        }
        for (MobileDashboardWidgetSize value : values()) {
            if (value.name().equals(size)) {
                return value;
            }
        }
        return null;
    }
}