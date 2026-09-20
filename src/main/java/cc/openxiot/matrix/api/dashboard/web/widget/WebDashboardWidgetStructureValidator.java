package cc.openxiot.matrix.api.dashboard.web.widget;

import cc.openxiot.matrix.api.dashboard.web.params.WebDashboardLayoutDto;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetStructureValidator;
import cc.openxiot.matrix.db.dashboard.web.WebDashboardWidget;
import cc.openxiot.matrix.db.dashboard.web.widget.WebDashboardWidgetSize;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Web 看板卡片配置的**结构性**校验外壳：纯函数、无状态、不碰数据库 —— 返回 {@code null} 表示通过，
 * 否则返回给前端看的错误文案。照 {@code ModbusServiceValidator} 的风格，由调用方转成
 * {@link IllegalArgumentException}。
 *
 * <p><b>本类只管「配置本身合不合法」，不管「你能不能写」和「这个设备是不是你的」</b>：</p>
 * <ul>
 *   <li>「能不能写」是资源层 {@code requireSpaceAdmin} 的事；</li>
 *   <li>「设备/服务是否属于该空间」「{@code (serviceId, functionIndex, field)} 三元组是否存在」
 *       需要查库，是 {@link DashboardWidgetOwnershipValidator} 的事。</li>
 * </ul>
 * <p>这样切分的实际好处：本类能被不碰库的纯 JUnit 覆盖（{@code @QuarkusTest} 会要求连 Mongo），
 * 而且成员可用的 {@code render} 也能直接复用它校验请求体里那份「未保存的草稿」。</p>
 *
 * <p><b>与共享核心的分工</b>（见 {@link DashboardWidgetStructureValidator}）：身份与 config 的规则
 * 全在共享类里，Web 与 Mobile 一套口径。本类只补 Web 特有的两样 —— <b>档位</b>（{@link WebDashboardWidgetSize}）
 * 与<b>网格坐标</b>，并按「身份 → 尺寸 → 位置 → config」插在各阶段之间。Mobile 看板有自己的壳
 * （{@code MobileDashboardWidgetStructureValidator}，{@code FULL/HALF} 档位、无坐标），两者不共享
 * 尺寸/位置口径。</p>
 *
 * <p>错误文案一律带 {@code widgets[i]} 前缀（i 是卡片在数组里的下标）：编辑器一次能提交几十张卡，
 * 不指出是哪一张的报错等于没报。</p>
 */
public final class WebDashboardWidgetStructureValidator {

    /** 卡片数 / 窗口 / 服务字段上限属共享口径，统一收在共享核心，这里只作转发，避免两处数字漂移。 */
    public static final int MAX_WIDGETS = DashboardWidgetStructureValidator.MAX_WIDGETS;
    public static final int MAX_WINDOW_DAYS = DashboardWidgetStructureValidator.MAX_WINDOW_DAYS;
    public static final int MAX_SERVICE_FIELDS = DashboardWidgetStructureValidator.MAX_SERVICE_FIELDS;

    /**
     * 屏幕宽几格。**服务端只有这一个网格数字**，而且它抄不过来第二份 —— 它是屏幕宽，
     * 不是从档位推出来的（占格表 {@code w}/{@code h} 仍然只在前端）。与前端
     * {@code DashboardLayout.ts} 的 {@code GRID_COLUMNS} 一致。
     */
    private static final int GRID_COLUMNS = 24;

    /**
     * 档位名单的文案，**从枚举生成**。上一版这里是手抄的 {@code "S/M/L/XL"} —— 那是第四处
     * 硬编码名单（另三处是前端的类型、占格表、codec），档位一改名它就会说错话
     * （{@code S/M/L/XL} → {@code S1/M1/...} → 今天的 {@code W6H200} 那一套，改了两次）。
     * 生成出来则永远与枚举一致。
     */
    private static final String SIZE_NAMES =
            Arrays.stream(WebDashboardWidgetSize.values()).map(Enum::name).collect(Collectors.joining("/"));

    private WebDashboardWidgetStructureValidator() {
    }

    /** 校验保存请求体。返回 {@code null} 表示通过。 */
    public static String validate(WebDashboardLayoutDto body) {
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
    public static String validateWidgets(List<WebDashboardWidget> widgets) {
        return DashboardWidgetStructureValidator.validateWidgets(widgets, WebDashboardWidgetStructureValidator::validateWidget);
    }

    /** 单张卡：身份 → 尺寸 → 位置 → config。尺寸与坐标是 Web 特有的，身份与 config 走共享核心。 */
    private static String validateWidget(WebDashboardWidget widget) {
        String identity = DashboardWidgetStructureValidator.identityError(widget);
        if (identity != null) {
            return identity;
        }
        if (WebDashboardWidgetSize.of(widget.size) == null) {
            return "size must be one of " + SIZE_NAMES;
        }
        String positionError = validatePosition(widget);
        if (positionError != null) {
            return positionError;
        }
        return DashboardWidgetStructureValidator.configError(widget);
    }

    /**
     * 坐标的**粗校验**：两个一起给、`0 ≤ x ≤ 23`、`y ≥ 0`。
     *
     * <p><b>重叠与 {@code x + w ≤ 24} 不校验</b>，而且这不是漏了：服务端<b>没有 {@code w}</b>
     * —— 占几列几行由 {@code size} 档位在前端算（见 {@link WebDashboardWidgetSize}）。做不了的事不假装做，
     * 排版正确性由前端的那套坐标运算保证；这一层挡的是「明显不是坐标的数」（负数、越界、给一半）。</p>
     *
     * <p><b>两个都不给是合法的</b>：改造前存下来的老文档没有坐标，前端会按数组顺序铺一遍
     * （铺法复刻改造前的流式排布，所以老布局长相不变）。所以这里「都没有」放行、「只给一个」拒绝
     * —— 后者只可能是脏数据，放进去就是一张位置来路不明的卡。</p>
     */
    private static String validatePosition(WebDashboardWidget widget) {
        if (widget.x == null && widget.y == null) {
            return null;
        }
        if (widget.x == null || widget.y == null) {
            return "x and y must be given together";
        }
        if (widget.x < 0 || widget.x >= GRID_COLUMNS) {
            return "x must be between 0 and " + (GRID_COLUMNS - 1);
        }
        if (widget.y < 0) {
            return "y must not be negative";
        }
        return null;
    }
}