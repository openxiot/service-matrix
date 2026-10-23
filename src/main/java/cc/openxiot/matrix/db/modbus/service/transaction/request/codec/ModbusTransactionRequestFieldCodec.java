package cc.openxiot.matrix.db.modbus.service.transaction.request.codec;

import cc.openxiot.matrix.db.modbus.service.transaction.request.ModbusTransactionRequestField;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusTransactionRequestField}（写入字段定义）与 JSON 的互转。
 *
 * <p>{@code value} 是异构的（位为布尔、寄存器为数值），编码原样写入、解码取原始类型
 * （{@code JsonObject#getValue} 给回 Boolean/Integer/Long/Double），不做归一 ——
 * 归一成 Double 会把「位」变成 0/1 数字，归一成字符串则违背本仓「不把协议语义压成字符串」的口径。</p>
 */
public final class ModbusTransactionRequestFieldCodec {

    private ModbusTransactionRequestFieldCodec() {
    }

    public static JsonObject encode(ModbusTransactionRequestField field) {
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
        if (field.offset != null) {
            o.put("offset", field.offset);
        }
        if (field.format != null) {
            o.put("format", field.format);
        }
        if (field.byteOrder != null) {
            o.put("byteOrder", field.byteOrder);
        }
        if (field.value != null) {
            o.put("value", field.value);
        }
        return o;
    }

    public static JsonArray encode(List<ModbusTransactionRequestField> fields) {
        JsonArray arr = new JsonArray();
        if (fields != null) {
            for (ModbusTransactionRequestField field : fields) {
                arr.add(encode(field));
            }
        }
        return arr;
    }

    public static ModbusTransactionRequestField decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusTransactionRequestField field = new ModbusTransactionRequestField();
        field.index = o.getInteger("index");
        field.field = o.getString("field");
        field.offset = o.getInteger("offset");
        field.format = o.getString("format");
        field.byteOrder = o.getString("byteOrder");
        // 原样取原始类型：`value: 0` 不能因为 codec 而被读成 0.0 或 "0"
        field.value = o.getValue("value");
        return field;
    }

    public static List<ModbusTransactionRequestField> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusTransactionRequestField> fields = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            fields.add(decode(arr.getJsonObject(i)));
        }
        return fields;
    }
}
