package cc.openxiot.matrix.api.dashboard.web.widget;

import java.util.ArrayList;
import java.util.List;

/**
 * 读卡片 {@code config} 的一小组防御性取值器，供两个校验器共用。
 *
 * <p><b>为什么要单独一个类</b>：{@code config} 是 {@code Map<String, Object>}
 * （见 {@code WebDashboardWidget} 的说明），每个字段都要先判类型再取值，而两个校验器都要做这件事。
 * 更要紧的是「什么算『给了』」这个约定必须只有一处定义 —— {@link #str} 把空串与空白串
 * 一律当作没给（编辑器把输入框清空后提交的就是空串），这个约定若在两处各写一遍，
 * 迟早出现「保存时说必填、渲染时说可选」这类自相矛盾的报错。</p>
 *
 * <p>全部返回 {@code null} 表示「没给或类型不对」，**不区分这两种情况**：
 * 需要区分类型错误的地方（如 {@code intVal} 拿到字符串 {@code "24"}）由调用方用
 * {@code config.containsKey} 自行判断，因为多数可选项「没给」是合法缺省。</p>
 */
public final class WebDashboardWidgetConfigs {

    private WebDashboardWidgetConfigs() {
    }

    /** 非空字符串才算给了。 */
    public static String str(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        return s;
    }

    /** JSON 数字在 Jackson 下可能是 Integer / Long / Double，一律按 {@code Number} 收。 */
    public static Integer intVal(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    public static Long longVal(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    /** 字符串数组；非字符串元素直接跳过（该报的错由结构校验负责，这里只求不抛异常）。 */
    public static List<String> strList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = str(item);
                if (text != null) {
                    result.add(text);
                }
            }
        }
        return result;
    }
}
