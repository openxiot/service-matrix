package cc.openxiot.matrix.api.dashboard.mobile.layout;

import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.mobile.widget.MobileDashboardWidgetSize;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 空间从未配置过时下发的**预置移动端布局**：一份竖屏从上到下的有序卡片。
 *
 * <p><b>纯函数，不落库</b>（与 {@code WebDashboardPresetLayouts} 同法）：布局是项目共享一份，
 * 若走「首次 GET 时补一条文档」，两个成员同时打开就会并发建两条。落库只发生在 {@code PUT}，
 * 所以预置状态下 {@code version} 恒为 0，第一次保存提交 {@code version: 0} 即可。</p>
 *
 * <p><b>顺序就是视觉顺序</b>：竖屏一列从上往下排。手机没有 Web 那套网格，位置真值就是数组顺序，
 * 所以这里<b>给坐标没有意义</b>，各张卡只给一个宽幅档位：
 * <ul>
 *   <li>两张 {@code HALF} 统计卡并列（一整行的两半）：{@code devices.total} / {@code fds.total}
 *       —— 两块小数字并列很常见，省屏幕；</li>
 *   <li>再两张 {@code HALF} 统计卡：{@code alarms.today}（带窗口）/ {@code failures.total}（带窗口）；</li>
 *   <li>整宽一张设备类型分布 {@code deviceType}（FULL）；</li>
 *   <li>整宽一条告警曲线 {@code alarmCount}（FULL）收尾。</li>
 * </ul>
 * 曲线/分布图必须 {@code FULL}（{@code HALF} 只对 stat 开放，半宽画不开）。</p>
 *
 * <p><b>标题走 {@code titleKey} 而不是 {@code title}</b>：预置布局是服务端造的，
 * 而 {@code title} 属于用户数据、前端不许翻译。这给的是 i18n key，前端翻成当前语言；
 * 用户自己改过标题后 {@code title} 优先。</p>
 */
public final class MobileDashboardPresetLayouts {

    private MobileDashboardPresetLayouts() {
    }

    /** 预置布局的版本号：从未保存过。 */
    public static final long PRESET_VERSION = 0L;

    /**
     * 造一份预置布局。返回的实体 {@code id == null}（没落库），
     * {@code creator} / {@code updater} 也是 null —— codec 对这三者都是「没有就不下发」。
     */
    public static MobileDashboardLayoutEntity build(String spaceId) {
        MobileDashboardLayoutEntity layout = new MobileDashboardLayoutEntity();
        layout.spaceId = spaceId;
        layout.version = PRESET_VERSION;
        layout.widgets = List.of(
                stat("m_preset_devices", "设备总量", "devices.total", null),
                stat("m_preset_fds", "现场设备总量", "fds.total", null),
                stat("m_preset_alarms", "今日告警", "alarms.today", window(24)),
                // failures.total 必须带窗口，且「故障种类数」的正确译法是「故障种类」
                stat("m_preset_failures", "故障种类", "failures.total", window(24)),
                distribution("m_preset_device_type", "设备类型分布", "deviceType", null),
                alarmLine("m_preset_alarm_curve", "告警曲线")
        );
        return layout;
    }

    private static MobileDashboardWidget stat(String id, String titleKey,
                                        String metric, Map<String, Object> window) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("metric", metric);
        if (window != null) {
            config.put("window", window);
        }
        // 统计卡用半宽：两块小数字并列（FULL/HALF 里 HALF 只对 stat 开放）
        return widget(id, "stat", titleKey, MobileDashboardWidgetSize.HALF, config);
    }

    private static MobileDashboardWidget distribution(String id, String titleKey,
                                                String dimension, Map<String, Object> window) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dimension", dimension);
        if (window != null) {
            config.put("window", window);
        }
        config.put("chart", "pie");
        return widget(id, "distribution", titleKey, MobileDashboardWidgetSize.FULL, config);
    }

    private static MobileDashboardWidget alarmLine(String id, String titleKey) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("source", "alarmCount");
        config.put("window", window(24));
        config.put("bucket", "hour");
        return widget(id, "line", titleKey, MobileDashboardWidgetSize.FULL, config);
    }

    /** 最近 N 小时的窗口。**存语义，不存绝对时刻** —— 由引擎在取数时按服务端「现在」解析。 */
    private static Map<String, Object> window(int hours) {
        return Map.of("kind", "last", "hours", hours);
    }

    private static MobileDashboardWidget widget(String id, String type, String titleKey,
                                          MobileDashboardWidgetSize size, Map<String, Object> config) {
        MobileDashboardWidget widget = new MobileDashboardWidget();
        widget.id = id;
        widget.type = type;
        widget.titleKey = titleKey;
        widget.size = size.name();
        widget.config = config;
        return widget;
    }
}