package cc.openxiot.matrix.api.card.fd;

import cc.openxiot.common.person.codec.PersonCodec;
import cc.openxiot.matrix.db.card.fd.FdCard;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * 现场设备卡片配置 → 线上 JSON。**只编不解**：请求体由 Jackson 直接绑到
 * {@code FdCardDto}，这一层只管把读出来的东西写出去。
 *
 * <p><b>没有配置与配置成空必须分得开</b>：{@code customized} 是文档存不存在的信号。
 * 缺席时只出 {@code {fdid, customized:false}}，不补一个空的 {@code registers} ——
 * 补了前端就分不清「没配过（按缺省 4 个摆）」和「用户清空了（什么都不摆）」。
 * 与 {@code DeviceCardCodec} 同一条口径。</p>
 */
public final class FdCardCodec {

    private FdCardCodec() {
    }

    /** 一批已配置的现场设备卡片（列表接口）。没配过的不在这里，前端按缺省规则摆。 */
    public static JsonArray encodeList(List<FdCard> cards) {
        JsonArray arr = new JsonArray();
        if (cards == null) {
            return arr;
        }
        for (FdCard card : cards) {
            if (card != null) {
                arr.add(encode(card));
            }
        }
        return arr;
    }

    /** 一份已存的配置：{@code {fdid, customized:true, registers, creator?, updater?}}。 */
    public static JsonObject encode(FdCard card) {
        JsonObject o = new JsonObject();
        if (card == null) {
            return o;
        }
        o.put("fdid", card.fdid);
        o.put("customized", true);
        JsonArray registers = new JsonArray();
        if (card.registers != null) {
            card.registers.forEach(registers::add);
        }
        o.put("registers", registers);
        if (card.creator != null) {
            o.put("creator", PersonCodec.encode(card.creator));
        }
        if (card.updater != null) {
            o.put("updater", PersonCodec.encode(card.updater));
        }
        return o;
    }

    /** 「这台现场设备没有配置」：前端据此走缺省规则，而不是「什么都不显示」。 */
    public static JsonObject encodeAbsent(String fdid) {
        JsonObject o = new JsonObject();
        o.put("fdid", fdid);
        o.put("customized", false);
        return o;
    }
}
