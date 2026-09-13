package cc.openxiot.matrix.db.codec;

import cc.openxiot.common.person.Person;
import io.vertx.core.json.JsonObject;

/**
 * {@link Person}（创建者 / 更新者）与 JSON 的互转：只出 id / name / timestamp，
 * 时间戳按毫秒数输出（与前端 {@code new Date(ms)} 同口径）。
 *
 * <p>各实体的 Codec 都要写 creator / updater，形状必须一致，故提到这里共用。</p>
 */
public final class PersonCodec {

    private PersonCodec() {
    }

    public static JsonObject encode(Person p) {
        JsonObject o = new JsonObject();
        if (p == null) {
            return o;
        }
        if (p.id != null) {
            o.put("id", p.id);
        }
        if (p.name != null) {
            o.put("name", p.name);
        }
        if (p.timestamp != null) {
            o.put("timestamp", p.timestamp.getTime());
        }
        return o;
    }
}
