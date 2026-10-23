package cc.openxiot.matrix.api.modbus.service.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.openxiot.matrix.typedef.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.typedef.modbus.failure.ModbusInvokeException;
import cc.openxiot.matrix.db.modbus.service.transaction.response.field.ModbusTransactionResponseField;
import cc.openxiot.matrix.db.modbus.service.transaction.response.field.ModbusTransactionResponseFieldBit;
import cc.openxiot.matrix.db.modbus.service.transaction.response.field.ModbusTransactionResponseFieldValue;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Modbus 应答帧按字段解析的纯单测：期望帧（含 CRC16）由独立实现算出，与本实现的 0xA001/0xFFFF 口径互校。
 */
class ModbusResponseParserTest {

    private static ModbusTransactionResponseField field(int index, String name, int bytes, String format, String byteOrder) {
        ModbusTransactionResponseField field = new ModbusTransactionResponseField();
        field.index = index;
        field.field = name;
        field.bytes = bytes;
        field.format = format;
        field.byteOrder = byteOrder;
        return field;
    }

    private static ModbusTransactionResponseFieldValue value(long value, String description) {
        ModbusTransactionResponseFieldValue entry = new ModbusTransactionResponseFieldValue();
        entry.value = value;
        entry.description = description;
        return entry;
    }

    private static ModbusTransactionResponseFieldBit bit(int offset, String name) {
        ModbusTransactionResponseFieldBit entry = new ModbusTransactionResponseFieldBit();
        entry.offset = offset;
        entry.field = name;
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
        ModbusTransactionResponseField on = field(1, "开关状态", 1, "uint8", null);
        on.valueList = List.of(value(0, "ON"), value(1, "OFF"));

        assertEquals("ON", ModbusResponseParser.parse(List.of(on), "01030100F048").getString("开关状态"));
        assertEquals("OFF", ModbusResponseParser.parse(List.of(on), "010301013188").getString("开关状态"));
    }

    @Test
    void decodeValueListMissFallsBackToNumber() {
        ModbusTransactionResponseField on = field(1, "开关状态", 1, "uint8", null);
        on.valueList = List.of(value(9, "未知"));

        assertEquals(1, ModbusResponseParser.parse(List.of(on), "010301013188").getInteger("开关状态"));
    }

    @Test
    void decodeAppliesScaleUnlessOne() {
        ModbusTransactionResponseField scaled = field(1, "温度", 2, "int16", "ABCD");
        scaled.scale = 0.5;
        assertEquals(61.5, ModbusResponseParser.parse(List.of(scaled), "010302007BF867").getDouble("温度"));

        ModbusTransactionResponseField unscaled = field(1, "温度", 2, "int16", "ABCD");
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
    void decodeBitListExpandsEachNamedBit() {
        // 01 读位：数据区 05 = 第 0、2 位为 1；位清单把其中两位各自出一个 0/1 值（字段自身仍是整段掩码）
        ModbusTransactionResponseField bits = field(1, "开关状态", 1, "uint8", null);
        bits.bitList = List.of(bit(0, "进水阀"), bit(1, "出水阀"), bit(2, "报警"));

        JsonObject values = ModbusResponseParser.parse(List.of(bits), "01010105918B");

        assertEquals(5, values.getInteger("开关状态"));
        assertEquals(1, values.getInteger("进水阀"));
        assertEquals(0, values.getInteger("出水阀"));
        assertEquals(1, values.getInteger("报警"));
    }

    @Test
    void decodeBitRegionIsLittleEndianAcrossBytes() {
        // 01 读 9 个位：byteCount=2，数据 03 01 → 第 0、1 位在首字节、第 8 位在次字节低位；掩码按位数组读 = 0x0103
        ModbusTransactionResponseField bits = field(1, "开关状态", 2, "uint16", "ABCD");
        bits.bitList = List.of(bit(0, "第1位"), bit(8, "第9位"), bit(9, "第10位"));

        JsonObject values = ModbusResponseParser.parse(List.of(bits), "010102030178CC");

        assertEquals(0x0103, values.getInteger("开关状态"));
        assertEquals(1, values.getInteger("第1位"));
        assertEquals(1, values.getInteger("第9位"));
        assertEquals(0, values.getInteger("第10位"));
    }

    @Test
    void decodeBitListRejectsOffsetBeyondField() {
        ModbusTransactionResponseField bits = field(1, "开关状态", 1, "uint8", null);
        bits.bitList = List.of(bit(8, "越界位"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ModbusResponseParser.parse(List.of(bits), "01010105918B"));
        assertTrue(exception.getMessage().contains("invalid modbus field bit"));
    }

    @Test
    void decodeWriteResponseWithoutFieldsIsEmpty() {
        assertTrue(ModbusResponseParser.parse(List.of(), "010600010064D9E1").isEmpty());
    }

    @Test
    void decodeRejectsBadFrame() {
        List<ModbusTransactionResponseField> fields = List.of(field(1, "温度", 2, "uint16", "ABCD"));

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

    /**
     * 每一类失败都带上**分类**与远端原始码（见 {@link ModbusFailureType}）。
     *
     * <p>分类必须在这里定：只有解析器知道是 CRC 不符还是从站异常码、异常码是几号。
     * 记录端只做透传，前端按分类聚合 —— 事后从 message 文本里反推分类等于把协议语义
     * 压成了字符串匹配。</p>
     */
    @Test
    void classifiesEveryFailureType() {
        List<ModbusTransactionResponseField> fields = List.of(field(1, "温度", 2, "uint16", "ABCD"));

        // 从站异常应答（03 的异常应答是 83）：分类 + 码号都要落下来
        ModbusInvokeException slaveException = assertThrows(ModbusInvokeException.class,
                () -> ModbusResponseParser.parse(fields, "018302C0F1"));
        assertEquals(ModbusFailureType.SLAVE_EXCEPTION, slaveException.type());
        assertEquals(2, slaveException.remoteCode());

        // CRC 不符：设备回了，但报文不可信 —— 与「设备没回」是两回事，处置方向也不同
        assertEquals(ModbusFailureType.CRC_MISMATCH, typeOf(fields, "0103020064B9AE"));

        // 帧本身不成形：太短、非十六进制（后者由 ModbusFrameCodec 抛无分类的 IAE，在本类里补分类）
        assertEquals(ModbusFailureType.INVALID_FRAME, typeOf(fields, "0103"));
        assertEquals(ModbusFailureType.INVALID_FRAME, typeOf(fields, "zzzz"));

        // 数据字节数与点表声明对不上：点表配错，或回帧的从站不是目标从站
        assertEquals(ModbusFailureType.FRAME_MISMATCH,
                typeOf(List.of(field(1, "温度", 4, "uint32", "ABCD")), "0103020064B9AF"));

        // 字段定义本身有问题：不支持的 format
        assertEquals(ModbusFailureType.FIELD_DEFINITION_ERROR,
                typeOf(List.of(field(1, "温度", 2, "uint128", "ABCD")), "0103020064B9AF"));

        // 字段定义本身有问题：bit 偏移越界
        ModbusTransactionResponseField bits = field(1, "开关状态", 1, "uint8", null);
        bits.bitList = List.of(bit(8, "越界位"));
        assertEquals(ModbusFailureType.FIELD_DEFINITION_ERROR, typeOf(List.of(bits), "01010105918B"));
    }

    /** 没有远端码的分类断言：只关心分类，不关心码。 */
    private static ModbusFailureType typeOf(List<ModbusTransactionResponseField> fields, String frame) {
        return assertThrows(ModbusInvokeException.class,
                () -> ModbusResponseParser.parse(fields, frame)).type();
    }
}
