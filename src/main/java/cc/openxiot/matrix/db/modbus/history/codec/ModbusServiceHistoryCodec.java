package cc.openxiot.matrix.db.modbus.history.codec;

import cc.openxiot.matrix.api.modbus.history.ModbusHistoryWindow;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistory;
import io.vertx.core.json.JsonObject;

/**
 * 历史样本 → JSON（历史区间接口用），两种形态：
 *
 * <ul>
 *   <li>{@link #encode(ModbusServiceHistory)}：原始样本 {@code {at, value, keepalive}}。</li>
 *   <li>{@link #encode(ModbusHistoryWindow.Bucket)}：降采样桶
 *       {@code {at, until, count, first, last, min, max, avg}}。</li>
 * </ul>
 *
 * <p>两种形态共用 {@code at} 作横轴坐标，前端按响应里的 {@code downsampled} 决定画哪一种
 * （见 {@code Modbus-History.md} 第五节）。桶里 {@code min} / {@code max} / {@code avg} 在非数值点位
 * （{@code value-list} 的 description、字符串）上**为 null**：那类值没有大小可言，折叠后只剩
 * {@code first} / {@code last}（与 {@code ModbusHistoryPolicy} 里「非数值精确比较」同一口径）。</p>
 *
 * <p>只出不进：历史是采集写进去的，不由请求体决定，所以没有 decode。</p>
 */
public final class ModbusServiceHistoryCodec {

    private ModbusServiceHistoryCodec() {
    }

    public static JsonObject encode(ModbusServiceHistory sample) {
        JsonObject o = new JsonObject();
        if (sample == null) {
            return o;
        }
        if (sample.at != null) {
            o.put("at", sample.at.getTime());
        }
        if (sample.value != null) {
            o.put("value", sample.value);
        }
        // 缺省即 false（正常样本），只在是保活打点时才出键
        if (Boolean.TRUE.equals(sample.keepalive)) {
            o.put("keepalive", true);
        }
        return o;
    }

    public static JsonObject encode(ModbusHistoryWindow.Bucket bucket) {
        JsonObject o = new JsonObject();
        if (bucket == null) {
            return o;
        }
        o.put("at", bucket.at());
        o.put("until", bucket.until());
        o.put("count", bucket.count());
        o.put("first", bucket.first());
        o.put("last", bucket.last());
        o.put("min", bucket.min());
        o.put("max", bucket.max());
        o.put("avg", bucket.avg());
        return o;
    }
}
