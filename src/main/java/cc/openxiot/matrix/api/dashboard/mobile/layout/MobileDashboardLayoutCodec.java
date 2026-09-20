package cc.openxiot.matrix.api.dashboard.mobile.layout;

import cc.openxiot.common.person.codec.PersonCodec;
import cc.openxiot.matrix.api.dashboard.mobile.widget.MobileDashboardWidgetSides;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.mobile.widget.MobileDashboardWidgetSide;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * 移动端布局 → 线上 JSON。**只编不解**：请求体由 Jackson 直接绑到
 * {@code MobileDashboardLayoutDto}，这一层只管把读出来的东西写出去。形状与
 * {@code WebDashboardLayoutCodec} 一致，差别只在卡片上：移动端<b>没有坐标</b>（竖排堆叠，
 * 数组顺序即阅读顺序），换成了半宽卡的 {@code side}。
 *
 * <p><b>下发的是「有效半格」而不只是用户写下的那个</b>：{@link MobileDashboardWidgetSides#effective}
 * 把整份列表先算一遍，所以 {@code GET} 回来的每张 HALF 卡都带显式 {@code side}
 * —— 缺省值由服务端推导，<b>对所有客户端自描述</b>。这不只是方便：客户端若自己推，
 * 拖走一张之后同伴会被重新推导，于是从右半格滑到左半格（用户报的「横向补位」），
 * 而且松手那一刻才翻面。{@code FULL} 卡永远不发（它没有半格）。</p>
 *
 * <p><b>预置布局与已存布局走同一个方法</b>：预置布局是一个 {@code id == null} 的
 * {@link MobileDashboardLayoutEntity}（服务层在内存里造的），所以不需要第二套编码路径，
 * {@code id} 本身<b>不下发</b>（布局对外的身份是 {@code spaceId}）。预置的那四张 HALF 统计卡
 * 没写 {@code side}，推导出来正好是 {@code L,R,L,R}（两行并排），与改造前的长相一致。</p>
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

    public static JsonArray encodeWidgets(List<MobileDashboardWidget> widgets) {
        JsonArray arr = new JsonArray();
        if (widgets == null) {
            return arr;
        }
        // 半格要先按整份列表算（状态机要上下文：当前这一行的右半格空不空），再逐张编
        List<MobileDashboardWidgetSide> sides = MobileDashboardWidgetSides.effective(widgets);
        for (int i = 0; i < widgets.size(); i++) {
            arr.add(encodeWidget(widgets.get(i), sides.get(i)));
        }
        return arr;
    }

    /**
     * 单张卡片的编码：**没有上下文，所以不下发 {@code side}**（推不出有效半格）。
     * 整份列表走 {@link #encodeWidgets}；这个重载只给「单独编一张」的调用方，
     * 比如测试里拼一个保存请求体（客户端提交时本来就不写 side，正好同形）。
     */
    public static JsonObject encodeWidget(MobileDashboardWidget widget) {
        return encodeWidget(widget, null);
    }

    /**
     * 一张卡片。缺省项**不补空值**（{@code title} 没设就是不出现，前端据此走「用默认标题」），
     * 与 Web 同口径。{@code side} 由调用方按整份列表算好传进来（{@code FULL} 卡传 {@code null}）：
     * 传 {@code null} 就是不出现 —— <b>不补一个默认半格</b>，否则「FULL 卡带 side」这类
     * 本来无害的东西会从线上格式里长出来。
     */
    public static JsonObject encodeWidget(MobileDashboardWidget widget, MobileDashboardWidgetSide side) {
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
        if (side != null) {
            o.put("side", side.name());
        }
        Map<String, Object> config = widget.config;
        o.put("config", config == null ? new JsonObject() : new JsonObject(config));
        return o;
    }
}