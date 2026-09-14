package cc.openxiot.matrix.typedef.modbus.frame;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Modbus RTU 帧编解码：把点表里的一条功能码动作编成请求帧（16 进制字符串，交给 DTU 的“发送”方法透传到
 * RS485 总线），以及把 DTU 回传的应答帧解回读值。
 *
 * <p>DTU 侧把数据帧原样写上线、把收到的字节原样回传（见 {@code DeviceYedS710YmProcessor}），
 * 所以 CRC16 由本侧补齐，应答帧里的 CRC 也由本侧校验。</p>
 *
 * <pre>
 * 读 01/02/03/04: [slave][fc][addrHi][addrLo][qtyHi][qtyLo][crcLo][crcHi]
 * 写单线圈 05:    [slave][fc][addrHi][addrLo][FF00|0000][crcLo][crcHi]
 * 写单寄存器 06:  [slave][fc][addrHi][addrLo][valHi][valLo][crcLo][crcHi]
 * 写多线圈 0F:    [slave][fc][addrHi][addrLo][qtyHi][qtyLo][byteCount][bits...][crcLo][crcHi]
 * 写多寄存器 10:  [slave][fc][addrHi][addrLo][qtyHi][qtyLo][byteCount][regs...][crcLo][crcHi]
 * 读应答:         [slave][fc][byteCount][data...][crcLo][crcHi]
 * 写应答:         回显请求帧（无读值）
 * 异常应答:       [slave][fc|80][exceptionCode][crcLo][crcHi]
 * </pre>
 */
public final class ModbusFrameCodec {

    /** 读功能码：01 读线圈 / 02 读离散输入 / 03 读保持寄存器 / 04 读输入寄存器 */
    private static final Set<String> READ_FCS = Set.of("01", "02", "03", "04");

    private static final int CRC_INIT = 0xFFFF;
    private static final int CRC_POLY = 0xA001;
    private static final int ERROR_MASK = 0x80;
    private static final int DEFAULT_SLAVE_ID = 1;

    /** slave + fc + byteCount */
    private static final int HEADER_SIZE = 3;
    /** slave + fc + 数据 + crc16(2) */
    private static final int MIN_FRAME_SIZE = 5;
    private static final int CRC_SIZE = 2;

    private ModbusFrameCodec() {
    }

    /** 是否读功能码（01/02/03/04）。 */
    public static boolean isRead(String fc) {
        return READ_FCS.contains(normalizeFc(fc));
    }

    // ===== 请求帧 =====

    /**
     * 按功能码把一条点表命令编成请求帧。
     *
     * @param slaveId 从站地址（点表的 {@code slave.slaveId}，缺省 1）
     * @return 大写 16 进制字符串（含 CRC16，无分隔符）
     */
    public static String encodeRequest(Integer slaveId, ModbusCommand command) {
        int slave = slaveId == null ? DEFAULT_SLAVE_ID : slaveId;
        String fc = normalizeFc(command.fc);

        byte[] body = switch (fc) {
            case "01", "02", "03", "04" -> readBody(slave, fc, command);
            case "05" -> writeSingleCoilBody(slave, command);
            case "06" -> writeSingleRegisterBody(slave, command);
            case "0F" -> writeMultipleCoilsBody(slave, command);
            case "10" -> writeMultipleRegistersBody(slave, command);
            default -> throw new IllegalArgumentException("unsupported modbus function code: " + command.fc);
        };

        return toHex(withCrc(body));
    }

    private static byte[] readBody(int slave, String fc, ModbusCommand command) {
        int start = require(command.start, "start");
        int quantity = readQuantity(fc, command);
        return new byte[]{
                (byte) slave, (byte) Integer.parseInt(fc, 16),
                hi(start), lo(start),
                hi(quantity), lo(quantity)
        };
    }

    /**
     * 读请求帧里的数量：01/02 即位数；03/04 的数量是**值的个数**（点表注释见 {@code ModbusCommand#quantity}），
     * 帧里要读的寄存器数 = 数量 × 类型跨度（string 的数量本身就是长度，恒为一个值）。
     */
    public static int readQuantity(String fc, ModbusCommand command) {
        int quantity = command.quantity == null ? 1 : command.quantity;
        if (!"03".equals(fc) && !"04".equals(fc)) {
            return quantity;
        }
        if (command.dataType == null || "string".equalsIgnoreCase(command.dataType)) {
            return quantity;
        }
        return quantity * (byteWidth(command.dataType) / 2);
    }

    private static byte[] writeSingleCoilBody(int slave, ModbusCommand command) {
        int start = require(command.start, "start");
        boolean on = "on".equalsIgnoreCase(command.coilState);
        return new byte[]{
                (byte) slave, (byte) 0x05,
                hi(start), lo(start),
                (byte) (on ? 0xFF : 0x00), (byte) 0x00
        };
    }

    private static byte[] writeSingleRegisterBody(int slave, ModbusCommand command) {
        int start = require(command.start, "start");
        int value = (int) (command.registerValue == null ? 0L : command.registerValue) & 0xFFFF;
        return new byte[]{
                (byte) slave, (byte) 0x06,
                hi(start), lo(start),
                hi(value), lo(value)
        };
    }

    private static byte[] writeMultipleCoilsBody(int slave, ModbusCommand command) {
        int start = require(command.start, "start");
        List<ModbusCommand.Coil> coils = command.coils;
        if (coils == null || coils.isEmpty()) {
            throw new IllegalArgumentException("modbus command (fc 0F) has no coils: index=" + command.index);
        }

        int quantity = coils.size();
        int byteCount = (quantity + 7) / 8;
        byte[] bits = new byte[byteCount];
        for (ModbusCommand.Coil coil : coils) {
            // offset 相对起始地址（0 基），与 Modbus 应答里“首个请求位落在数据首字节 bit0”口径一致
            int offset = coil.offset == null ? 0 : coil.offset;
            if (offset < 0 || offset >= quantity) {
                throw new IllegalArgumentException("modbus coil offset out of range: " + offset);
            }
            if (Boolean.TRUE.equals(coil.on)) {
                bits[offset / 8] |= (byte) (1 << (offset % 8));
            }
        }

        byte[] frame = new byte[7 + byteCount];
        frame[0] = (byte) slave;
        frame[1] = (byte) 0x0F;
        frame[2] = hi(start);
        frame[3] = lo(start);
        frame[4] = hi(quantity);
        frame[5] = lo(quantity);
        frame[6] = (byte) byteCount;
        System.arraycopy(bits, 0, frame, 7, byteCount);
        return frame;
    }

    private static byte[] writeMultipleRegistersBody(int slave, ModbusCommand command) {
        int start = require(command.start, "start");
        List<ModbusCommand.Register> registers = command.registers;
        if (registers == null || registers.isEmpty()) {
            throw new IllegalArgumentException("modbus command (fc 10) has no registers: index=" + command.index);
        }

        int byteCount = 0;
        for (ModbusCommand.Register register : registers) {
            byteCount += byteWidth(register.dataType);
        }

        byte[] data = new byte[byteCount];
        for (ModbusCommand.Register register : registers) {
            int address = require(register.address, "register address");
            int offset = (address - start) * 2;
            if (offset < 0 || offset + byteWidth(register.dataType) > byteCount) {
                throw new IllegalArgumentException("modbus register address out of range: " + address
                        + " (start=" + start + ")");
            }
            byte[] value = toBytes(register.value == null ? 0L : register.value,
                    byteWidth(register.dataType), register.byteOrder);
            System.arraycopy(value, 0, data, offset, value.length);
        }

        byte[] frame = new byte[7 + byteCount];
        frame[0] = (byte) slave;
        frame[1] = (byte) 0x10;
        frame[2] = hi(start);
        frame[3] = lo(start);
        // 数量是寄存器个数而非明细行数：int32/uint32/float32 一行占两个寄存器
        int quantity = byteCount / 2;
        frame[4] = hi(quantity);
        frame[5] = lo(quantity);
        frame[6] = (byte) byteCount;
        System.arraycopy(data, 0, frame, 7, byteCount);
        return frame;
    }

    // ===== 应答帧 =====

    /**
     * 把应答帧解回读值（写命令的应答是回显请求帧，没有读值，返回 null）。
     *
     * <p>只解数据区的**首个值**：数量改为「值的个数」后一次读可能有多个值，多值读的调用链
     * 走 {@code ModbusResponseParser}（按服务定义的 response[] 逐个字段解），本方法只服务于
     * 旧的点位式口径与存量单测。</p>
     *
     * @param frameHex DTU 回传的应答帧（16 进制字符串，大小写与空白不敏感）
     * @throws IllegalArgumentException 帧非法、CRC 不符或从站返回异常码
     */
    public static Object decodeResponse(ModbusCommand command, String frameHex) {
        byte[] frame = parseHex(frameHex);
        if (frame.length < MIN_FRAME_SIZE) {
            throw new IllegalArgumentException("invalid modbus response: " + frameHex);
        }

        int fc = frame[1] & 0xFF;
        if ((fc & ERROR_MASK) != 0) {
            throw new IllegalArgumentException("modbus exception response: code=" + (frame[2] & 0xFF)
                    + ", frame=" + frameHex);
        }
        if (!checkCrc(frame)) {
            throw new IllegalArgumentException("modbus response crc mismatch: " + frameHex);
        }
        // 功能码应与请求一致（异常应答的 0x80 位上面已排除）：不一致通常是被上一帧的迟到应答串了
        if (fc != Integer.parseInt(normalizeFc(command.fc), 16)) {
            throw new IllegalArgumentException("unexpected modbus response function code: " + fc
                    + ", frame=" + frameHex);
        }

        return switch (normalizeFc(command.fc)) {
            case "01", "02" -> decodeBit(frame, frameHex);
            case "03", "04" -> decodeRegister(command, frame, frameHex);
            default -> null;
        };
    }

    /** 位读命令的应答：本次请求的第一个位 = 数据首字节的 bit0（BOOL 属性只取一个位）。 */
    private static Object decodeBit(byte[] frame, String frameHex) {
        if ((frame[2] & 0xFF) < 1) {
            throw new IllegalArgumentException("invalid modbus response: " + frameHex);
        }
        return (frame[HEADER_SIZE] & 0x01) != 0;
    }

    private static Object decodeRegister(ModbusCommand command, byte[] frame, String frameHex) {
        int byteCount = frame[2] & 0xFF;
        String dataType = command.dataType == null || command.dataType.isBlank()
                ? "uint16"
                : command.dataType.trim().toLowerCase(Locale.ROOT);

        if ("string".equals(dataType)) {
            if (byteCount < 1 || HEADER_SIZE + byteCount > frame.length - CRC_SIZE) {
                throw new IllegalArgumentException("invalid modbus response: " + frameHex);
            }
            // 定长字符串：尾部常以 0x00 补满，去掉尾部空白后再返回
            String text = new String(frame, HEADER_SIZE, byteCount, StandardCharsets.UTF_8);
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) <= ' ') {
                end--;
            }
            return text.substring(0, end);
        }

        int width = byteWidth(dataType);
        if (byteCount < width || HEADER_SIZE + width > frame.length - CRC_SIZE) {
            throw new IllegalArgumentException("invalid modbus response: " + frameHex);
        }

        long bits = fromBytes(frame, HEADER_SIZE, width, command.byteOrder);
        Object value = switch (dataType) {
            case "int16" -> (int) (short) bits;
            case "uint16" -> (int) bits & 0xFFFF;
            case "int32" -> (int) bits;
            case "uint32" -> bits & 0xFFFFFFFFL;
            case "float32" -> Float.intBitsToFloat((int) bits);
            default -> throw new IllegalArgumentException("unsupported modbus data type: " + command.dataType);
        };

        double scale = command.scale == null ? 1.0 : command.scale;
        return scale == 1.0 ? value : ((Number) value).doubleValue() * scale;
    }

    // ===== 字节序 / 位宽 =====

    /** 数据类型占用的寄存器字节数（int16/uint16 → 2，int32/uint32/float32 → 4）。 */
    private static int byteWidth(String dataType) {
        if (dataType == null) {
            return 2;
        }
        return switch (dataType.toLowerCase(Locale.ROOT)) {
            case "int32", "uint32", "float32" -> 4;
            default -> 2;
        };
    }

    /**
     * 把 16/32 位原始数值按字节序排成 Modbus 线上字节（大端为基准）。
     *
     * <p>ABCD 大端 / DCBA 全反 / BADC 字内字节交换 / CDAB 字交换；两字节时后三者同为交换两字节。
     * 四种排布都是自逆的，故编码与解码共用本方法；单字节原样返回。{@code ModbusService}（方案二）
     * 解应答字段时同样复用。</p>
     */
    private static byte[] toBytes(long bits, int width, String byteOrder) {
        byte[] be = new byte[width];
        for (int i = 0; i < width; i++) {
            be[i] = (byte) (bits >> (8 * (width - 1 - i)));
        }
        return reorder(be, byteOrder);
    }

    /** 从应答数据区按字节序取回原始数值。{@link #reorder} 自逆，故与编码用同一套规则还原。 */
    private static long fromBytes(byte[] data, int offset, int width, String byteOrder) {
        byte[] ordered = reorder(Arrays.copyOfRange(data, offset, offset + width), byteOrder);
        long bits = 0;
        for (byte b : ordered) {
            bits = (bits << 8) | (b & 0xFF);
        }
        return bits;
    }

    public static byte[] reorder(byte[] be, String byteOrder) {
        String order = byteOrder == null || byteOrder.isBlank() ? "ABCD" : byteOrder.toUpperCase(Locale.ROOT);

        // 只有 16 位（2 字节）与 32 位（4 字节）有排布可言：单字节（int8/uint8）与其它宽度原样返回
        if (be.length != 2 && be.length != 4) {
            return be;
        }

        if (be.length == 2) {
            return switch (order) {
                case "BADC", "DCBA", "CDAB" -> new byte[]{be[1], be[0]};
                default -> be;
            };
        }

        return switch (order) {
            case "DCBA" -> new byte[]{be[3], be[2], be[1], be[0]};
            case "BADC" -> new byte[]{be[1], be[0], be[3], be[2]};
            case "CDAB" -> new byte[]{be[2], be[3], be[0], be[1]};
            default -> be;
        };
    }

    // ===== CRC16 / 16 进制 =====

    /** 追加 Modbus CRC16（低字节在前）。 */
    private static byte[] withCrc(byte[] body) {
        int crc = crc16(body);
        byte[] frame = Arrays.copyOf(body, body.length + CRC_SIZE);
        frame[body.length] = (byte) (crc & 0xFF);
        frame[body.length + 1] = (byte) ((crc >> 8) & 0xFF);
        return frame;
    }

    /** 校验帧尾 CRC16。{@code ModbusService}（方案二）按字段切分应答数据区时复用。 */
    public static boolean checkCrc(byte[] frame) {
        if (frame.length <= CRC_SIZE) {
            return false;
        }
        byte[] body = Arrays.copyOf(frame, frame.length - CRC_SIZE);
        int expected = (frame[frame.length - CRC_SIZE] & 0xFF) | ((frame[frame.length - 1] & 0xFF) << 8);
        return crc16(body) == expected;
    }

    private static int crc16(byte[] data) {
        int crc = CRC_INIT;
        for (byte b : data) {
            crc ^= b & 0xFF;
            for (int i = 0; i < 8; i++) {
                if ((crc & 1) != 0) {
                    crc >>= 1;
                    crc ^= CRC_POLY;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    /** 16 进制字符串 → 字节（忽略空白，大小写不敏感）。 */
    public static byte[] parseHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("modbus frame is null");
        }
        String value = hex.replaceAll("\\s+", "");
        if (value.isEmpty() || (value.length() & 1) != 0) {
            throw new IllegalArgumentException("invalid modbus frame: " + hex);
        }

        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid modbus frame: " + hex);
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    /** 字节 → 大写 16 进制字符串（无分隔符）。 */
    public static String toHex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            b.append(Character.forDigit((value >> 4) & 0x0F, 16));
            b.append(Character.forDigit(value & 0x0F, 16));
        }
        return b.toString().toUpperCase(Locale.ROOT);
    }

    // ===== 小工具 =====

    private static String normalizeFc(String fc) {
        return fc == null ? "" : fc.trim().toUpperCase(Locale.ROOT);
    }

    private static byte hi(int value) {
        return (byte) ((value >> 8) & 0xFF);
    }

    private static byte lo(int value) {
        return (byte) (value & 0xFF);
    }

    private static int require(Integer value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("modbus command " + name + " is not set");
        }
        return value;
    }
}
