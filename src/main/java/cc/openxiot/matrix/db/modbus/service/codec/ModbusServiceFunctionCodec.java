package cc.openxiot.matrix.db.modbus.service.codec;

import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusServiceFunction}（服务里的一个方法）与 JSON 的互转。
 */
public final class ModbusServiceFunctionCodec {

    private ModbusServiceFunctionCodec() {
    }

    public static JsonObject encode(ModbusServiceFunction function) {
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
            o.put("request", function.request);
        }
        if (function.interval != null) {
            o.put("interval", function.interval);
        }
        o.put("response", ModbusServiceFieldCodec.encode(function.response));
        return o;
    }

    public static JsonArray encode(List<ModbusServiceFunction> functions) {
        JsonArray arr = new JsonArray();
        if (functions != null) {
            for (ModbusServiceFunction function : functions) {
                arr.add(encode(function));
            }
        }
        return arr;
    }

    public static ModbusServiceFunction decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusServiceFunction function = new ModbusServiceFunction();
        function.index = o.getInteger("index");
        function.name = o.getString("name");
        function.request = o.getString("request");
        function.interval = o.getInteger("interval");
        function.response = ModbusServiceFieldCodec.decode(o.getJsonArray("response"));
        return function;
    }

    public static List<ModbusServiceFunction> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusServiceFunction> functions = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            functions.add(decode(arr.getJsonObject(i)));
        }
        return functions;
    }
}
