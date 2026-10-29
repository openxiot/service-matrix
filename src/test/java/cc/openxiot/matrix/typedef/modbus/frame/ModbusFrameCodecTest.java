package cc.openxiot.matrix.typedef.modbus.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistries;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequest;
import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequests;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Modbus RTU 帧编解码的纯单测：期望帧（含 CRC16）由独立实现算出，与本实现的 0xA001/0xFFFF 口径互校。
 *
 * <p>点表命令已迁移到「以寄存器为中心」：读命令只带 start/quantity（被读寄存器按 fc 区从 start 推导），
 * 写命令带 writeItems（引用寄存器 + 原始值）。组帧所需的地址/数据类型/字节序/位宽一律取自 {@code registries}。</p>
 */
class ModbusFrameCodecTest {

    private static ModbusCommand command(String fc, Integer start, Integer quantity) {
        ModbusCommand command = new ModbusCommand();
        command.fc = fc;
        command.index = 1;
        command.start = start;
        command.quantity = quantity;
        return command;
    }

    private static ModbusCommand.ModbusCommandWriteItem item(int registryIndex, Object value) {
        ModbusCommand.ModbusCommandWriteItem item = new ModbusCommand.ModbusCommandWriteItem();
        item.registryIndex = registryIndex;
        item.value = value;
        return item;
    }

    // ===== 请求帧 =====

    @Test
    void encodeReadHoldingRegisters() {
        assertEquals("010300000001840A",
                ModbusFrameCodec.encodeRequest(1, command("03", 0, 1), registries()));
    }

    @Test
    void encodeReadCoils() {
        assertEquals("010100050001EDCB",
                ModbusFrameCodec.encodeRequest(1, command("01", 5, 1), registries()));
    }

    /**
     * 数量是「值的个数」：帧里要读的寄存器数 = 数量 × 被读起头寄存器的类型跨度。
     */
    @Test
    void encodeReadRegistersUsesValueCountTimesSpan() {
        // 3 个 uint16 值（跨度 1）= 3 个寄存器（起头寄存器 U16_10 @ holding/10）
        assertEquals("0103000A000325C9",
                ModbusFrameCodec.encodeRequest(1, command("03", 10, 3), registries()));
        // 2 个 float32 值（跨度 2）= 4 个寄存器（起头寄存器 F32_IN @ input/0）
        assertEquals("010400000004F1C9",
                ModbusFrameCodec.encodeRequest(1, command("04", 0, 2), registries()));
    }

    /** string 整段算一个值：帧寄存器数由声明的字节长推出（8 字节 = 4 个寄存器）。 */
    @Test
    void encodeReadStringUsesDeclaredLength() {
        // 起头寄存器 STR_LEN_IN @ input/20：length=8 → quantity=1 读 4 个寄存器
        assertEquals("010400140004B1CD",
                ModbusFrameCodec.encodeRequest(1, command("04", 20, 1), registries()));
    }

    /** 存量 string 命令没声明 length：数量仍按寄存器数解释，帧与升级前逐字节相同。 */
    @Test
    void encodeReadLegacyStringUsesQuantityAsRegisterCount() {
        assertEquals("0104000A0004D1CB",
                ModbusFrameCodec.encodeRequest(1, command("04", 10, 4), registries()));
    }

    @Test
    void encodeReadUsesSlaveIdAndDefaultsToOne() {
        assertEquals("020300000001",
                ModbusFrameCodec.encodeRequest(2, command("03", 0, 1), registries()).substring(0, 12));
        assertEquals("01",
                ModbusFrameCodec.encodeRequest(null, command("03", 0, 1), registries()).substring(0, 2));
    }

    @Test
    void encodeWriteSingleCoil() {
        ModbusCommand on = command("05", 0, null);
        on.writeItems = List.of(item(COIL0, true));
        assertEquals("01050000FF008C3A", ModbusFrameCodec.encodeRequest(1, on, registries()));

        ModbusCommand off = command("05", 0, null);
        off.writeItems = List.of(item(COIL0, false));
        assertEquals("010500000000CDCA", ModbusFrameCodec.encodeRequest(1, off, registries()));
    }

    @Test
    void encodeWriteSingleRegister() {
        ModbusCommand write = command("06", 1, null);
        write.writeItems = List.of(item(U16_1, 100));
        assertEquals("010600010064D9E1", ModbusFrameCodec.encodeRequest(1, write, registries()));
    }

    @Test
    void encodeWriteMultipleCoils() {
        ModbusCommand write = command("0F", 0, null);
        write.writeItems = List.of(item(COIL0, true), item(COIL1, false), item(COIL2, true), item(COIL3, true));

        // quantity=4 → byteCount=1，位按被引用线圈的地址相对 start 的偏移落位：0b1101
        assertEquals("010F00000004010DFF53", ModbusFrameCodec.encodeRequest(1, write, registries()));
    }

    @Test
    void encodeWriteMultipleRegisters() {
        ModbusCommand write = command("10", 10, null);
        write.writeItems = List.of(item(U16_10, 100), item(I16_11, -1));

        assertEquals("0110000A0002040064FFFF33BF", ModbusFrameCodec.encodeRequest(1, write, registries()));
    }

    @Test
    void encodeWriteMultipleRegisters_byteOrderCdab() {
        ModbusCommand write = command("10", 0, null);
        // 25.5f 的位模式 0x41CC0000，CDAB 即两字交换 → 0000 41CC
        write.writeItems = List.of(item(F32_0, 25.5f));

        assertEquals("01100000000204000041CCC3AA", ModbusFrameCodec.encodeRequest(1, write, registries()));
    }

    @Test
    void encodeRejectsMissingFields() {
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.encodeRequest(1, command("03", null, 1), registries()));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.encodeRequest(1, command("07", 0, 1), registries()));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.encodeRequest(1, command("10", 0, null), registries()));
    }

    // ===== 同一件事的两条线：点表命令 vs 服务请求定义 =====

    /**
     * 服务里的请求定义与点表命令是同一个组帧算式（共用 {@code withCrc / reorder / hi / lo}）：
     * 表达同一件事时必须出**逐字节相同**的帧。点表那条线是既有行为、也已被别处覆盖，拿它当 oracle。
     *
     * <p>唯一的语义差异是读方法的 {@code quantity}：点表是「值的个数 × 起头寄存器跨度」，
     * 服务里每个应答字段各带 format，那个乘法根本算不出来，所以服务这边直接写帧里的字面数量。</p>
     */
    @Test
    void serviceRequestMatchesThePointTableCommand() {
        // 读：uint16 一个值 = 1 个寄存器；2 个 float32 值 = 4 个寄存器（服务这边直接写 4）
        assertEquals(pointTable(command("03", 0, 1)),
                serviceFrame(ModbusRequests.read("03", 0, 1)));
        assertEquals(pointTable(command("01", 5, 1)),
                serviceFrame(ModbusRequests.read("01", 5, 1)));
        assertEquals(pointTable(command("04", 0, 2)),
                serviceFrame(ModbusRequests.read("04", 0, 4)));

        // 写单线圈
        ModbusCommand singleCoil = command("05", 0, null);
        singleCoil.writeItems = List.of(item(COIL0, true));
        assertEquals(pointTable(singleCoil), serviceFrame(ModbusRequests.write("05", 0,
                ModbusRequests.field(COIL0, true))));

        // 写单寄存器
        ModbusCommand singleRegister = command("06", 1, null);
        singleRegister.writeItems = List.of(item(U16_1, 100));
        assertEquals(pointTable(singleRegister), serviceFrame(ModbusRequests.write("06", 1,
                ModbusRequests.field(U16_1, 100))));

        // 写多个线圈：位按各自的地址相对 start 的偏移落进同一个位字节
        ModbusCommand coils = command("0F", 0, null);
        coils.writeItems = List.of(item(COIL0, true), item(COIL1, false), item(COIL2, true), item(COIL3, true));
        assertEquals(pointTable(coils), serviceFrame(ModbusRequests.write("0F", 0,
                ModbusRequests.field(COIL0, true),
                ModbusRequests.field(COIL1, false),
                ModbusRequests.field(COIL2, true),
                ModbusRequests.field(COIL3, true))));

        // 写多个寄存器：int16 的 -1（补码）在两条线上同形
        ModbusCommand registers = command("10", 10, null);
        registers.writeItems = List.of(item(U16_10, 100), item(I16_11, -1));
        assertEquals(pointTable(registers), serviceFrame(ModbusRequests.write("10", 10,
                ModbusRequests.field(U16_10, 100),
                ModbusRequests.field(I16_11, -1))));

        // float32 + CDAB：点表给的是浮点值、服务这边也是浮点值，落到帧上应当是同一串
        ModbusCommand floatRegisters = command("10", 0, null);
        floatRegisters.writeItems = List.of(item(F32_0, 25.5f));
        assertEquals(pointTable(floatRegisters), serviceFrame(ModbusRequests.write("10", 0,
                ModbusRequests.field(F32_0, 25.5f))));
    }

    /** 定点回归：结构化的那条线也不能把既有帧组歪（这几个值别处在断言）。 */
    @Test
    void serviceRequestKeepsTheRegressionFrames() {
        assertEquals("010300000001840A", serviceFrame(ModbusRequests.holding()));
        assertEquals("01050000FF008C3A", serviceFrame(ModbusRequests.write("05", 0,
                ModbusRequests.field(COIL0, true))));
        assertEquals("0110000A0002040064FFFF33BF", serviceFrame(ModbusRequests.write("10", 10,
                ModbusRequests.field(U16_10, 100),
                ModbusRequests.field(I16_11, -1))));
    }

    /** invoke 给的 values 顶掉定义里的缺省值：同一个定义、每次调用一个值。 */
    @Test
    void serviceRequestValuesOverrideTheDefaults() {
        ModbusRequest write = ModbusRequests.write("06", 1,
                ModbusRequests.field(U16_1, 100));

        assertEquals("010600010064D9E1", ModbusFrameCodec.encodeRequest(write, Map.of("2", 100), registries()));
        assertEquals("0106000100C8D99C", ModbusFrameCodec.encodeRequest(write, Map.of("2", 200), registries()));
        // 没给值就回落缺省：这是「开箱即用、又能被覆盖」的那一半
        assertEquals("010600010064D9E1", ModbusFrameCodec.encodeRequest(write, Map.of(), registries()));
    }

    /** 组帧失败必须是明确的配置错误（会被 wrap 成 CONFIG_ERROR），不是一句 NPE。 */
    @Test
    void serviceRequestRejectsUnbuildableFrames() {
        assertThrows(IllegalArgumentException.class, () -> serviceFrame(null));
        assertThrows(IllegalArgumentException.class,
                () -> serviceFrame(ModbusRequests.read("07", 0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.encodeRequest(ModbusRequests.write("06", 1,
                        ModbusRequests.field(U16_1, null)), Map.of(), registries()));
    }

    // ===== 应答帧 =====

    @Test
    void decodeUint16Register() {
        assertEquals(100, ModbusFrameCodec.decodeResponse(command("03", 0, 1), "0103020064B9AF",
                one("holding", 0, "uint16", null, null)));
    }

    @Test
    void decodeInt16AndScale() {
        // 无缩放：-1 原样返回
        assertEquals(-1, ModbusFrameCodec.decodeResponse(command("03", 0, 1), "010302FFFFB9F4",
                one("holding", 0, "int16", null, null)));

        // 123 原始值 × 0.5 缩放 → 61.5（0.5 用二进制可精确表示的系数，避免浮点比较误差）
        assertEquals(61.5, ModbusFrameCodec.decodeResponse(command("03", 0, 1), "010302007BF867",
                one("holding", 0, "int16", null, 0.5)));
    }

    @Test
    void decodeUint32AndFloat32() {
        assertEquals(100L, ModbusFrameCodec.decodeResponse(command("04", 0, 2), "01040400000064FA6F",
                one("input", 0, "uint32", "ABCD", null)));
        assertEquals(25.5f, ModbusFrameCodec.decodeResponse(command("04", 0, 2), "01040441CC00002F87",
                one("input", 0, "float32", "ABCD", null)));
        assertEquals(25.5f, ModbusFrameCodec.decodeResponse(command("04", 0, 2), "010404000041CCCB81",
                one("input", 0, "float32", "CDAB", null)));
        // 应答功能码与请求不一致（被上一帧迟到应答串了）要能识别出来
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.decodeResponse(command("04", 0, 2), "01030441CC00002E30",
                        one("input", 0, "float32", "ABCD", null)));
    }

    @Test
    void decodeCoilBit() {
        assertEquals(true, ModbusFrameCodec.decodeResponse(command("01", 0, 1), "010101019048", registries()));
        assertEquals(false, ModbusFrameCodec.decodeResponse(command("01", 0, 1), "010101005188", registries()));
    }

    /** string 解码：先丢 0x00 再按 UTF-8 整段解，尾部空白（含补位的空格）去掉。 */
    @Test
    void decodeStringStripsZeroBytesAndTrailingSpace() {
        // 尾部补 0x00（"ON" 后两个空字节）
        assertEquals("ON", ModbusFrameCodec.decodeResponse(command("04", 10, 4),
                "0104044F4E00008D47", oneString(10, null)));
        // 两个字符挤一个寄存器
        assertEquals("AB", ModbusFrameCodec.decodeResponse(command("04", 10, 4),
                "010404414200004FAC", oneString(10, null)));
        // 一字符一寄存器（高字节补 0）也能读对
        assertEquals("AB", ModbusFrameCodec.decodeResponse(command("04", 10, 4),
                "01040441004200DF18", oneString(10, null)));
        // 尾部空格补位
        assertEquals("ON", ModbusFrameCodec.decodeResponse(command("04", 10, 4),
                "0104044F4E2020955F", oneString(10, null)));
    }

    /** 中文跨寄存器：UTF-8 整段解（"温度" = 6 字节，后补 2 个 0x00）。 */
    @Test
    void decodeStringDecodesUtf8AcrossRegisters() {
        assertEquals("温度", ModbusFrameCodec.decodeResponse(command("04", 20, 1),
                "010408E6B8A9E5BAA6000002B0", oneString(20, 8)));
    }

    /** 声明了 length：应答字节数必须整段对上（少读/多读都是配置或从站错）。 */
    @Test
    void decodeStringRejectsByteCountMismatchWhenLengthDeclared() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.decodeResponse(command("04", 20, 1),
                        "0104044F4E00008D47", oneString(20, 8)));
        assertTrue(ex.getMessage().contains("does not match string length 8"));
    }

    @Test
    void decodeWriteResponseHasNoValue() {
        assertNull(ModbusFrameCodec.decodeResponse(command("06", 1, null), "010600010064D9E1", registries()));
    }

    @Test
    void decodeToleratesWhitespaceAndLowerCase() {
        assertEquals(100, ModbusFrameCodec.decodeResponse(command("03", 0, 1), "01 03 02 00 64 b9 af",
                one("holding", 0, "uint16", null, null)));
    }

    @Test
    void decodeRejectsExceptionAndBadCrc() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.decodeResponse(command("03", 0, 1), "018302C0F1",
                        one("holding", 0, "uint16", null, null)));
        assertEquals(true, exception.getMessage().contains("code=2"));

        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.decodeResponse(command("03", 0, 1), "0103020064B9AE",
                        one("holding", 0, "uint16", null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.decodeResponse(command("03", 0, 1), "0103",
                        one("holding", 0, "uint16", null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusFrameCodec.decodeResponse(command("03", 0, 1), "zz",
                        one("holding", 0, "uint16", null, null)));
    }

    // ===== 小工具 =====

    /** 点表那条线（从站固定 1）：服务里的从站是 request 自己的字段，这里对齐全用 1。 */
    private static String pointTable(ModbusCommand command) {
        return ModbusFrameCodec.encodeRequest(1, command, registries());
    }

    /** 服务定义那条线：这里的用例全都靠字段的缺省值，故 values 传 null。 */
    private static String serviceFrame(ModbusRequest request) {
        return ModbusFrameCodec.encodeRequest(request, null, registries());
    }

    /** 一个寄存器的查询表（解应答用；读 03/04 的格式/字节序/缩放取被读起头寄存器）。 */
    private static Map<Integer, ModbusRegistry> one(String area, int address, String dataType,
                                                    String byteOrder, Double scale) {
        ModbusRegistry r = new ModbusRegistry();
        r.index = 1;
        r.address = address;
        r.area = area;
        r.dataType = dataType;
        r.byteOrder = byteOrder;
        r.scale = scale;
        r.access = "R";
        return ModbusRegistries.byIndex(List.of(r));
    }

    /** 单只 string 寄存器的查询表（解应答用；length 为空 = 存量口径，按帧里的 byteCount 整段解）。 */
    private static Map<Integer, ModbusRegistry> oneString(int address, Integer length) {
        ModbusRegistry r = new ModbusRegistry();
        r.index = 1;
        r.address = address;
        r.area = "input";
        r.dataType = "string";
        r.length = length;
        r.access = "R";
        return ModbusRegistries.byIndex(List.of(r));
    }

    // 服务线夹具用的寄存器（index -> (地址, 区, 类型)）：字段/命令以 registryIndex 引用它们
    private static final int U16_0 = 1;     // addr 0  uint16
    private static final int U16_1 = 2;     // addr 1  uint16
    private static final int COIL0 = 3;     // addr 0  coil
    private static final int COIL1 = 4;     // addr 1  coil
    private static final int COIL2 = 5;     // addr 2  coil
    private static final int COIL3 = 6;     // addr 3  coil
    private static final int U16_10 = 7;    // addr 10 uint16
    private static final int I16_11 = 8;    // addr 11 int16
    private static final int F32_0 = 9;     // addr 0  float32
    private static final int COIL5 = 10;    // addr 5  coil
    private static final int F32_IN = 11;   // addr 0  input float32
    private static final int STR_IN = 12;   // addr 10 input string（未声明 length，存量口径）
    private static final int STR_LEN_IN = 13;   // addr 20 input string length=8

    private static Map<Integer, ModbusRegistry> registries() {
        List<ModbusRegistry> regs = new ArrayList<>();
        regs.add(reg(U16_0, 0, "holding", "uint16", "ABCD"));
        regs.add(reg(U16_1, 1, "holding", "uint16", "ABCD"));
        regs.add(reg(COIL0, 0, "coil", "coil", null));
        regs.add(reg(COIL1, 1, "coil", "coil", null));
        regs.add(reg(COIL2, 2, "coil", "coil", null));
        regs.add(reg(COIL3, 3, "coil", "coil", null));
        regs.add(reg(U16_10, 10, "holding", "uint16", "ABCD"));
        regs.add(reg(I16_11, 11, "holding", "int16", "ABCD"));
        regs.add(reg(F32_0, 0, "holding", "float32", "CDAB"));
        regs.add(reg(COIL5, 5, "coil", "coil", null));
        regs.add(reg(F32_IN, 0, "input", "float32", "ABCD"));
        regs.add(reg(STR_IN, 10, "input", "string", null));
        ModbusRegistry strLen = reg(STR_LEN_IN, 20, "input", "string", null);
        strLen.length = 8;
        regs.add(strLen);
        return ModbusRegistries.byIndex(regs);
    }

    private static ModbusRegistry reg(int index, int address, String area, String dataType, String byteOrder) {
        ModbusRegistry r = new ModbusRegistry();
        r.index = index;
        r.address = address;
        r.area = area;
        r.dataType = dataType;
        r.byteOrder = byteOrder;
        r.access = "RW";
        return r;
    }
}