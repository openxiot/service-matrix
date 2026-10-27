package cc.openxiot.matrix.db.modbus.fd.poll.codec;

import cc.openxiot.matrix.db.modbus.fd.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.fd.poll.ModbusPollFrom;
import cc.openxiot.matrix.db.modbus.fd.poll.request.codec.ModbusRequestCodec;
import cc.openxiot.matrix.db.modbus.fd.poll.response.codec.ModbusResponseCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusPoll}（服务里的一个方法）与 JSON 的互转。
 *
 * <p>方法已压平回**单类**：没有按 {@code kind} 选子类这一层了。encode 先写来源对象 {@code from}
 * （{@code {type,index,access}}），再写其余共享字段；decode 解出 {@code from} 对象挂到方法上，
 * 不再构造任何一种子类。</p>
 *
 * <p>库里不带 {@code from}、甚至没有裸 {@code index} 的历史文档（判别字出现之前的旧方法）一律容忍：
 * {@code from} 解出来是 null，调用链各自做好 null 安全（轮询/告警/影子都不入表）。
 * 旧文档里可能的裸 {@code index} 不再回迁 —— 这是一次干净的形状变更，不背着旧字段走。</p>
 */
public final class ModbusPollCodec {

    private ModbusPollCodec() {
    }

    public static JsonObject encode(ModbusPoll poll) {
        JsonObject o = new JsonObject();
        if (poll == null) {
            return o;
        }
        // 方法在服务内的序号（身份）：与 from.index（溯源）分开 —— from.index 只指向 config，
        // 不承载身份；缺失容忍（旧文档可能没有，调用链各自兜底）
        if (poll.index != null) {
            o.put("index", poll.index);
        }
        if (poll.from != null) {
            JsonObject from = new JsonObject();
            if (poll.from.type != null) {
                from.put("type", poll.from.type);
            }
            if (poll.from.index != null) {
                from.put("index", poll.from.index);
            }
            if (poll.from.access != null) {
                from.put("access", poll.from.access);
            }
            o.put("from", from);
        }
        if (poll.name != null) {
            o.put("name", poll.name);
        }
        if (poll.request != null) {
            o.put("request", ModbusRequestCodec.encode(poll.request));
        }
        if (poll.interval != null) {
            o.put("interval", poll.interval);
        }
        if (poll.polling != null) {
            o.put("polling", poll.polling);
        }
        // 没有应答字段（写方法）就整段不出键：与 request.fields 为空不出键同一约定，
        // 库里因此不会留下 `response: {}` 这种空壳
        if (!ModbusPoll.responseFields(poll).isEmpty()) {
            o.put("response", ModbusResponseCodec.encode(poll.response));
        }
        return o;
    }

    public static JsonArray encode(List<ModbusPoll> polls) {
        JsonArray arr = new JsonArray();
        if (polls != null) {
            for (ModbusPoll poll : polls) {
                arr.add(encode(poll));
            }
        }
        return arr;
    }

    public static ModbusPoll decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusPoll poll = new ModbusPoll();
        // 来源：解出 {type,index,access} 对象；缺省（旧文档）保持 null，轮询/告警/影子各自兜底
        JsonObject fromObj = o.getJsonObject("from");
        if (fromObj != null) {
            ModbusPollFrom from = new ModbusPollFrom();
            from.type = fromObj.getString("type");
            from.index = fromObj.getInteger("index");
            from.access = fromObj.getString("access");
            poll.from = from;
        }
        // 方法自身的序号（身份）：与 from.index 分开解 —— from.index 只是溯源
        poll.index = o.getInteger("index");
        poll.name = o.getString("name");
        poll.request = ModbusRequestCodec.decode(o.getJsonObject("request"));
        poll.interval = o.getInteger("interval");
        poll.polling = o.getBoolean("polling");
        poll.response = ModbusResponseCodec.decode(o.getJsonObject("response"));
        return poll;
    }

    public static List<ModbusPoll> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusPoll> polls = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            polls.add(decode(arr.getJsonObject(i)));
        }
        return polls;
    }
}