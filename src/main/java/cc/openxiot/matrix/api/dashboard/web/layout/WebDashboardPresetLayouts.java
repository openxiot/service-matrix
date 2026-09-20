package cc.openxiot.matrix.api.dashboard.web.layout;

import cc.openxiot.matrix.db.dashboard.web.WebDashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.web.WebDashboardWidget;
import cc.openxiot.matrix.db.dashboard.web.widget.WebDashboardWidgetSize;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 空间从未配置过时下发的**预置布局**：就是原来那一屏（4 张统计卡 + 3 张分布 + 1 条告警曲线），
 * 用新 schema 表达。
 *
 * <p><b>纯函数，不落库</b>（方案 §6.5）：布局是空间共享一份，若走「首次 GET 时补一条文档」，
 * 两个成员同时打开页面就会并发建两条 —— 要么加唯一索引加重试，要么面对重复文档，
 * 代价都大于收益。落库只发生在 {@code PUT}，所以在预置状态下 {@code version} 恒为 0，
 * 第一次保存提交 {@code version: 0} 即可。</p>
 *
 * <p><b>服务端一个坐标都不给，位置由前端按数组顺序铺一遍</b>。数组顺序是<b>阅读顺序</b>
 * （先行后列，也正是视觉顺序）：4 张 {@code W6H92}（各占 6 列、一行高 92px）正好填满第一行的
 * 24 列，剩下 4 张 {@code W12H308}（各占 12 列、三行高 308px）两两一行、填满两行 ——
 * 首屏因此是 {@code 92 + 16 + 308 + 16 + 308 = 740px}，无空洞。</p>
 *
 * <p><b>为什么不在这儿写坐标</b>：写不了。坐标得配着 {@code w} 一起才对（写重叠了就摆出
 * 两张叠在一起的卡），而 {@code w} <b>只在前端</b>（见 {@code WebDashboardWidgetSize}）。前端哪天把
 * 那一档从 6 列改成 8 列（那样 {@code W6H92} 这个名字本身也得跟着改），这里手写的坐标表
 * 就会<b>静默地</b>摆出重叠的卡片；交给前端的 {@code placeAt} / {@code ensurePlacements}
 * 反而永远自洽 —— 它铺出来的就是改造前浏览器流式排开的那个样子。</p>
 *
 * <p>四张非统计卡取 {@code W12H308}（三行）而不是两行的 {@code W12H200}：曲线与分布图在
 * 200px 里要挤掉卡头与坐标轴，剩下画图的地方太扁；也不是四行的 {@code W12H416} ——
 * 那是为了让首屏不至于一屏只装得下一行卡。<b>改动这里时数组顺序不能乱</b>，
 * 否则用户看到的预置首屏会跟着变。</p>
 *
 * <p>统计卡在二维摆放那一轮从 200px 的档位换成了 92px 的（今天叫 {@code W6H200} →
 * {@code W6H92}）：统计卡是唯一「没有卡头、按内容自然高约 90px」的卡片，
 * 塞在 200px 的格子里下面空 110px。<b>只影响从未保存过的空间</b> ——
 * 存过布局的空间读的是自己那份文档，一张卡的档位都不会被悄悄改掉。</p>
 *
 * <p><b>标题走 {@code titleKey} 而不是 {@code title}</b>：预置布局是服务端造的，
 * 而 {@code title} 属于用户数据、前端不许翻译（{@code AGENTS.md}）。若把「设备总量」
 * 直接写进 {@code title}，英文界面就会看到中文。所以这里给的是 i18n key，
 * 前端翻成当前语言；用户自己改过标题后 {@code title} 优先。</p>
 *
 * <p><b>去掉了两张假数据卡片</b>：原页面的「本月能耗」与「日能耗曲线」读的是
 * {@code dashboard.mock.ts} 的假数据（种子随机数），而 §5.3 的 metric 表里根本没有能耗 ——
 * 一个没有数据源的卡片不该出现在预置布局里（用户第一次编辑时没法把它改成真数据，
 * 只能删掉）。第四张统计卡改用 {@code failures.total}：它是 §5.3 里唯一还没被用到的 metric，
 * 且原页面从来没展示过故障，正好补上。它的口径（「几种」不是「几次」）在编辑器里有专门提示。</p>
 */
public final class WebDashboardPresetLayouts {

    private WebDashboardPresetLayouts() {
    }

    /** 预置布局的版本号：从未保存过。与 {@code WebDashboardLayoutEntity.version} 同义。 */
    public static final long PRESET_VERSION = 0L;

    /**
     * 造一份预置布局。返回的实体 {@code id == null}（没落库），
     * {@code creator} / {@code updater} 也是 null（预置布局没有作者）——
     * codec 对这三者都是「没有就不下发」，所以不需要特殊的编码路径。
     */
    public static WebDashboardLayoutEntity build(String spaceId) {
        WebDashboardLayoutEntity layout = new WebDashboardLayoutEntity();
        layout.spaceId = spaceId;
        layout.version = PRESET_VERSION;
        layout.widgets = List.of(
                // ── 第一行：4 张统计卡，各 6 列，正好 24 列 ──
                stat("w_preset_devices", "设备总量",
                        "devices.total", null),
                stat("w_preset_services", "服务总量",
                        "services.total", null),
                stat("w_preset_alarms", "今日告警",
                        "alarms.today", window(24)),
                // failures.total 必须带窗口（§5.3），且「故障种类数」的正确译法是「故障种类」
                stat("w_preset_failures", "故障种类",
                        "failures.total", window(24)),

                // ── 第二、三行：4 张 M（各 12 列），两两一行 ──
                distribution("w_preset_device_type", "设备类型分布", "deviceType", null),
                distribution("w_preset_service_type", "服务类型分布", "serviceType", null),
                distribution("w_preset_alarm_type", "告警类型分布", "alarmType", window(24)),
                alarmLine("w_preset_alarm_curve", "告警曲线")
        );
        return layout;
    }

    private static WebDashboardWidget stat(String id, String titleKey,
                                       String metric, Map<String, Object> window) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("metric", metric);
        if (window != null) {
            config.put("window", window);
        }
        // W6H92 = 一行高（92px）、6 列。统计卡只给两个矮档位（W6H92 / W12H92），见 WebDashboardWidgetSize
        return widget(id, "stat", titleKey, WebDashboardWidgetSize.W6H92, config);
    }

    private static WebDashboardWidget distribution(String id, String titleKey,
                                                String dimension, Map<String, Object> window) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dimension", dimension);
        if (window != null) {
            config.put("window", window);
        }
        config.put("chart", "pie");
        return widget(id, "distribution", titleKey, WebDashboardWidgetSize.W12H308, config);
    }

    private static WebDashboardWidget alarmLine(String id, String titleKey) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("source", "alarmCount");
        config.put("window", window(24));
        config.put("bucket", "hour");
        return widget(id, "line", titleKey, WebDashboardWidgetSize.W12H308, config);
    }

    /**
     * 最近 N 小时的窗口。**存语义，不存绝对时刻** —— 预置布局要是写成绝对毫秒，
     * 它在下发的那一刻就固化了，用户第二天打开看到的还是昨天的区间。
     *
     * <p>{@code {kind: "last", hours: N}} 由 {@code DashboardWidgetDataService} 在**取数时**按服务端的
     * 「现在」解析成绝对区间（见 {@code DashboardWidgetDataService.windowOf}），前端不需要在调 render 前
     * 做换算；前端读这个字段只是为了显示「最近 24 小时」这类窗口标签。
     * 另一种形态 {@code {kind: "range", from, to}} 才是编辑器里手动选了绝对区间的情形，
     * 那种由前端算成 epoch 毫秒。</p>
     */
    private static Map<String, Object> window(int hours) {
        return Map.of("kind", "last", "hours", hours);
    }

    private static WebDashboardWidget widget(String id, String type, String titleKey,
                                          WebDashboardWidgetSize size, Map<String, Object> config) {
        WebDashboardWidget widget = new WebDashboardWidget();
        widget.id = id;
        widget.type = type;
        // titleKey 而不是 title：见类注释
        widget.titleKey = titleKey;
        // 只给档位名。占几列几行是前端的事（见 WebDashboardWidgetSize），坐标也由前端铺（见类注释）
        widget.size = size.name();
        widget.config = config;
        return widget;
    }
}
