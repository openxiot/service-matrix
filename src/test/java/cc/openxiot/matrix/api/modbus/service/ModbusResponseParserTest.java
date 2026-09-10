package cc.openxiot.matrix.api.modbus.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldValue;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Modbus 应答帧按字段解析的纯单测：期望帧（含 CRC16）由独立实现算出，与本实现的 0xA001/0xFFFF 口径互校。
 */
class ModbusResponseParserTest {

    private static ModbusServiceField field(int index, String name, int bytes, String format, String byteOrder) {
        ModbusServiceField field = new ModbusServiceField();
        field.index = index;
        field.field = name;
        field.bytes = bytes;
        field.format = format;
        field.byteOrder = byteOrder;
        return field;
    }

    private static ModbusServiceFieldValue value(long value, String description) {
        ModbusServiceFieldValue entry = new ModbusServiceFieldValue();
        entry.value = value;
        entry.description = description;
        return entry;
    }

    @Test
    void decodeSingleUint16() {
        JsonObject values = ModbusResponseParser.parse(
                List.of(field(1, "温度", 2, "uint16", "ABCD")), "0103020064B9AF");

        assertEquals(100, values.getInteger("温度"));
    }

    @Test
    void decodeSplitsDataAreaByFieldIndex() {
        // 3 个寄存器：0064 + 000000C8 → 两个字段（uint16 + uint32），偏移按 bytes 累加
        JsonObject values = ModbusResponseParser.parse(
                List.of(field(1, "温度", 2, "uint16", "ABCD"), field(2, "累计流量", 4, "uint32", "ABCD")),
                "0103060064000000C8512B");

        assertEquals(100, values.getInteger("温度"));
        assertEquals(200L, values.getLong("累计流量"));
    }

    @Test
    void decodeIgnoresFieldOrderInListAndUsesIndex() {
        // 列表顺序颠倒，偏移仍按 index 升序累加
        JsonObject values = ModbusResponseParser.parse(
                List.of(field(2, "累计流量", 4, "uint32", "ABCD"), field(1, "温度", 2, "uint16", "ABCD")),
                "0103060064000000C8512B");

        assertEquals(100, values.getInteger("温度"));
        assertEquals(200L, values.getLong("累计流量"));
    }

    @Test
    void decodeUint8WithValueList() {
        ModbusServiceField on = field(1, "开关状态", 1, "uint8", null);
        on.valueList = List.of(value(0, "ON"), value(1, "OFF"));

        assertEquals("ON", ModbusResponseParser.parse(List.of(on), "01030100F048").getString("开关状态"));
        assertEquals("OFF", ModbusResponseParser.parse(List.of(on), "010301013188").getString("开关状态"));
    }

    @Test
    void decodeValueListMissFallsBackToNumber() {
        ModbusServiceField on = field(1, "开关状态", 1, "uint8", null);
        on.valueList = List.of(value(9, "未知"));

        assertEquals(1, ModbusResponseParser.parse(List.of(on), "010301013188").getInteger("开关状态"));
    }

    @Test
    void decodeAppliesScaleUnlessOne() {
        ModbusServiceField scaled = field(1, "温度", 2, "int16", "ABCD");
        scaled.scale = 0.5;
        assertEquals(61.5, ModbusResponseParser.parse(List.of(scaled), "010302007BF867").getDouble("温度"));

        ModbusServiceField unscaled = field(1, "温度", 2, "int16", "ABCD");
        unscaled.scale = 1.0;
        assertEquals(123, ModbusResponseParser.parse(List.of(unscaled), "010302007BF867").getInteger("温度"));
    }

    @Test
    void decodeInt16AndFloat32ByteOrders() {
        assertEquals(-1, ModbusResponseParser.parse(
                List.of(field(1, "值", 2, "int16", "ABCD")), "010302FFFFB9F4").getInteger("值"));

        assertEquals(25.5f, ModbusResponseParser.parse(
                List.of(field(1, "值", 4, "float32", "CDAB")), "010404000041CCCB81").getFloat("值"));
    }

    @Test
    void decodeStringTrimsTrailingPadding() {
        assertEquals("AB", ModbusResponseParser.parse(
                List.of(field(1, "型号", 4, "string", null)), "010404414200004FAC").getString("型号"));
    }

    @Test
    void decodeWriteResponseWithoutFieldsIsEmpty() {
        assertTrue(ModbusResponseParser.parse(List.of(), "010600010064D9E1").isEmpty());
    }

    @Test
    void decodeRejectsBadFrame() {
        List<ModbusServiceField> fields = List.of(field(1, "温度", 2, "uint16", "ABCD"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ModbusResponseParser.parse(fields, "018302C0F1"));
        assertTrue(exception.getMessage().contains("code=2"));

        assertThrows(IllegalArgumentException.class,
                () -> ModbusResponseParser.parse(fields, "0103020064B9AE"));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusResponseParser.parse(fields, "0103"));
        // 字段声明与帧长对不上
        assertThrows(IllegalArgumentException.class,
                () -> ModbusResponseParser.parse(
                        List.of(field(1, "温度", 4, "uint32", "ABCD")), "0103020064B9AF"));
    }
}
