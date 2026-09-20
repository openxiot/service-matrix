package cc.openxiot.matrix.api.dashboard.mobile.widget;

import cc.openxiot.matrix.api.dashboard.mobile.params.MobileDashboardLayoutDto;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetStructureValidator;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import cc.openxiot.matrix.db.dashboard.mobile.widget.MobileDashboardWidgetSize;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 移动端卡片配置的**结构性**校验外壳：纯函数、无状态、不碰数据库 —— 返回 {@code null} 表示通过，
 * 否则返回给前端看的错误文案。与 Web 的 {@code WebDashboardWidgetStructureValidator} 是同一套骨架，
 * 差别只在 Web 那半边特有的两样（档位与坐标）换成了移动端这一样（{@code FULL/HALF} 档位、无坐标）。
 *
 * <p><b>身份与 config 的规则全在共享核心</b>（{@link DashboardWidgetStructureValidator}），Web /
 * Mobile 一套口径一套文案；本类只补移动端特有的<b>档位</b>（{@link MobileDashboardWidgetSize}），
 * 并按「身份 → 尺寸 → config」插在各阶段之间。移动端<b>没有坐标</b>：卡片不带 {@code x}/{@code y}
 * （竖排堆叠，数组顺序即阅读顺序），也就没有坐标这一道校验。</p>
 *
 * <p>错误文案一律带 {@code widgets[i]} 前缀（i 是卡片在数组里的下标）。</p>
 */
public final class MobileDashboardWidgetStructureValidator {

    /**
     * 档位名单文案，**从枚举生成**（与 Web 外壳同法）：加档位那天不会说错话，
     * 手抄的名单则会变成「断言的两边同源」之外的第四处硬编码。
     */
    private static final String SIZE_NAMES =
            Arrays.stream(MobileDashboardWidgetSize.values()).map(Enum::name).collect(Collectors.joining("/"));

    private MobileDashboardWidgetStructureValidator() {
    }

    /** 校验保存请求体。返回 {@code null} 表示通过。 */
    public static String validate(MobileDashboardLayoutDto body) {
        if (body == null) {
            return "body is required";
        }
        if (body.version == null) {
            return "version is required";
        }
        if (body.version < 0) {
            return "version must not be negative";
        }
        return validateWidgets(body.widgets);
    }

    /**
     * 只校验卡片列表。{@code render} 用它校验请求体里那份**未保存的草稿** ——
     * 草稿没有 {@code version}，所以不能直接走 {@link #validate}。
     */
    public static String validateWidgets(List<MobileDashboardWidget> widgets) {
        return DashboardWidgetStructureValidator.validateWidgets(
                widgets, MobileDashboardWidgetStructureValidator::validateWidget);
    }

    /** 单张卡：身份 → 尺寸 → config。尺寸是移动端特有的，身份与 config 走共享核心。 */
    private static String validateWidget(MobileDashboardWidget widget) {
        String identity = DashboardWidgetStructureValidator.identityError(widget);
        if (identity != null) {
            return identity;
        }
        MobileDashboardWidgetSize size = MobileDashboardWidgetSize.of(widget.size);
        if (size == null) {
            return "size must be one of " + SIZE_NAMES;
        }
        // HALF 只对 stat 卡开放：两块小数字并列很常见；曲线/分布图半宽画不开，
        // 存进去也只是渲染时退化，不如保存时就说清楚
        if (size == MobileDashboardWidgetSize.HALF && !"stat".equals(widget.type)) {
            return "size HALF is only valid for stat cards";
        }
        return DashboardWidgetStructureValidator.configError(widget);
    }
}