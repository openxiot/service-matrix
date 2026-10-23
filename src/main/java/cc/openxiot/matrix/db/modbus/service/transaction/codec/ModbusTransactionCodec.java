package cc.openxiot.matrix.db.modbus.service.transaction.codec;

import cc.openxiot.matrix.db.modbus.service.transaction.ModbusTransaction;
import cc.openxiot.matrix.db.modbus.service.transaction.ModbusTransactionFrom;
import cc.openxiot.matrix.db.modbus.service.transaction.request.codec.ModbusTransactionRequestCodec;
import cc.openxiot.matrix.db.modbus.service.transaction.response.codec.ModbusTransactionResponseCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusTransaction}（服务里的一个方法）与 JSON 的互转。
 *
 * <p>方法已压平回**单类**：没有按 {@code kind} 选子类这一层了。encode 先写来源对象 {@code from}
 * （{@code {type,index,access}}），再写其余共享字段；decode 解出 {@code from} 对象挂到方法上，
 * 不再构造任何一种子类。</p>
 *
 * <p>库里不带 {@code from}、甚至没有裸 {@code index} 的历史文档（判别字出现之前的旧方法）一律容忍：
 * {@code from} 解出来是 null，调用链各自做好 null 安全（轮询/告警/影子都不入表）。
 * 旧文档里可能的裸 {@code index} 不再回迁 —— 这是一次干净的形状变更，不背着旧字段走。</p>
 */
public final class ModbusTransactionCodec {

    private ModbusTransactionCodec() {
    }

    public static JsonObject encode(ModbusTransaction function) {
        JsonObject o = new JsonObject();
        if (function == null) {
            return o;
        }
        // 方法在服务内的序号（身份）：与 from.index（溯源）分开 —— from.index 只指向 config，
        // 不承载身份；缺失容忍（旧文档可能没有，调用链各自兜底）
        if (function.index != null) {
            o.put("index", function.index);
        }
        if (function.from != null) {
            JsonObject from = new JsonObject();
            if (function.from.type != null) {
                from.put("type", function.from.type);
            }
            if (function.from.index != null) {
                from.put("index", function.from.index);
            }
            if (function.from.access != null) {
                from.put("access", function.from.access);
            }
            o.put("from", from);
        }
        if (function.name != null) {
            o.put("name", function.name);
        }
        if (function.request != null) {
            o.put("request", ModbusTransactionRequestCodec.encode(function.request));
        }
        if (function.interval != null) {
            o.put("interval", function.interval);
        }
        if (function.polling != null) {
            o.put("polling", function.polling);
        }
        // 没有应答字段（写方法）就整段不出键：与 request.fields 为空不出键同一约定，
        // 库里因此不会留下 `response: {}` 这种空壳
        if (!ModbusTransaction.responseFields(function).isEmpty()) {
            o.put("response", ModbusTransactionResponseCodec.encode(function.response));
        }
        return o;
    }

    public static JsonArray encode(List<ModbusTransaction> functions) {
        JsonArray arr = new JsonArray();
        if (functions != null) {
            for (ModbusTransaction function : functions) {
                arr.add(encode(function));
            }
        }
        return arr;
    }

    public static ModbusTransaction decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusTransaction function = new ModbusTransaction();
        // 来源：解出 {type,index,access} 对象；缺省（旧文档）保持 null，轮询/告警/影子各自兜底
        JsonObject fromObj = o.getJsonObject("from");
        if (fromObj != null) {
            ModbusTransactionFrom from = new ModbusTransactionFrom();
            from.type = fromObj.getString("type");
            from.index = fromObj.getInteger("index");
            from.access = fromObj.getString("access");
            function.from = from;
        }
        // 方法自身的序号（身份）：与 from.index 分开解 —— from.index 只是溯源
        function.index = o.getInteger("index");
        function.name = o.getString("name");
        function.request = ModbusTransactionRequestCodec.decode(o.getJsonObject("request"));
        function.interval = o.getInteger("interval");
        function.polling = o.getBoolean("polling");
        function.response = ModbusTransactionResponseCodec.decode(o.getJsonObject("response"));
        return function;
    }

    public static List<ModbusTransaction> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusTransaction> functions = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            functions.add(decode(arr.getJsonObject(i)));
        }
        return functions;
    }
}