package cc.openxiot.matrix.db.modbus.service.polling.response.field.codec;

import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusTransactionResponseField;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusTransactionResponseField}（应答字段解析规则）与 JSON 的互转。
 *
 * <p>带连字符的 {@code value-list} / {@code bit-list} 在实体里叫 {@code valueList} / {@code bitList}，
 * 编解码按示例文件的口径写回原名。</p>
 */
public final class ModbusTransactionResponseFieldCodec {

    private ModbusTransactionResponseFieldCodec() {
    }

    public static JsonObject encode(ModbusTransactionResponseField field) {
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
            o.put("value-list", ModbusTransactionResponseFieldValueCodec.encode(field.valueList));
        }
        if (field.bitList != null) {
            o.put("bit-list", ModbusTransactionResponseFieldBitCodec.encode(field.bitList));
        }
        return o;
    }

    public static JsonArray encode(List<ModbusTransactionResponseField> fields) {
        JsonArray arr = new JsonArray();
        if (fields != null) {
            for (ModbusTransactionResponseField field : fields) {
                arr.add(encode(field));
            }
        }
        return arr;
    }

    public static ModbusTransactionResponseField decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusTransactionResponseField field = new ModbusTransactionResponseField();
        field.index = o.getInteger("index");
        field.field = o.getString("field");
        field.bytes = o.getInteger("bytes");
        field.format = o.getString("format");
        field.byteOrder = o.getString("byteOrder");
        field.scale = o.getDouble("scale");
        field.unit = o.getString("unit");
        field.valueList = ModbusTransactionResponseFieldValueCodec.decode(o.getJsonArray("value-list"));
        field.bitList = ModbusTransactionResponseFieldBitCodec.decode(o.getJsonArray("bit-list"));
        return field;
    }

    public static List<ModbusTransactionResponseField> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusTransactionResponseField> fields = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            fields.add(decode(arr.getJsonObject(i)));
        }
        return fields;
    }
}
