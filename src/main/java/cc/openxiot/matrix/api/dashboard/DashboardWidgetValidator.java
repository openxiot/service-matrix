package cc.openxiot.matrix.api.dashboard;

import cc.openxiot.matrix.api.dashboard.params.DashboardLayoutDto;
import cc.openxiot.matrix.db.dashboard.DashboardWidget;
import cc.openxiot.matrix.db.dashboard.WidgetSize;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 看板卡片配置的**结构性**校验：纯函数、无状态、不碰数据库 —— 返回 {@code null} 表示通过，
 * 否则返回给前端看的错误文案。照 {@code ModbusServiceValidator} 的风格，由调用方转成
 * {@link IllegalArgumentException}。
 *
 * <p><b>服务端必须重做一遍，不能信前端</b>：卡片配置是用户数据，会直接驱动服务端的查询
 * （窗口多大、查哪个点表、展开几行），前端拦不住的请求这里得拦住。</p>
 *
 * <p><b>本类只管「配置本身合不合法」，不管「你能不能写」和「这个设备是不是你的」</b>：</p>
 * <ul>
 *   <li>「能不能写」是资源层 {@code requireSpaceAdmin} 的事；</li>
 *   <li>「设备/服务是否属于该空间」「{@code (serviceId, functionIndex, field)} 三元组是否存在」
 *       需要查库，是 {@link DashboardLayoutValidator} 的事。</li>
 * </ul>
 * <p>这样切分的实际好处：本类能被不碰库的纯 JUnit 覆盖（{@code @QuarkusTest} 会要求连 Mongo），
 * 而且成员可用的 {@code render} 也能直接复用它校验请求体里那份「未保存的草稿」。</p>
 *
 * <p>错误文案一律带 {@code widgets[i]} 前缀（i 是卡片在数组里的下标）：编辑器一次能提交几十张卡，
 * 不指出是哪一张的报错等于没报。</p>
 */
public final class DashboardWidgetValidator {

    /** 一个看板的卡片数上限：防止一次 render 打爆后端（每张卡都可能触发一次上游查询）。 */
    public static final int MAX_WIDGETS = 30;

    /** 窗口上限，与 {@code /statistics/overview} 一致（31 天）。 */
    public static final int MAX_WINDOW_DAYS = 31;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** 一张服务卡片最多显示几个字段：一次调用虽然返回全部字段，但卡片放不下太多行。 */
    public static final int MAX_SERVICE_FIELDS = 12;

    private static final Set<String> TYPES = Set.of("stat", "line", "distribution", "device", "service");

    /**
     * 屏幕宽几格。**服务端只有这一个网格数字**，而且它抄不过来第二份 —— 它是屏幕宽，
     * 不是从档位推出来的（占格表 {@code w}/{@code h} 仍然只在前端）。与前端
     * {@code DashboardLayout.ts} 的 {@code GRID_COLUMNS} 一致。
     */
    private static final int GRID_COLUMNS = 24;

    /**
     * 档位名单的文案，**从枚举生成**。上一版这里是手抄的 {@code "S/M/L/XL"} —— 那是第四处
     * 硬编码名单（另三处是前端的类型、占格表、codec），加 {@code S1}/{@code M1} 那天它就会说错话。
     */
    private static final String SIZE_NAMES =
            Arrays.stream(WidgetSize.values()).map(Enum::name).collect(Collectors.joining("/"));

    private static final Set<String> STAT_METRICS = Set.of(
            "devices.total", "devices.online", "services.total",
            "alarms.today", "alarms.window", "failures.total");

    private static final Set<String> DIMENSIONS = Set.of(
            "deviceType", "serviceType", "alarmType", "failureType");

    /** 需要窗口的分布维度：告警与故障都按时间区间统计，设备/服务类型是当前快照。 */
    private static final Set<String> WINDOWED_DIMENSIONS = Set.of("alarmType", "failureType");

    private static final Set<String> LINE_SOURCES = Set.of("alarmCount", "serviceField");

    /**
     * 目前只实现 {@code pie}。{@code bar} 与 {@code gauge} 同属 P3（要在 {@code echarts.directive.ts}
     * 补注册 {@code BarChart}），所以现在提交 {@code bar} 也会被拒 —— 理由与 {@link #DISPLAYS} 相同：
     * 用户配了柱状图却看到饼图，比保存时报一句错更难查。
     */
    private static final Set<String> CHARTS = Set.of("pie");

    /**
     * 目前只实现 {@code value}。{@code gauge} 与设备卡片的 {@code boolean} 都排在 P3
     * （仪表盘要在 {@code echarts.directive.ts} 补注册 {@code GaugeChart}），
     * 所以现在提交它们会被拒 —— 与其静默降级成大字，不如让编辑器现在就报错。
     */
    private static final Set<String> DISPLAYS = Set.of("value");

    private DashboardWidgetValidator() {
    }

    /** 校验保存请求体。返回 {@code null} 表示通过。 */
    public static String validate(DashboardLayoutDto body) {
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
    public static String validateWidgets(List<DashboardWidget> widgets) {
        if (widgets == null) {
            return "widgets is required";
        }
        if (widgets.size() > MAX_WIDGETS) {
            return "too many widgets: at most " + MAX_WIDGETS;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < widgets.size(); i++) {
            DashboardWidget widget = widgets.get(i);
            String err = validateWidget(widget);
            if (err != null) {
                return "widgets[" + i + "]: " + err;
            }
            if (!ids.add(widget.id)) {
                return "widgets[" + i + "]: duplicated widget id: " + widget.id;
            }
        }
        return null;
    }

    private static String validateWidget(DashboardWidget widget) {
        if (widget == null) {
            return "widget is required";
        }
        if (widget.id == null || widget.id.isBlank()) {
            return "id is required";
        }
        if (widget.id.length() > 64) {
            return "id is too long";
        }
        if (widget.type == null || !TYPES.contains(widget.type)) {
            return "type must be one of " + TYPES;
        }
        if (widget.title != null && widget.title.length() > 128) {
            return "title is too long";
        }
        // titleKey 只由服务端的预置布局填写（§6.5），客户端提交它没有意义但也不致害；
        // 卡长度是为了不让一条超长 key 变成字典里查不到的垃圾数据。
        if (widget.titleKey != null && widget.titleKey.length() > 128) {
            return "titleKey is too long";
        }
        if (WidgetSize.of(widget.size) == null) {
            return "size must be one of " + SIZE_NAMES;
        }
        if (widget.refresh != null && (widget.refresh < 0 || widget.refresh > 86_400)) {
            return "refresh must be between 0 and 86400 seconds";
        }
        String positionError = validatePosition(widget);
        if (positionError != null) {
            return positionError;
        }
        Map<String, Object> config = widget.config;
        if (config == null) {
            return "config is required";
        }
        return switch (widget.type) {
            case "stat" -> validateStat(config);
            case "line" -> validateLine(config);
            case "distribution" -> validateDistribution(config);
            case "device" -> validateDevice(config);
            case "service" -> validateService(config);
            default -> "type must be one of " + TYPES;
        };
    }

    /**
     * 坐标的**粗校验**：两个一起给、`0 ≤ x ≤ 23`、`y ≥ 0`。
     *
     * <p><b>重叠与 {@code x + w ≤ 24} 不校验</b>，而且这不是漏了：服务端<b>没有 {@code w}</b>
     * —— 占几列几行由 {@code size} 档位在前端算（见 {@link WidgetSize}）。做不了的事不假装做，
     * 排版正确性由前端的那套坐标运算保证；这一层挡的是「明显不是坐标的数」（负数、越界、给一半）。</p>
     *
     * <p><b>两个都不给是合法的</b>：改造前存下来的老文档没有坐标，前端会按数组顺序铺一遍
     * （铺法复刻改造前的流式排布，所以老布局长相不变）。所以这里「都没有」放行、「只给一个」拒绝
     * —— 后者只可能是脏数据，放进去就是一张位置来路不明的卡。</p>
     */
    private static String validatePosition(DashboardWidget widget) {
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

    private static String validateStat(Map<String, Object> config) {
        String metric = WidgetConfigs.str(config.get("metric"));
        if (metric == null || !STAT_METRICS.contains(metric)) {
            return "config.metric must be one of " + STAT_METRICS;
        }
        // 带窗口的 metric 必须给 window：漏了的话 render 会退回兜底窗口，
        // 「今日告警」就会被当成 1 小时窗口求和 —— 数字看着像真的，但是错的
        if (needsWindow(metric)) {
            return validateWindow(config.get("window"));
        }
        return null;
    }

    /** 只有带窗口的 metric 才要求 window；不带窗口的卡片给了也忽略（不报错，免得编辑器碍手碍脚）。 */
    private static boolean needsWindow(String metric) {
        return "alarms.today".equals(metric) || "alarms.window".equals(metric)
                || "failures.total".equals(metric);
    }

    private static String validateLine(Map<String, Object> config) {
        String source = WidgetConfigs.str(config.get("source"));
        if (source == null || !LINE_SOURCES.contains(source)) {
            return "config.source must be one of " + LINE_SOURCES;
        }
        String windowErr = validateWindow(config.get("window"));
        if (windowErr != null) {
            return windowErr;
        }
        if ("alarmCount".equals(source)) {
            String bucket = WidgetConfigs.str(config.get("bucket"));
            if (bucket != null && !"hour".equals(bucket)) {
                return "config.bucket must be hour";
            }
            return null;
        }
        // serviceField：三元组必须齐全。字段是否存在由 DashboardWidgetOwnershipValidator 查库确认。
        if (WidgetConfigs.str(config.get("serviceId")) == null) {
            return "config.serviceId is required";
        }
        if (WidgetConfigs.intVal(config.get("functionIndex")) == null) {
            return "config.functionIndex must be a number";
        }
        if (WidgetConfigs.str(config.get("field")) == null) {
            return "config.field is required";
        }
        Integer maxPoints = WidgetConfigs.intVal(config.get("maxPoints"));
        if (maxPoints != null && (maxPoints < 1 || maxPoints > 2000)) {
            return "config.maxPoints must be between 1 and 2000";
        }
        return booleanErr(config, "showFailureShadow");
    }

    private static String validateDistribution(Map<String, Object> config) {
        String dimension = WidgetConfigs.str(config.get("dimension"));
        if (dimension == null || !DIMENSIONS.contains(dimension)) {
            return "config.dimension must be one of " + DIMENSIONS;
        }
        if (WINDOWED_DIMENSIONS.contains(dimension)) {
            String windowErr = validateWindow(config.get("window"));
            if (windowErr != null) {
                return windowErr;
            }
        }
        String chart = WidgetConfigs.str(config.get("chart"));
        if (config.containsKey("chart") && (chart == null || !CHARTS.contains(chart))) {
            return "config.chart must be one of " + CHARTS;
        }
        Integer limit = WidgetConfigs.intVal(config.get("limit"));
        if (config.containsKey("limit") && limit == null) {
            return "config.limit must be a number";
        }
        if (limit != null && (limit < 1 || limit > 100)) {
            return "config.limit must be between 1 and 100";
        }
        return null;
    }

    private static String validateDevice(Map<String, Object> config) {
        if (WidgetConfigs.str(config.get("did")) == null) {
            return "config.did is required";
        }
        if (WidgetConfigs.str(config.get("pid")) == null) {
            return "config.pid is required";
        }
        String display = WidgetConfigs.str(config.get("display"));
        if (display != null && !DISPLAYS.contains(display)) {
            return "config.display must be one of " + DISPLAYS;
        }
        return booleanErr(config, "showUnit");
    }

    private static String validateService(Map<String, Object> config) {
        if (WidgetConfigs.str(config.get("serviceId")) == null) {
            return "config.serviceId is required";
        }
        if (WidgetConfigs.intVal(config.get("functionIndex")) == null) {
            return "config.functionIndex must be a number";
        }
        Object raw = config.get("fields");
        if (!(raw instanceof List<?> fields)) {
            return "config.fields must be an array";
        }
        if (fields.isEmpty()) {
            return "config.fields must not be empty";
        }
        if (fields.size() > MAX_SERVICE_FIELDS) {
            return "config.fields has too many entries: at most " + MAX_SERVICE_FIELDS;
        }
        Set<String> seen = new HashSet<>();
        for (Object field : fields) {
            String name = WidgetConfigs.str(field);
            if (name == null) {
                return "config.fields must contain strings";
            }
            if (!seen.add(name)) {
                return "config.fields has a duplicate: " + name;
            }
        }
        String display = WidgetConfigs.str(config.get("display"));
        if (display != null && !DISPLAYS.contains(display)) {
            return "config.display must be one of " + DISPLAYS;
        }
        // 只有一个开关了：`showStale`（陈旧阈值）已删 —— 卡片不判陈旧、只显示采集时刻，
        // 留着一个不再有效果的键会让下一个人以为它会判
        return booleanErr(config, "showUnit");
    }

    /**
     * 窗口：{@code {kind: "last", hours: N}} 或 {@code {kind: "range", from: ms, to: ms}}。
     *
     * <p>两种形态都只校验**跨度**不超过 31 天，与 {@code /statistics/overview} 同口径；
     * 具体时刻由前端算（后端没有「最近 N 小时」这类参数，这是既有约定）。</p>
     */
    private static String validateWindow(Object raw) {
        if (!(raw instanceof Map<?, ?> window)) {
            return "config.window is required";
        }
        String kind = WidgetConfigs.str(window.get("kind"));
        if (kind == null) {
            return "config.window.kind is required";
        }
        return switch (kind) {
            case "last" -> {
                Integer hours = WidgetConfigs.intVal(window.get("hours"));
                if (hours == null) {
                    yield "config.window.hours must be a number";
                }
                if (hours < 1 || hours > MAX_WINDOW_DAYS * 24) {
                    yield "config.window.hours must be between 1 and " + (MAX_WINDOW_DAYS * 24);
                }
                yield null;
            }
            case "range" -> {
                Long from = WidgetConfigs.longVal(window.get("from"));
                Long to = WidgetConfigs.longVal(window.get("to"));
                if (from == null || to == null) {
                    yield "config.window.from and config.window.to must be numbers (epoch millis)";
                }
                if (to <= from) {
                    yield "config.window.to must be after from";
                }
                if (to - from > MAX_WINDOW_DAYS * DAY_MS) {
                    yield "config.window must not exceed " + MAX_WINDOW_DAYS + " days";
                }
                yield null;
            }
            default -> "config.window.kind must be one of last/range";
        };
    }

    /** 可选的布尔项：缺省合法；给了就必须真是布尔（避免前端把字符串 "true" 塞进来）。 */
    private static String booleanErr(Map<String, Object> config, String key) {
        if (!config.containsKey(key)) {
            return null;
        }
        return config.get(key) instanceof Boolean ? null : "config." + key + " must be a boolean";
    }
}
