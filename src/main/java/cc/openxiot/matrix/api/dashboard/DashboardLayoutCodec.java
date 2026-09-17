package cc.openxiot.matrix.api.dashboard;

import cc.openxiot.common.person.codec.PersonCodec;
import cc.openxiot.matrix.db.dashboard.DashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.DashboardWidget;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * 布局 → 线上 JSON。**只编不解**：请求体由 Jackson 直接绑到
 * {@code DashboardLayoutDto}，这一层只管把读出来的东西写出去。
 *
 * <p><b>预置布局与已存布局走同一个方法</b>：预置布局是一个 {@code id == null} 的
 * {@link DashboardLayoutEntity}（服务层在内存里造的），所以这里不需要第二套编码路径。
 * {@code id} 本身<b>不下发</b> —— 布局对外的身份就是 {@code spaceId}，
 * 暴露一个前端用不上的 ObjectId 只会让人以为它能拿来寻址。</p>
 *
 * <p>{@code config} 是异构的 {@code Map<String, Object>}，直接交给
 * {@code new JsonObject(map)}：里面的值来自 Jackson 反序列化的 JSON，本来就只有
 * String / Number / Boolean / Map / List / null 这几种，不会再遇到需要特殊处理的类型。
 * （落库侧的 codec 兜底见 {@code ObjectPropertyCodecProvider}。）</p>
 *
 * <p><b>卡片不带坐标</b>：{@code widgets} 的数组顺序就是屏幕顺序，从前端读出来什么样、
 * 写回去就是什么样，这里不做任何排序或补位 —— 顺序是用户的意图，不是可推导的派生物。</p>
 */
public final class DashboardLayoutCodec {

    private DashboardLayoutCodec() {
    }

    /** 整份布局：{@code {spaceId, version, widgets, creator, updater}}。 */
    public static JsonObject encode(DashboardLayoutEntity layout) {
        JsonObject o = new JsonObject();
        if (layout == null) {
            return o;
        }
        if (layout.spaceId != null) {
            o.put("spaceId", layout.spaceId);
        }
        o.put("version", layout.version);
        o.put("widgets", encodeWidgets(layout.widgets));
        if (layout.creator != null) {
            o.put("creator", PersonCodec.encode(layout.creator));
        }
        if (layout.updater != null) {
            o.put("updater", PersonCodec.encode(layout.updater));
        }
        return o;
    }

    public static JsonArray encodeWidgets(List<DashboardWidget> widgets) {
        JsonArray arr = new JsonArray();
        if (widgets == null) {
            return arr;
        }
        for (DashboardWidget widget : widgets) {
            arr.add(encodeWidget(widget));
        }
        return arr;
    }

    /**
     * 一张卡片。缺省项**不补空值**：{@code title} 没设就是不出现（前端据此走「用默认标题」），
     * {@code refresh} 没设就是不出现（走默认刷新策略），与 {@code ModbusServiceFieldCodec}
     * 对 {@code value-list} / {@code bit-list} 的处理同口径 —— 补成空串/0 会让「没设」和
     * 「设成了空」再也分不开。
     */
    public static JsonObject encodeWidget(DashboardWidget widget) {
        JsonObject o = new JsonObject();
        if (widget == null) {
            return o;
        }
        if (widget.id != null) {
            o.put("id", widget.id);
        }
        if (widget.type != null) {
            o.put("type", widget.type);
        }
        if (widget.title != null) {
            o.put("title", widget.title);
        }
        if (widget.titleKey != null) {
            o.put("titleKey", widget.titleKey);
        }
        if (widget.size != null) {
            o.put("size", widget.size);
        }
        if (widget.refresh != null) {
            o.put("refresh", widget.refresh);
        }
        Map<String, Object> config = widget.config;
        o.put("config", config == null ? new JsonObject() : new JsonObject(config));
        return o;
    }
}
