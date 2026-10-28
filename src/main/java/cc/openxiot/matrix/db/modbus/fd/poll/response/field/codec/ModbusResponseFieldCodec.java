package cc.openxiot.matrix.db.modbus.fd.poll.response.field.codec;

import cc.openxiot.matrix.db.modbus.fd.poll.response.field.ModbusResponseField;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusResponseField}（应答字段解析规则）与 JSON 的互转。
 *
 * <p>字段身份即 {@code registryIndex}（引用点表寄存器），解码元数据全部由被引用的寄存器携带。</p>
 */
public final class ModbusResponseFieldCodec {

    private ModbusResponseFieldCodec() {
    }

    public static JsonObject encode(ModbusResponseField field) {
        JsonObject o = new JsonObject();
        if (field == null) {
            return o;
        }
        if (field.registryIndex != null) {
            o.put("registryIndex", field.registryIndex);
        }
        return o;
    }

    public static JsonArray encode(List<ModbusResponseField> fields) {
        JsonArray arr = new JsonArray();
        if (fields != null) {
            for (ModbusResponseField field : fields) {
                arr.add(encode(field));
            }
        }
        return arr;
    }

    public static ModbusResponseField decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusResponseField field = new ModbusResponseField();
        field.registryIndex = o.getInteger("registryIndex");
        return field;
    }

    public static List<ModbusResponseField> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusResponseField> fields = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            fields.add(decode(arr.getJsonObject(i)));
        }
        return fields;
    }
}