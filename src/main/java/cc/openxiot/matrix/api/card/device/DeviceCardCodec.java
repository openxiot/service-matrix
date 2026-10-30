package cc.openxiot.matrix.api.card.device;

import cc.openxiot.common.person.codec.PersonCodec;
import cc.openxiot.matrix.db.card.device.DeviceCard;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * 设备卡片配置 → 线上 JSON。**只编不解**：请求体由 Jackson 直接绑到
 * {@code DeviceCardDto}，这一层只管把读出来的东西写出去。
 *
 * <p><b>没有配置与配置成空必须分得开</b>：{@code customized} 是文档存不存在的信号。
 * 缺席时只出 {@code {did, customized:false}}，<b>不补一个空的 {@code services}</b> ——
 * 补了前端就分不清「没配过（按缺省 2×3 摆）」和「用户清空了（什么都不摆）」。
 * 与 {@code WebDashboardLayoutCodec} 不补空值同一条口径。</p>
 *
 * <p>{@code spaceId} 也不下发：它是服务端按项目列表用的派生字段，前端按 did 认卡片，
 * 用不上它。</p>
 */
public final class DeviceCardCodec {

    private DeviceCardCodec() {
    }

    /** 一批已配置的卡片（列表接口）。没配过的设备不在这里，前端按缺省规则摆。 */
    public static JsonArray encodeList(List<DeviceCard> cards) {
        JsonArray arr = new JsonArray();
        if (cards == null) {
            return arr;
        }
        for (DeviceCard card : cards) {
            if (card != null) {
                arr.add(encode(card));
            }
        }
        return arr;
    }

    /** 一份已存的配置：{@code {did, customized:true, services, creator?, updater?}}。 */
    public static JsonObject encode(DeviceCard card) {
        JsonObject o = new JsonObject();
        if (card == null) {
            return o;
        }
        o.put("did", card.did);
        o.put("customized", true);
        o.put("services", encodeServices(card.services));
        if (card.creator != null) {
            o.put("creator", PersonCodec.encode(card.creator));
        }
        if (card.updater != null) {
            o.put("updater", PersonCodec.encode(card.updater));
        }
        return o;
    }

    /** 「这台设备没有配置」：前端据此走缺省规则，而不是「什么都不显示」。 */
    public static JsonObject encodeAbsent(String did) {
        JsonObject o = new JsonObject();
        o.put("did", did);
        o.put("customized", false);
        return o;
    }

    private static JsonArray encodeServices(List<DeviceCard.ServiceSelection> services) {
        JsonArray arr = new JsonArray();
        if (services == null) {
            return arr;
        }
        for (DeviceCard.ServiceSelection service : services) {
            if (service == null) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.put("siid", service.siid);
            JsonArray piids = new JsonArray();
            if (service.piids != null) {
                service.piids.forEach(piids::add);
            }
            o.put("piids", piids);
            arr.add(o);
        }
        return arr;
    }
}
