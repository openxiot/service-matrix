package cc.openxiot.matrix.api.modbus.history;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次采集「要不要落库、落哪些字段」的判定（规则见 Modbus-History.md 第二节）。
 *
 * <p>纯函数、无状态、不碰数据库：与 {@code ModbusServiceValidator} 同风格 ——
 * 这是这套规则唯一能跑单测的地方（{@code @QuarkusTest} 要连 Mongo）。</p>
 *
 * <p>三条规则，**逐字段**判定：</p>
 * <ol>
 *   <li><b>变位</b>：值 ≠ 基准值 → 按正常样本落一条。开关量靠这条。</li>
 *   <li><b>死区</b>：{@code |值 − 基准值| >= deadband} 才算变。模拟量靠这条 ——
 *       没有死区，缩放后的温度每次都在变（61.5 → 61.6），压缩率会退化成 0。</li>
 *   <li><b>保活打点</b>：距上次落库超过保活周期 → 值没变也强制落一条，用来区分
 *       「值稳定」和「采集挂了」，同时给曲线提供锚点。</li>
 * </ol>
 */
public final class ModbusHistoryPolicy {

    /** {@code values} 为空时的结果（没有可落的东西）。 */
    private static final Decision NOTHING = new Decision(List.of(), List.of());

    private ModbusHistoryPolicy() {
    }

    /**
     * 一次采集的判定结果。
     *
     * @param changedFields   值变了、按正常样本落的字段
     * @param keepaliveFields 值没变、因保活周期到点而落的字段（同一次采集里不会与前者重叠）
     */
    public record Decision(List<String> changedFields, List<String> keepaliveFields) {

        /** 有没有东西要落；false 时调用方应当**一次数据库操作都不做**（绝大多数采集走这条）。 */
        public boolean hasRows() {
            return !changedFields.isEmpty() || !keepaliveFields.isEmpty();
        }
    }

    /**
     * 判定一次采集。
     *
     * @param baseline        上一次落库的「字段 → 值」（{@code ModbusServiceShadow.fields}）；没有影子时传 null
     * @param recordedAt      上一次落库时间；没有影子时传 null
     * @param values          本次采集的「字段 → 值」
     * @param now             本次采集时间
     * @param deadband        死区，仅对数值生效；0 = 精确比较（取绝对值，负数视为同值）
     * @param keepAliveMillis 保活周期（毫秒）；{@code <= 0} = 关闭保活
     */
    public static Decision decide(Map<String, Object> baseline,
                                  Date recordedAt,
                                  Map<String, Object> values,
                                  Date now,
                                  double deadband,
                                  long keepAliveMillis) {
        if (values == null || values.isEmpty()) {
            return NOTHING;
        }
        Map<String, Object> base = baseline == null ? Map.of() : baseline;
        boolean keepAliveDue = keepAliveMillis > 0
                && (recordedAt == null || now.getTime() - recordedAt.getTime() >= keepAliveMillis);

        List<String> changed = new ArrayList<>();
        List<String> keepalive = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            // 基准里没有这个字段 = 首次采集（或定义新增了字段）：算变化，否则这个点位永远落不下第一条
            if (!base.containsKey(field) || !sameValue(base.get(field), value, deadband)) {
                changed.add(field);
            } else if (keepAliveDue) {
                keepalive.add(field);
            }
        }
        return new Decision(List.copyOf(changed), List.copyOf(keepalive));
    }

    /**
     * 值是否算「没变」：数值一律按 {@code doubleValue()} 比 —— 影子从库里读回来可能是
     * {@code Integer} / {@code Long} / {@code Double}，与本次采集算出来的类型未必一致，
     * 按类型比会把「没变」误判成「变了」。非数值（{@code value-list} 的 description、
     * {@code string} 格式）精确比较。
     */
    public static boolean sameValue(Object previous, Object current, double deadband) {
        if (previous instanceof Number a && current instanceof Number b) {
            return Math.abs(a.doubleValue() - b.doubleValue()) <= Math.abs(deadband);
        }
        return Objects.equals(previous, current);
    }
}
