package cc.openxiot.matrix.db.modbus.fd.poll.request.codec;

import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequestField;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusRequestField}（写入字段定义）与 JSON 的互转。
 *
 * <p>字段身份即 {@code registryIndex}（引用点表寄存器），写入值类型/字节序由被引用的寄存器携带。</p>
 *
 * <p>{@code value} 是异构的（位为布尔、寄存器为数值），编码原样写入、解码取原始类型
 * （{@code JsonObject#getValue} 给回 Boolean/Integer/Long/Double），不做归一 ——
 * 归一成 Double 会把「位」变成 0/1 数字，归一成字符串则违背本仓「不把协议语义压成字符串」的口径。</p>
 */
public final class ModbusRequestFieldCodec {

    private ModbusRequestFieldCodec() {
    }

    public static JsonObject encode(ModbusRequestField field) {
        JsonObject o = new JsonObject();
        if (field == null) {
            return o;
        }
        if (field.registryIndex != null) {
            o.put("registryIndex", field.registryIndex);
        }
        if (field.value != null) {
            o.put("value", field.value);
        }
        return o;
    }

    public static JsonArray encode(List<ModbusRequestField> fields) {
        JsonArray arr = new JsonArray();
        if (fields != null) {
            for (ModbusRequestField field : fields) {
                arr.add(encode(field));
            }
        }
        return arr;
    }

    public static ModbusRequestField decode(JsonObject o) {
        if (o == null) {
            return null;
        }
        ModbusRequestField field = new ModbusRequestField();
        field.registryIndex = o.getInteger("registryIndex");
        // 原样取原始类型：`value: 0` 不能因为 codec 而被读成 0.0 或 "0"
        field.value = o.getValue("value");
        return field;
    }

    public static List<ModbusRequestField> decode(JsonArray arr) {
        if (arr == null) {
            return null;
        }
        List<ModbusRequestField> fields = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            fields.add(decode(arr.getJsonObject(i)));
        }
        return fields;
    }
}