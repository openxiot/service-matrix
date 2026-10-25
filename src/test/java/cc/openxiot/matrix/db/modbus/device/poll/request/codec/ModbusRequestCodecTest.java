package cc.openxiot.matrix.db.modbus.device.poll.request.codec;

import cc.openxiot.matrix.db.modbus.device.poll.request.ModbusRequest;
import cc.openxiot.matrix.db.modbus.device.poll.request.ModbusRequestField;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusRequestCodec} / {@link ModbusRequestFieldCodec} 的 JSON 互转。
 *
 * <p>两条规矩值得单独钉住：**没给的项不出键**（库里不留空值噪音，读方法也不会带着一个
 * {@code fields: []}），以及 {@code value} 的**原始类型不变**（位是布尔、寄存器是数值，
 * 不能被码器归一成 0.0 或 "0"）。</p>
 */
class ModbusRequestCodecTest {

    private static ModbusRequest read() {
        ModbusRequest request = new ModbusRequest();
        request.slaveId = 1;
        request.fc = "03";
        request.start = 0;
        request.quantity = 2;
        return request;
    }

    private static ModbusRequestField field(int index, String name, Integer offset, String format,
                                            String byteOrder, Object value) {
        ModbusRequestField field = new ModbusRequestField();
        field.index = index;
        field.field = name;
        field.offset = offset;
        field.format = format;
        field.byteOrder = byteOrder;
        field.value = value;
        return field;
    }

    @Test
    void readRequestRoundTrips() {
        ModbusRequest request = read();
        JsonObject o = ModbusRequestCodec.encode(request);

        assertEquals(1, o.getInteger("slaveId").intValue());
        assertEquals("03", o.getString("fc"));
        assertEquals(0, o.getInteger("start").intValue());
        assertEquals(2, o.getInteger("quantity").intValue());
        assertFalse(o.containsKey("fields"));   // 读方法没有写字段：不该落一个空数组

        ModbusRequest back = ModbusRequestCodec.decode(o);
        assertEquals(1, back.slaveId.intValue());
        assertEquals("03", back.fc);
        assertEquals(0, back.start.intValue());
        assertEquals(2, back.quantity.intValue());
        assertNull(back.fields);
        assertEquals(o, ModbusRequestCodec.encode(back));
    }

    @Test
    void writeRequestRoundTrips() {
        ModbusRequest request = new ModbusRequest();
        request.slaveId = 4;
        request.fc = "10";
        request.start = 10;
        request.fields = List.of(
                field(1, "运行模式", 0, "uint16", null, 1),
                field(2, "设定温度", 1, "float32", "ABCD", 7.5));

        JsonObject o = ModbusRequestCodec.encode(request);
        assertFalse(o.containsKey("quantity"));   // 写方法的数量由字段推出，定义里不写

        JsonArray fields = o.getJsonArray("fields");
        assertEquals(2, fields.size());
        assertFalse(fields.getJsonObject(0).containsKey("byteOrder"));   // uint16 用不上字节序
        assertEquals("ABCD", fields.getJsonObject(1).getString("byteOrder"));

        ModbusRequest back = ModbusRequestCodec.decode(o);
        assertEquals("10", back.fc);
        assertEquals(2, back.fields.size());
        assertEquals(0, back.fields.get(0).offset.intValue());
        assertEquals(1, back.fields.get(1).offset.intValue());
        assertEquals(o, ModbusRequestCodec.encode(back));
    }

    /** {@code value} 原样进出：位是布尔、寄存器是数值，码器不做归一。 */
    @Test
    void valueKeepsItsOwnType() {
        JsonObject o = ModbusRequestFieldCodec.encode(
                field(1, "开机", 0, "bit", null, Boolean.TRUE));
        assertEquals(Boolean.TRUE, o.getValue("value"));
        assertEquals(Boolean.TRUE, ModbusRequestFieldCodec.decode(o).value);

        // `0` 不能因为过了一层码器而变成 0.0 或 "0"：写帧的取值判定要按值本身判整数
        JsonObject zero = ModbusRequestFieldCodec.encode(
                field(1, "运行模式", 0, "uint16", null, 0));
        assertEquals(0, ((Number) zero.getValue("value")).intValue());
        assertTrue(ModbusRequestFieldCodec.decode(zero).value instanceof Integer);

        JsonObject decimal = ModbusRequestFieldCodec.encode(
                field(1, "设定温度", 0, "float32", "ABCD", 7.5));
        assertEquals(7.5, ((Number) ModbusRequestFieldCodec.decode(decimal).value).doubleValue());
    }

    /** 没有缺省值的字段不出 {@code value} 键：decode 回 null，invoke 时该字段就必须给值。 */
    @Test
    void fieldWithoutDefaultOmitsTheValueKey() {
        JsonObject o = ModbusRequestFieldCodec.encode(
                field(1, "设定温度", 0, "int16", null, null));
        assertFalse(o.containsKey("value"));
        assertNull(ModbusRequestFieldCodec.decode(o).value);
    }

    @Test
    void emptyFieldsEncodeToNoKey() {
        ModbusRequest request = new ModbusRequest();
        request.slaveId = 1;
        request.fc = "06";
        request.start = 0;
        request.fields = List.of();

        assertFalse(ModbusRequestCodec.encode(request).containsKey("fields"));
    }

    /** 空值一路安全：null 进 null 出、空容器不进库，不抛也不生成空壳。 */
    @Test
    void nullsEncodeToEmptyContainers() {
        assertTrue(ModbusRequestCodec.encode((ModbusRequest) null).isEmpty());

        // 空对象解出一份「什么都没配」的定义：各字段 null，由校验器去报「request.slaveId is required」
        ModbusRequest blank = ModbusRequestCodec.decode(new JsonObject());
        assertNull(blank.slaveId);
        assertNull(blank.fc);
        assertNull(blank.start);
        assertNull(blank.quantity);
        assertNull(blank.fields);

        assertTrue(ModbusRequestFieldCodec.encode((ModbusRequestField) null).isEmpty());
        assertTrue(ModbusRequestFieldCodec.encode((List<ModbusRequestField>) null).isEmpty());
        assertTrue(ModbusRequestFieldCodec.encode(List.of()).isEmpty());

        assertNull(ModbusRequestCodec.decode(null));
        assertNull(ModbusRequestFieldCodec.decode((JsonObject) null));
        assertNull(ModbusRequestFieldCodec.decode((JsonArray) null));
        assertTrue(ModbusRequestFieldCodec.decode(new JsonArray()).isEmpty());
    }
}
