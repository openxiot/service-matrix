package cc.openxiot.matrix.api.modbus.fd.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.openxiot.matrix.typedef.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.typedef.modbus.failure.ModbusInvokeException;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistries;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequest;
import cc.openxiot.matrix.db.modbus.fd.poll.response.field.ModbusResponseField;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Modbus 应答帧按寄存器引用解析的纯单测：期望帧（含 CRC16）由独立实现算出，与本实现的 0xA001/0xFFFF 口径互校。
 *
 * <p>字段只引用 {@code registryIndex}，解码元数据从被引用的 {@link ModbusRegistry} 取
 * （dataType/byteOrder/scale/enumMap/area/length）。返回值以寄存器地址为 key。</p>
 */
class ModbusResponseParserTest {

    private static ModbusRegistry reg(int index, int address, String dataType, String byteOrder, Double scale) {
        ModbusRegistry reg = new ModbusRegistry();
        reg.index = index;
        reg.address = address;
        reg.area = "holding";
        reg.dataType = dataType;
        reg.byteOrder = byteOrder;
        reg.scale = scale;
        reg.length = null;
        return reg;
    }

    private static ModbusRegistry reg(int index, int address, String dataType, String byteOrder) {
        return reg(index, address, dataType, byteOrder, null);
    }

    private static ModbusResponseField field(int registryIndex) {
        ModbusResponseField field = new ModbusResponseField();
        field.registryIndex = registryIndex;
        return field;
    }

    /** 一个读方法的请求定义（fc 03 默认）。 */
    private static ModbusRequest read(String fc, int quantity) {
        ModbusRequest request = new ModbusRequest();
        request.slaveId = 1;
        request.fc = fc;
        request.start = 0;
        request.quantity = quantity;
        return request;
    }

    private static JsonObject parse(List<ModbusResponseField> fields, List<ModbusRegistry> registries,
                                    ModbusRequest request, String frameHex) {
        return ModbusResponseParser.parse(fields, ModbusRegistries.byIndex(registries), request, frameHex);
    }

    @Test
    void decodeSingleUint16() {
        ModbusRegistry temp = reg(1, 0, "uint16", "ABCD");
        JsonObject values = parse(List.of(field(1)), List.of(temp), read("03", 1), "0103020064B9AF");
        assertEquals(100, values.getInteger("0"));
    }

    @Test
    void decodeSplitsDataAreaByRegisterWidth() {
        // 地址 0 是 uint16、地址 2 是 uint32：按各寄存器字节宽累加切分
        ModbusRegistry temp = reg(1, 0, "uint16", "ABCD");
        ModbusRegistry total = reg(2, 2, "uint32", "ABCD");
        JsonObject values = parse(List.of(field(1), field(2)), List.of(temp, total), read("03", 3),
                "0103060064000000C8512B");
        assertEquals(100, values.getInteger("0"));
        assertEquals(200L, values.getLong("2"));
    }

    @Test
    void decodeIgnoresFieldListOrderAndUsesRegisterAddress() {
        ModbusRegistry temp = reg(1, 0, "uint16", "ABCD");
        ModbusRegistry total = reg(2, 2, "uint32", "ABCD");
        JsonObject values = parse(List.of(field(2), field(1)), List.of(total, temp), read("03", 3),
                "0103060064000000C8512B");
        assertEquals(100, values.getInteger("0"));
        assertEquals(200L, values.getLong("2"));
    }

    @Test
    void decodeUint8WithEnumMap() {
        ModbusRegistry on = reg(1, 0, "uint8", null);
        on.enumMap = Map.of("0", "ON", "1", "OFF");
        assertEquals("ON", parse(List.of(field(1)), List.of(on), read("03", 1), "01030100F048").getString("0"));
        assertEquals("OFF", parse(List.of(field(1)), List.of(on), read("03", 1), "010301013188").getString("0"));
    }

    @Test
    void decodeEnumMapMissFallsBackToNumber() {
        ModbusRegistry on = reg(1, 0, "uint8", null);
        on.enumMap = Map.of("9", "未知");
        assertEquals(1, parse(List.of(field(1)), List.of(on), read("03", 1), "010301013188").getInteger("0"));
    }

    @Test
    void decodeAppliesScaleUnlessOne() {
        ModbusRegistry scaled = reg(1, 0, "int16", "ABCD", 0.5);
        assertEquals(61.5, parse(List.of(field(1)), List.of(scaled), read("03", 1), "010302007BF867")
                .getDouble("0"));

        ModbusRegistry unscaled = reg(1, 0, "int16", "ABCD", 1.0);
        assertEquals(123, parse(List.of(field(1)), List.of(unscaled), read("03", 1), "010302007BF867")
                .getInteger("0"));
    }

    @Test
    void decodeInt16AndFloat32ByteOrders() {
        assertEquals(-1, parse(List.of(field(1)), List.of(reg(1, 0, "int16", "ABCD")),
                read("03", 1), "010302FFFFB9F4").getInteger("0"));

        assertEquals(25.5f, parse(List.of(field(1)), List.of(reg(1, 0, "float32", "CDAB")),
                read("03", 2), "010404000041CCCB81").getFloat("0"));
    }

    @Test
    void decodeStringTrimsTrailingPadding() {
        // string 寄存器跨 2 个寄存器（缺省 2 字节），或显式 length
        ModbusRegistry model = reg(1, 0, "string", null);
        model.length = 4;
        assertEquals("AB", parse(List.of(field(1)), List.of(model), read("03", 2), "010404414200004FAC")
                .getString("0"));
    }

    @Test
    void decodeBitRegionPicksEachRegisterBitByAddress() {
        // 01 读位：地址 0、2 的位区 05 = 第 0、2 位为 1。每只线圈寄存器是独立字段，位偏移 = 地址 − start
        ModbusRegistry c0 = new ModbusRegistry();
        c0.index = 1; c0.address = 0; c0.area = "coil";
        ModbusRegistry c2 = new ModbusRegistry();
        c2.index = 2; c2.address = 2; c2.area = "coil";
        JsonObject values = parse(List.of(field(1), field(2)), List.of(c0, c2), read("01", 8),
                "01010105918B");
        assertEquals(1, values.getInteger("0"));
        assertEquals(1, values.getInteger("2"));
    }

    @Test
    void decodeWriteResponseWithoutFieldsIsEmpty() {
        assertTrue(ModbusResponseParser.parse(List.of(), Map.of(), read("03", 1), "010600010064D9E1").isEmpty());
    }

    @Test
    void decodeRejectsBadFrame() {
        ModbusRegistry temp = reg(1, 0, "uint16", "ABCD");
        List<ModbusResponseField> fields = List.of(field(1));
        List<ModbusRegistry> regs = List.of(temp);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parse(fields, regs, read("03", 1), "018302C0F1"));
        assertTrue(exception.getMessage().contains("code=2"));

        assertThrows(IllegalArgumentException.class, () -> parse(fields, regs, read("03", 1), "0103020064B9AE"));
        assertThrows(IllegalArgumentException.class, () -> parse(fields, regs, read("03", 1), "0103"));
        // 字段声明（uint32 = 4 字节）与帧长（1 个寄存器 = 2 字节）对不上
        assertThrows(IllegalArgumentException.class,
                () -> parse(List.of(field(1)), List.of(reg(1, 0, "uint32", "ABCD")),
                        read("03", 1), "0103020064B9AF"));
    }

    /**
     * 每一类失败都带上**分类**与远端原始码（见 {@link ModbusFailureType}）。
     */
    @Test
    void classifiesEveryFailureType() {
        ModbusRegistry temp = reg(1, 0, "uint16", "ABCD");
        List<ModbusResponseField> fields = List.of(field(1));
        List<ModbusRegistry> regs = List.of(temp);

        ModbusInvokeException slaveException = assertThrows(ModbusInvokeException.class,
                () -> parse(fields, regs, read("03", 1), "018302C0F1"));
        assertEquals(ModbusFailureType.SLAVE_EXCEPTION, slaveException.type());
        assertEquals(2, slaveException.remoteCode());

        assertEquals(ModbusFailureType.CRC_MISMATCH, typeOf(fields, regs, read("03", 1), "0103020064B9AE"));
        assertEquals(ModbusFailureType.INVALID_FRAME, typeOf(fields, regs, read("03", 1), "0103"));
        assertEquals(ModbusFailureType.INVALID_FRAME, typeOf(fields, regs, read("03", 1), "zzzz"));

        assertEquals(ModbusFailureType.FRAME_MISMATCH,
                typeOf(List.of(field(1)), List.of(reg(1, 0, "uint32", "ABCD")), read("03", 1), "0103020064B9AF"));

        // 引用不存在的寄存器 → FIELD_DEFINITION_ERROR
        assertEquals(ModbusFailureType.FIELD_DEFINITION_ERROR,
                typeOf(List.of(field(99)), regs, read("03", 1), "0103020064B9AF"));
    }

    /** 没有远端码的分类断言：只关心分类，不关心码。 */
    private static ModbusFailureType typeOf(List<ModbusResponseField> fields, List<ModbusRegistry> regs,
                                            ModbusRequest request, String frame) {
        return assertThrows(ModbusInvokeException.class,
                () -> parse(fields, regs, request, frame)).type();
    }
}