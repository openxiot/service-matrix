package cc.openxiot.matrix.db.modbus.service.function.codec;

import cc.openxiot.matrix.db.modbus.service.function.ModbusFunction;
import cc.openxiot.matrix.db.modbus.service.function.request.codec.ModbusFunctionRequestCodec;
import cc.openxiot.matrix.db.modbus.service.function.response.codec.ModbusFunctionResponseCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusFunction}（服务里的一个方法）与 JSON 的互转。
 */
public final class ModbusFunctionCodec {

    private ModbusFunctionCodec() {
    }

    public static JsonObject encode(ModbusFunction function) {
        JsonObject o = new JsonObject();
        if (function == null) {
            return o;
        }
        if (function.index != null) {
            o.put("index", function.index);
        }
        if (function.name != null) {
            o.put("name", function.name);
        }
        if (function.request != null) {
            o.put("request", ModbusFunctionRequestCodec.encode(function.request));
        }
        if (function.interval != null) {
            o.put("interval", function.interval);
        }
        if (function.polling != null) {
            o.put("polling", function.polling);
        }
        // 没有应答字段（写方法）就整段不出键：与 request.fields 为空不出键同一约定，
        // 库里因此不会留下 `response: {}` 这种空壳
        if (!ModbusFunction.responseFields(function).isEmpty()) {
            o.put("response", ModbusFunctionResponseCodec.encode(function.response));
        }
        return o;
    }

    public static JsonArray encode(List<ModbusFunction> functions) {
        JsonArray arr = new JsonArray();
        if (functions != null) {
            for (ModbusFunction function : functions) {
                arr.add(encode(function));
            }
        }
        return arr;
    }

    public static ModbusFunction decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusFunction function = new ModbusFunction();
        function.index = o.getInteger("index");
        function.name = o.getString("name");
        function.request = ModbusFunctionRequestCodec.decode(o.getJsonObject("request"));
        function.interval = o.getInteger("interval");
        function.polling = o.getBoolean("polling");
        function.response = ModbusFunctionResponseCodec.decode(o.getJsonObject("response"));
        return function;
    }

    public static List<ModbusFunction> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusFunction> functions = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            functions.add(decode(arr.getJsonObject(i)));
        }
        return functions;
    }
}
