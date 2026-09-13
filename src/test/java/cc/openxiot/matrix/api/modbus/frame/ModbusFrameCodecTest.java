package cc.openxiot.matrix.api.modbus.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Modbus RTU 帧编解码的纯单测：期望帧（含 CRC16）由独立实现算出，与本实现的 0xA001/0xFFFF 口径互校。
 */
class ModbusFrameCodecTest {

    private static ModbusCommand command(String fc, Integer start, Integer quantity, String dataType, String byteOrder) {
        ModbusCommand command = new ModbusCommand();
        command.fc = fc;
        command.index = 1;
        command.start = start;
        command.quantity = quantity;
        command.dataType = dataType;
        command.byteOrder = byteOrder;
        return command;
    }

    // ===== 请求帧 =====

    @Test
    void encodeReadHoldingRegisters() {
        assertEquals("010300000001840A", ModbusFrameCodec.encodeRequest(1, command("03", 0, 1, "uint16", null)));
    }

    @Test
    void encodeReadCoils() {
        assertEquals("010100050001EDCB", ModbusFrameCodec.encodeRequest(1, command("01", 5, 1, null, null)));
    }

    /** 数量是「值的个数」：帧里要读的寄存器数 = 数量 × 类型跨度。 */
    @Test
    void encodeReadRegistersUsesValueCountTimesSpan() {
        // 3 个 uint16 值（跨度 1）= 3 个寄存器
        assertEquals("0103000A000325C9", ModbusFrameCodec.encodeRequest(1, command("03", 10, 3, "uint16", "ABCD")));
        // 2 个 float32 值（跨度 2）= 4 个寄存器
        assertEquals("010400000004F1C9", ModbusFrameCodec.encodeRequest(1, command("04", 0, 2, "float32", "ABCD")));
    }

    /** string 的数量本身就是寄存器长度，恒为一个值。 */
    @Test
    void encodeReadStringUsesQuantityAsLength() {
        assertEquals("010400000004F1C9", ModbusFrameCodec.encodeRequest(1, command("04", 0, 4, "string", "ABCD")));
    }

    @Test
    void encodeReadUsesSlaveIdAndDefaultsToOne() {
        assertEquals("020300000001", ModbusFrameCodec.encodeRequest(2, command("03", 0, 1, "uint16", null)).substring(0, 12));
        assertEquals("01", ModbusFrameCodec.encodeRequest(null, command("03", 0, 1, "uint16", null)).substring(0, 2));
    }

    @Test
    void encodeWriteSingleCoil() {
        ModbusCommand on = command("05", 0, null, null, null);
        on.coilState = "on";
        assertEquals("01050000FF008C3A", ModbusFrameCodec.encodeRequest(1, on));

        ModbusCommand off = command("05", 0, null, null, null);
        off.coilState = "off";
        assertEquals("010500000000CDCA", ModbusFrameCodec.encodeRequest(1, off));
    }

    @Test
    void encodeWriteSingleRegister() {
        ModbusCommand write = command("06", 1, null, null, null);
        write.registerValue = 100L;
        assertEquals("010600010064D9E1", ModbusFrameCodec.encodeRequest(1, write));
    }

    @Test
    void encodeWriteMultipleCoils() {
        ModbusCommand write = command("0F", 0, null, null, null);
        write.coils = List.of(coil(0, true), coil(1, false), coil(2, true), coil(3, true));

        // quantity=4 → byteCount=1，位按 offset 落在对应 bit 上：0b1101
        assertEquals("010F00000004010DFF53", ModbusFrameCodec.encodeRequest(1, write));
    }

    @Test
    void encodeWriteMultipleRegisters() {
        ModbusCommand write = command("10", 10, null, null, null);
        write.registers = List.of(register(10, "uint16", "ABCD", 100L), register(11, "int16", "ABCD", -1L));

        assertEquals("0110000A0002040064FFFF33BF", ModbusFrameCodec.encodeRequest(1, write));
    }

    @Test
    void encodeWriteMultipleRegisters_byteOrderCdab() {
        ModbusCommand write = command("10", 0, null, null, null);
        // 25.5f 的位模式 0x41CC0000，CDAB 即两字交换 → 0000 41CC
        write.registers = List.of(register(0, "float32", "CDAB", 0x41CC0000L));

        assertEquals("01100000000204000041CCC3AA", ModbusFrameCodec.encodeRequest(1, write));
    }

    @Test
    void encodeRejectsMissingFields() {
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.encodeRequest(1, command("03", null, 1, "uint16", null)));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.encodeRequest(1, command("07", 0, 1, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.encodeRequest(1, command("10", 0, null, null, null)));
    }

    // ===== 应答帧 =====

    @Test
    void decodeUint16Register() {
        assertEquals(100, ModbusFrameCodec.decodeResponse(command("03", 0, 1, "uint16", null), "0103020064B9AF"));
    }

    @Test
    void decodeInt16AndScale() {
        assertEquals(-1, ModbusFrameCodec.decodeResponse(command("03", 0, 1, "int16", null), "010302FFFFB9F4"));

        // 123 原始值 × 0.5 缩放 → 61.5（用二进制可精确表示的系数，避免浮点比较误差）
        ModbusCommand scaled = command("03", 0, 1, "int16", null);
        scaled.scale = 0.5;
        assertEquals(61.5, ModbusFrameCodec.decodeResponse(scaled, "010302007BF867"));
    }

    @Test
    void decodeUint32AndFloat32() {
        assertEquals(100L, ModbusFrameCodec.decodeResponse(command("04", 0, 2, "uint32", "ABCD"), "01040400000064FA6F"));
        assertEquals(25.5f, ModbusFrameCodec.decodeResponse(command("04", 0, 2, "float32", "ABCD"), "01040441CC00002F87"));
        assertEquals(25.5f, ModbusFrameCodec.decodeResponse(command("04", 0, 2, "float32", "CDAB"), "010404000041CCCB81"));
        // 应答功能码与请求不一致（被上一帧迟到应答串了）要能识别出来
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.decodeResponse(command("04", 0, 2, "float32", "ABCD"), "01030441CC00002E30"));
    }

    @Test
    void decodeCoilBit() {
        assertEquals(true, ModbusFrameCodec.decodeResponse(command("01", 0, 1, null, null), "010101019048"));
        assertEquals(false, ModbusFrameCodec.decodeResponse(command("01", 0, 1, null, null), "010101005188"));
    }

    @Test
    void decodeWriteResponseHasNoValue() {
        assertNull(ModbusFrameCodec.decodeResponse(command("06", 1, null, null, null), "010600010064D9E1"));
    }

    @Test
    void decodeToleratesWhitespaceAndLowerCase() {
        assertEquals(100, ModbusFrameCodec.decodeResponse(command("03", 0, 1, "uint16", null), "01 03 02 00 64 b9 af"));
    }

    @Test
    void decodeRejectsExceptionAndBadCrc() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.decodeResponse(command("03", 0, 1, "uint16", null), "018302C0F1"));
        assertEquals(true, exception.getMessage().contains("code=2"));

        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.decodeResponse(command("03", 0, 1, "uint16", null), "0103020064B9AE"));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.decodeResponse(command("03", 0, 1, "uint16", null), "0103"));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.decodeResponse(command("03", 0, 1, "uint16", null), "zz"));
    }

    // ===== 小工具 =====

    private static ModbusCommand.Coil coil(int offset, boolean on) {
        ModbusCommand.Coil coil = new ModbusCommand.Coil();
        coil.offset = offset;
        coil.on = on;
        return coil;
    }

    private static ModbusCommand.Register register(int address, String dataType, String byteOrder, long value) {
        ModbusCommand.Register register = new ModbusCommand.Register();
        register.address = address;
        register.dataType = dataType;
        register.byteOrder = byteOrder;
        register.value = value;
        return register;
    }
}
