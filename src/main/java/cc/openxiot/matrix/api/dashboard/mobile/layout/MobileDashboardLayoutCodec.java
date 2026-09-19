package cc.openxiot.matrix.api.dashboard.mobile.layout;

import cc.openxiot.common.person.codec.PersonCodec;
import cc.openxiot.matrix.db.dashboard.DashboardWidget;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardLayoutEntity;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * 移动端布局 → 线上 JSON。**只编不解**：请求体由 Jackson 直接绑到
 * {@code MobileDashboardLayoutDto}，这一层只管把读出来的东西写出去。形状与
 * {@code WebDashboardLayoutCodec} 一致，唯一差别是<b>不编坐标</b> —— 移动端卡片没有
 * {@code x}/{@code y}（竖排堆叠，数组顺序即阅读顺序）。即使实体上意外带着坐标（共享
 * {@link DashboardWidget} 的字段还在），这里也一律不下发，免得移动端 app 以为能寻址。
 *
 * <p><b>预置布局与已存布局走同一个方法</b>：预置布局是一个 {@code id == null} 的
 * {@link MobileDashboardLayoutEntity}（服务层在内存里造的），所以不需要第二套编码路径，
 * {@code id} 本身<b>不下发</b>（布局对外的身份是 {@code spaceId}）。</p>
 *
 * <p>{@code config} 是异构的 {@code Map<String, Object>}，直接交给 {@code new JsonObject(map)}：
 * 里面的值来自 Jackson 反序列化的 JSON，本来就只有 String/Number/Boolean/Map/List/null 这几种。</p>
 */
public final class MobileDashboardLayoutCodec {

    private MobileDashboardLayoutCodec() {
    }

    /** 整份布局：{@code {spaceId, version, widgets, creator, updater}}。 */
    public static JsonObject encode(MobileDashboardLayoutEntity layout) {
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
     * 一张卡片。缺省项**不补空值**（{@code title} 没设就是不出现，前端据此走「用默认标题」），
     * 与 Web 同口径。**不出坐标**：移动端没有位置真值，就算实体里带 {@code x}/{@code y} 也不发。
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
        Map<String, Object> config = widget.config;
        o.put("config", config == null ? new JsonObject() : new JsonObject(config));
        return o;
    }
}