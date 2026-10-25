package cc.openxiot.matrix.api.dashboard.widget;

import cc.openxiot.matrix.db.dashboard.DashboardWidgetView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 卡片配置的**结构性**校验 —— Web 与 Mobile 看板共享的那一半：卡片身份与 config 的纯函数校验。
 *
 * <p>这是从 {@code WebDashboardWidgetStructureValidator} 抽出来的共享核心。卡片在两种看板上
 * 是同一类东西（{@link DashboardWidgetView} 那五个字段、同一份 config 语义；实体各是各的），所以：
 * <ul>
 *   <li><b>身份 + config 归这里</b>（id/type/title/titleKey、config 必填、五种类型的 config 规则、窗口），
 *       Web 与 Mobile 共用，一套口径一套文案；</li>
 *   <li><b>尺寸与坐标归各自的壳</b>：Web 用 {@code WebDashboardWidgetStructureValidator}（24 列网格、
 *       {@code W} 档位、x/y），Mobile 用 {@code MobileDashboardWidgetStructureValidator}
 *       （{@code FULL/HALF} 档位、无 x/y）。</li>
 * </ul>
 * 两边的壳都只负责在「身份 → 尺寸 → 位置 → config」这个顺序里插入各自的尺寸/位置检查，
 * 其余都走这里，因此任一端的规格改动不会让另一端的校验跟着漂移。</p>
 *
 * <p>与 {@code ModbusDeviceValidator} 同风格：纯函数、无状态、不碰数据库，返回 {@code null} 表示通过，
 * 否则返回给前端看的错误文案。错误文案一律带 {@code widgets[i]} 前缀（i 是卡片在数组里的下标），
 * 这是循环层（每个看板各壳的 {@code validateWidgets}）加上的 —— 本类的方法返回的都是不带前缀的
 * 单张卡错误。</p>
 */
public final class DashboardWidgetStructureValidator {

    /** 一个看板的卡片数上限：防止一次 render 打爆后端（每张卡都可能触发一次上游查询）。 */
    public static final int MAX_WIDGETS = 30;

    /** 窗口上限，与 {@code /statistics/overview} 一致（31 天）。 */
    public static final int MAX_WINDOW_DAYS = 31;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** 一张服务卡片最多显示几个字段：一次调用虽然返回全部字段，但卡片放不下太多行。 */
    public static final int MAX_SERVICE_FIELDS = 12;

    /** 一张设备卡片最多显示几个属性。与服务卡同一档：卡片再大也放不下更多统计量。 */
    public static final int MAX_DEVICE_PIDS = 12;

    private static final Set<String> TYPES = Set.of("stat", "line", "distribution", "device", "service");

    private static final Set<String> STAT_METRICS = Set.of(
            "devices.total", "devices.online", "services.total",
            "alarms.today", "alarms.window", "failures.total");

    private static final Set<String> DIMENSIONS = Set.of(
            "deviceType", "serviceType", "alarmType", "failureType");

    /** 需要窗口的分布维度：告警与故障都按时间区间统计，设备/服务类型是当前快照。 */
    private static final Set<String> WINDOWED_DIMENSIONS = Set.of("alarmType", "failureType");

    private static final Set<String> LINE_SOURCES = Set.of("alarmCount", "serviceField");

    /**
     * 目前只实现 {@code pie}。{@code bar} 与 {@code gauge} 同属 P3，所以现在提交它们也会被拒 ——
     * 理由与 {@link #DISPLAYS} 相同：用户配了柱状图却看到饼图，比保存时报一句错更难查。
     */
    private static final Set<String> CHARTS = Set.of("pie");

    /**
     * 目前只实现 {@code value}。{@code gauge} 与设备卡片的 {@code boolean} 都排在 P3，
     * 所以现在提交它们会被拒 —— 与其静默降级成大字，不如让编辑器现在就报错。
     */
    private static final Set<String> DISPLAYS = Set.of("value");

    private DashboardWidgetStructureValidator() {
    }

    /**
     * 循环层：卡片数上限 + 逐张校验 + id 唯一性。{@code perCard} 由使用方（Web/Mobile 各壳）提供，
     * 以便把自己的尺寸/位置检查插进「身份 → 尺寸 → 位置 → config」的中间。返回 {@code null} 表示通过，
     * 否则返回带 {@code widgets[i]} 前缀的错误。
     */
    public static <T extends DashboardWidgetView> String validateWidgets(List<T> widgets, Function<T, String> perCard) {
        if (widgets == null) {
            return "widgets is required";
        }
        if (widgets.size() > MAX_WIDGETS) {
            return "too many widgets: at most " + MAX_WIDGETS;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < widgets.size(); i++) {
            T widget = widgets.get(i);
            String err = perCard.apply(widget);
            if (err != null) {
                return "widgets[" + i + "]: " + err;
            }
            if (!ids.add(widget.id())) {
                return "widgets[" + i + "]: duplicated widget id: " + widget.id();
            }
        }
        return null;
    }

    /** 身份 + config（不含尺寸/位置）。不加尺寸/位置的场景（如纯 session 校验）可以直接用它当 perCard。 */
    public static String validateConfig(DashboardWidgetView widget) {
        String identity = identityError(widget);
        return identity != null ? identity : configError(widget);
    }

    /**
     * 卡片身份：id / type / title / titleKey。本类两个校验的**第一段**，各壳要在它的后面、
     * config 的前面插入自己的尺寸/位置检查。
     */
    public static String identityError(DashboardWidgetView widget) {
        if (widget == null) {
            return "widget is required";
        }
        if (widget.id() == null || widget.id().isBlank()) {
            return "id is required";
        }
        if (widget.id().length() > 64) {
            return "id is too long";
        }
        if (widget.type() == null || !TYPES.contains(widget.type())) {
            return "type must be one of " + TYPES;
        }
        if (widget.title() != null && widget.title().length() > 128) {
            return "title is too long";
        }
        // titleKey 只由服务端的预置布局填写（§6.5），客户端提交它没有意义但也不致害；
        // 卡长度是为了不让一条超长 key 变成字典里查不到的垃圾数据。
        if (widget.titleKey() != null && widget.titleKey().length() > 128) {
            return "titleKey is too long";
        }
        return null;
    }

    /** 卡片 config：必填 + 按类型的规则。调用方要先跑 {@link #identityError} 保证 type 合法。 */
    public static String configError(DashboardWidgetView widget) {
        // 防御：configError 独立可调用，type 非法（null 也会让下面 switch 抛 NPE）先在这里挡一刀
        if (widget.type() == null || !TYPES.contains(widget.type())) {
            return "type must be one of " + TYPES;
        }
        Map<String, Object> config = widget.config();
        if (config == null) {
            return "config is required";
        }
        return switch (widget.type()) {
            case "stat" -> validateStat(config);
            case "line" -> validateLine(config);
            case "distribution" -> validateDistribution(config);
            case "device" -> validateDevice(config);
            case "service" -> validateService(config);
            default -> "type must be one of " + TYPES;
        };
    }

    private static String validateStat(Map<String, Object> config) {
        String metric = DashboardWidgetConfigs.str(config.get("metric"));
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
        String source = DashboardWidgetConfigs.str(config.get("source"));
        if (source == null || !LINE_SOURCES.contains(source)) {
            return "config.source must be one of " + LINE_SOURCES;
        }
        String windowErr = validateWindow(config.get("window"));
        if (windowErr != null) {
            return windowErr;
        }
        if ("alarmCount".equals(source)) {
            String bucket = DashboardWidgetConfigs.str(config.get("bucket"));
            if (bucket != null && !"hour".equals(bucket)) {
                return "config.bucket must be hour";
            }
            return null;
        }
        // serviceField：服务 + 寄存器字段（不再存方法序号 pollIndex，由取数时解析）。
        // 字段是否真实存在由 DashboardWidgetOwnershipValidator 查库确认。
        if (DashboardWidgetConfigs.str(config.get("serviceId")) == null) {
            return "config.serviceId is required";
        }
        if (DashboardWidgetConfigs.str(config.get("field")) == null) {
            return "config.field is required";
        }
        Integer maxPoints = DashboardWidgetConfigs.intVal(config.get("maxPoints"));
        if (maxPoints != null && (maxPoints < 1 || maxPoints > 2000)) {
            return "config.maxPoints must be between 1 and 2000";
        }
        return booleanErr(config, "showFailureShadow");
    }

    private static String validateDistribution(Map<String, Object> config) {
        String dimension = DashboardWidgetConfigs.str(config.get("dimension"));
        if (dimension == null || !DIMENSIONS.contains(dimension)) {
            return "config.dimension must be one of " + DIMENSIONS;
        }
        if (WINDOWED_DIMENSIONS.contains(dimension)) {
            String windowErr = validateWindow(config.get("window"));
            if (windowErr != null) {
                return windowErr;
            }
        }
        String chart = DashboardWidgetConfigs.str(config.get("chart"));
        if (config.containsKey("chart") && (chart == null || !CHARTS.contains(chart))) {
            return "config.chart must be one of " + CHARTS;
        }
        Integer limit = DashboardWidgetConfigs.intVal(config.get("limit"));
        if (config.containsKey("limit") && limit == null) {
            return "config.limit must be a number";
        }
        if (limit != null && (limit < 1 || limit > 100)) {
            return "config.limit must be between 1 and 100";
        }
        return null;
    }

    private static String validateDevice(Map<String, Object> config) {
        if (DashboardWidgetConfigs.str(config.get("did")) == null) {
            return "config.did is required";
        }
        String pidsErr = validateDevicePids(config);
        if (pidsErr != null) {
            return pidsErr;
        }
        String display = DashboardWidgetConfigs.str(config.get("display"));
        if (display != null && !DISPLAYS.contains(display)) {
            return "config.display must be one of " + DISPLAYS;
        }
        return booleanErr(config, "showUnit");
    }

    /**
     * 设备卡的属性清单。**两种写法都要认**：新配置写 {@code pids}（一张卡多个属性），
     * 改造前的卡只有一个 {@code pid} —— 库里存着旧的，重新保存一次也应当能过。
     *
     * <p>与 {@code config.fields} 同一套校验（非空、不超上限、元素为非空串、不重复）：
     * 重复的 pid 会让同一张卡把同一个数印两遍，而卡上没有任何东西说明那是同一只属性。</p>
     */
    private static String validateDevicePids(Map<String, Object> config) {
        if (!config.containsKey("pids")) {
            if (DashboardWidgetConfigs.str(config.get("pid")) == null) {
                return "config.pid or config.pids is required";
            }
            return null;
        }
        Object raw = config.get("pids");
        if (!(raw instanceof List<?> pids)) {
            return "config.pids must be an array";
        }
        if (pids.isEmpty()) {
            return "config.pids must not be empty";
        }
        if (pids.size() > MAX_DEVICE_PIDS) {
            return "config.pids has too many entries: at most " + MAX_DEVICE_PIDS;
        }
        Set<String> seen = new HashSet<>();
        for (Object pid : pids) {
            String value = DashboardWidgetConfigs.str(pid);
            if (value == null) {
                return "config.pids must contain strings";
            }
            if (!seen.add(value)) {
                return "config.pids has a duplicate: " + value;
            }
        }
        return null;
    }

    private static String validateService(Map<String, Object> config) {
        if (DashboardWidgetConfigs.str(config.get("serviceId")) == null) {
            return "config.serviceId is required";
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
            String name = DashboardWidgetConfigs.str(field);
            if (name == null) {
                return "config.fields must contain strings";
            }
            if (!seen.add(name)) {
                return "config.fields has a duplicate: " + name;
            }
        }
        String display = DashboardWidgetConfigs.str(config.get("display"));
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
        String kind = DashboardWidgetConfigs.str(window.get("kind"));
        if (kind == null) {
            return "config.window.kind is required";
        }
        return switch (kind) {
            case "last" -> {
                Integer hours = DashboardWidgetConfigs.intVal(window.get("hours"));
                if (hours == null) {
                    yield "config.window.hours must be a number";
                }
                if (hours < 1 || hours > MAX_WINDOW_DAYS * 24) {
                    yield "config.window.hours must be between 1 and " + (MAX_WINDOW_DAYS * 24);
                }
                yield null;
            }
            case "range" -> {
                Long from = DashboardWidgetConfigs.longVal(window.get("from"));
                Long to = DashboardWidgetConfigs.longVal(window.get("to"));
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