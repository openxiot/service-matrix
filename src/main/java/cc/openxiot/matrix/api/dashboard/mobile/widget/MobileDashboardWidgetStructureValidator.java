package cc.openxiot.matrix.api.dashboard.mobile.widget;

import cc.openxiot.matrix.api.dashboard.mobile.params.MobileDashboardLayoutDto;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetStructureValidator;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import cc.openxiot.matrix.db.dashboard.mobile.widget.MobileDashboardWidgetSide;
import cc.openxiot.matrix.db.dashboard.mobile.widget.MobileDashboardWidgetSize;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 移动端卡片配置的**结构性**校验外壳：纯函数、无状态、不碰数据库 —— 返回 {@code null} 表示通过，
 * 否则返回给前端看的错误文案。与 Web 的 {@code WebDashboardWidgetStructureValidator} 是同一套骨架，
 * 差别只在 Web 那半边特有的两样（档位与坐标）换成了移动端这两样（{@code FULL/HALF} 档位、
 * 半宽卡占哪半格 {@code side}）。
 *
 * <p><b>身份与 config 的规则全在共享核心</b>（{@link DashboardWidgetStructureValidator}），Web /
 * Mobile 一套口径一套文案；本类只补移动端特有的<b>档位</b>（{@link MobileDashboardWidgetSize}）
 * 与<b>半格</b>（{@link MobileDashboardWidgetSide}），并按「身份 → 尺寸 → 半格 → config」
 * 插在各阶段之间。移动端<b>没有坐标</b>：卡片不带 {@code x}/{@code y}（竖排堆叠，数组顺序即
 * 阅读顺序），也就没有坐标这一道校验。</p>
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

    /** 半格名单文案，同样从枚举生成 —— 抄一遍迟早会说错话。 */
    private static final String SIDE_NAMES =
            Arrays.stream(MobileDashboardWidgetSide.values()).map(Enum::name).collect(Collectors.joining("/"));

    /**
     * 开放半宽的类型：统计卡（两块小数字并列很常见）与服务卡（一行「字段名 + 值」，半宽放得下）。
     *
     * <p>曲线与分布图半宽画不开 —— 存进去也只是渲染时退化，不如保存时就说清楚。设备卡也没开：
     * 它同样是一行，但没人要过，开不开是产品口径，不是渲染能力问题（想开就在这行加 {@code "device"}，
     * 文案跟着改）。</p>
     */
    private static final Set<String> HALF_TYPES = Set.of("stat", "fd");

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

    /** 单张卡：身份 → 尺寸 → 半格 → config。尺寸与半格是移动端特有的，身份与 config 走共享核心。 */
    private static String validateWidget(MobileDashboardWidget widget) {
        String identity = DashboardWidgetStructureValidator.identityError(widget);
        if (identity != null) {
            return identity;
        }
        MobileDashboardWidgetSize size = MobileDashboardWidgetSize.of(widget.size);
        if (size == null) {
            return "size must be one of " + SIZE_NAMES;
        }
        // HALF 只对 HALF_TYPES 里的类型开放（stat / service，理由见那个常量）
        if (size == MobileDashboardWidgetSize.HALF && !HALF_TYPES.contains(widget.type)) {
            return "size HALF is only valid for stat and service cards";
        }
        // 半格只有 HALF 卡有：**给了就必须是 LEFT/RIGHT**（认不出来就报错，别让它悄悄按缺省推；
        // 编辑器笔下「我以为它靠左、其实靠右」是最难查的一类）。反过来，**FULL 卡带着 side 不报错**、
        // 下游一律忽略：render 也用这套校验校验编辑器里那份未保存的草稿，而编辑页的实时预览会
        // 吞掉 render 失败（MobileDashboardViewModel 里那处 onFailure），硬拒会让预览无声冻住；
        // 而「FULL 带 side」在下游无害 —— codec 不发、排布不看。
        if (size == MobileDashboardWidgetSize.HALF && widget.side != null
                && MobileDashboardWidgetSide.of(widget.side) == null) {
            return "side must be one of " + SIDE_NAMES;
        }
        return DashboardWidgetStructureValidator.configError(widget);
    }
}