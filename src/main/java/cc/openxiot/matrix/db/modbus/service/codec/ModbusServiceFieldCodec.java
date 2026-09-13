package cc.openxiot.matrix.db.modbus.service.codec;

import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusServiceField}（应答字段解析规则）与 JSON 的互转。
 *
 * <p>带连字符的 {@code value-list} / {@code bit-list} 在实体里叫 {@code valueList} / {@code bitList}，
 * 编解码按示例文件的口径写回原名。</p>
 */
public final class ModbusServiceFieldCodec {

    private ModbusServiceFieldCodec() {
    }

    public static JsonObject encode(ModbusServiceField field) {
        JsonObject o = new JsonObject();
        if (field == null) {
            return o;
        }
        if (field.index != null) {
            o.put("index", field.index);
        }
        if (field.field != null) {
            o.put("field", field.field);
        }
        if (field.bytes != null) {
            o.put("bytes", field.bytes);
        }
        if (field.format != null) {
            o.put("format", field.format);
        }
        if (field.byteOrder != null) {
            o.put("byteOrder", field.byteOrder);
        }
        if (field.scale != null) {
            o.put("scale", field.scale);
        }
        if (field.unit != null) {
            o.put("unit", field.unit);
        }
        if (field.valueList != null) {
            o.put("value-list", ModbusServiceFieldValueCodec.encode(field.valueList));
        }
        if (field.bitList != null) {
            o.put("bit-list", ModbusServiceFieldBitCodec.encode(field.bitList));
        }
        return o;
    }

    public static JsonArray encode(List<ModbusServiceField> fields) {
        JsonArray arr = new JsonArray();
        if (fields != null) {
            for (ModbusServiceField field : fields) {
                arr.add(encode(field));
            }
        }
        return arr;
    }

    public static ModbusServiceField decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusServiceField field = new ModbusServiceField();
        field.index = o.getInteger("index");
        field.field = o.getString("field");
        field.bytes = o.getInteger("bytes");
        field.format = o.getString("format");
        field.byteOrder = o.getString("byteOrder");
        field.scale = o.getDouble("scale");
        field.unit = o.getString("unit");
        field.valueList = ModbusServiceFieldValueCodec.decode(o.getJsonArray("value-list"));
        field.bitList = ModbusServiceFieldBitCodec.decode(o.getJsonArray("bit-list"));
        return field;
    }

    public static List<ModbusServiceField> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusServiceField> fields = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            fields.add(decode(arr.getJsonObject(i)));
        }
        return fields;
    }
}
