package cc.openxiot.matrix.typedef.modbus.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.service.transaction.request.ModbusTransactionRequest;
import cc.openxiot.matrix.db.modbus.service.transaction.request.ModbusTransactionRequests;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    // ===== 同一件事的两条线：点表命令 vs 服务请求定义 =====

    /**
     * 服务里的请求定义与点表命令是同一个组帧算式（共用 {@code withCrc / reorder / hi / lo}）：
     * 表达同一件事时必须出**逐字节相同**的帧。点表那条线是既有行为、也已被别处覆盖，拿它当 oracle。
     *
     * <p>唯一的语义差异是读方法的 {@code quantity}：点表是「值的个数 × 单一 dataType 跨度」，
     * 服务里每个应答字段各带 format，那个乘法根本算不出来，所以服务这边直接写帧里的字面数量。</p>
     */
    @Test
    void serviceRequestMatchesThePointTableCommand() {
        // 读：uint16 一个值 = 1 个寄存器；2 个 float32 值 = 4 个寄存器（服务这边直接写 4）
        assertEquals(pointTable(command("03", 0, 1, "uint16", null)),
                serviceFrame(ModbusTransactionRequests.read("03", 0, 1)));
        assertEquals(pointTable(command("01", 5, 1, null, null)),
                serviceFrame(ModbusTransactionRequests.read("01", 5, 1)));
        assertEquals(pointTable(command("04", 0, 2, "float32", "ABCD")),
                serviceFrame(ModbusTransactionRequests.read("04", 0, 4)));

        // 写单线圈
        ModbusCommand singleCoil = command("05", 0, null, null, null);
        singleCoil.coilState = "on";
        assertEquals(pointTable(singleCoil), serviceFrame(ModbusTransactionRequests.write("05", 0,
                ModbusTransactionRequests.field(1, "开机", "bit", true))));

        // 写单寄存器
        ModbusCommand singleRegister = command("06", 1, null, null, null);
        singleRegister.registerValue = 100L;
        assertEquals(pointTable(singleRegister), serviceFrame(ModbusTransactionRequests.write("06", 1,
                ModbusTransactionRequests.field(1, "写入值", "uint16", 100))));

        // 写多个线圈：位按各自的 offset 落进同一个位字节
        ModbusCommand coils = command("0F", 0, null, null, null);
        coils.coils = List.of(coil(0, true), coil(1, false), coil(2, true), coil(3, true));
        assertEquals(pointTable(coils), serviceFrame(ModbusTransactionRequests.write("0F", 0,
                ModbusTransactionRequests.field(1, "位1", 0, "bit", true),
                ModbusTransactionRequests.field(2, "位2", 1, "bit", false),
                ModbusTransactionRequests.field(3, "位3", 2, "bit", true),
                ModbusTransactionRequests.field(4, "位4", 3, "bit", true))));

        // 写多个寄存器：int16 的 -1（补码）在两条线上同形
        ModbusCommand registers = command("10", 10, null, null, null);
        registers.registers = List.of(register(10, "uint16", "ABCD", 100L), register(11, "int16", "ABCD", -1L));
        assertEquals(pointTable(registers), serviceFrame(ModbusTransactionRequests.write("10", 10,
                ModbusTransactionRequests.field(1, "值1", 0, "uint16", "ABCD", 100),
                ModbusTransactionRequests.field(2, "值2", 1, "int16", "ABCD", -1))));

        // float32 + CDAB：点表给的是位模式，服务这边给的是浮点值，落到帧上应当是同一串
        ModbusCommand floatRegisters = command("10", 0, null, null, null);
        floatRegisters.registers = List.of(register(0, "float32", "CDAB", 0x41CC0000L));
        assertEquals(pointTable(floatRegisters), serviceFrame(ModbusTransactionRequests.write("10", 0,
                ModbusTransactionRequests.field(1, "设定温度", 0, "float32", "CDAB", 25.5f))));
    }

    /** 定点回归：结构化的那条线也不能把既有帧组歪（这几个值别处在断言）。 */
    @Test
    void serviceRequestKeepsTheRegressionFrames() {
        assertEquals("010300000001840A", serviceFrame(ModbusTransactionRequests.holding()));
        assertEquals("01050000FF008C3A", serviceFrame(ModbusTransactionRequests.write("05", 0,
                ModbusTransactionRequests.field(1, "开机", "bit", true))));
        assertEquals("0110000A0002040064FFFF33BF", serviceFrame(ModbusTransactionRequests.write("10", 10,
                ModbusTransactionRequests.field(1, "值1", 0, "uint16", "ABCD", 100),
                ModbusTransactionRequests.field(2, "值2", 1, "int16", "ABCD", -1))));
    }

    /** invoke 给的 values 顶掉定义里的缺省值：同一个定义、每次调用一个值。 */
    @Test
    void serviceRequestValuesOverrideTheDefaults() {
        ModbusTransactionRequest write = ModbusTransactionRequests.write("06", 1,
                ModbusTransactionRequests.field(1, "写入值", "uint16", 100));

        assertEquals("010600010064D9E1", ModbusFrameCodec.encodeRequest(write, Map.of("写入值", 100)));
        assertEquals("0106000100C8D99C", ModbusFrameCodec.encodeRequest(write, Map.of("写入值", 200)));
        // 没给值就回落缺省：这是「开箱即用、又能被覆盖」的那一半
        assertEquals("010600010064D9E1", ModbusFrameCodec.encodeRequest(write, Map.of()));
    }

    /** 组帧失败必须是明确的配置错误（会被 wrap 成 CONFIG_ERROR），不是一句 NPE。 */
    @Test
    void serviceRequestRejectsUnbuildableFrames() {
        assertThrows(IllegalArgumentException.class, () -> serviceFrame(null));
        assertThrows(IllegalArgumentException.class,
                () -> serviceFrame(ModbusTransactionRequests.read("07", 0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.encodeRequest(ModbusTransactionRequests.write("06", 1,
                        ModbusTransactionRequests.field(1, "写入值", "uint16", null)), Map.of()));
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
        ModbusCommand.Field f = new ModbusCommand.Field();
        f.name = "x";
        f.scale = 0.5;
        scaled.fields = List.of(f);
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

    /** 点表那条线（从站固定 1）：服务里的从站是 request 自己的字段，这里对齐全用 1。 */
    private static String pointTable(ModbusCommand command) {
        return ModbusFrameCodec.encodeRequest(1, command);
    }

    /** 服务定义那条线：这里的用例全都靠字段的缺省值，故 values 传 null。 */
    private static String serviceFrame(ModbusTransactionRequest request) {
        return ModbusFrameCodec.encodeRequest(request, null);
    }

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
