package cc.openxiot.matrix.typedef.modbus.frame;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistries;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequest;
import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequestField;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
     * 按功能码把一条（以寄存器为中心的）点表命令编成请求帧。
     *
     * <p>读命令（01/02/03/04）只用 {@code start/quantity}，被读寄存器按 fc 区从 {@code start} 起推导；
     * 写命令（05/06/0F/10）的数据区由 {@code writeItems} 按被引用寄存器的地址升序拼出——地址、数据类型、
     * 字节序、位宽全部取自被引用的寄存器（{@code registries}）。主站调试 / 从站模拟都用这条线。</p>
     *
     * @param slaveId    从站地址（点表的 {@code slave.slaveId}，缺省 1）
     * @param command    点表命令（读写都以寄存器为中心）
     * @param registries 点表寄存器按 {@code index} 的查询表（configSnapshot.registries）
     * @return 大写 16 进制字符串（含 CRC16，无分隔符）
     */
    public static String encodeRequest(Integer slaveId, ModbusCommand command,
                                       Map<Integer, ModbusRegistry> registries) {
        int slave = slaveId == null ? DEFAULT_SLAVE_ID : slaveId;
        String fc = normalizeFc(command.fc);

        byte[] body = switch (fc) {
            case "01", "02", "03", "04" -> readBody(slave, fc, command, registries);
            case "05" -> writeSingleCoilBody(slave, command, registries);
            case "06" -> writeSingleRegisterBody(slave, command, registries);
            case "0F" -> writeMultipleCoilsBody(slave, command, registries);
            case "10" -> writeMultipleRegistersBody(slave, command, registries);
            default -> throw new IllegalArgumentException("unsupported modbus function code: " + command.fc);
        };

        return toHex(withCrc(body));
    }

    private static byte[] readBody(int slave, String fc, ModbusCommand command,
                                   Map<Integer, ModbusRegistry> registries) {
        int start = require(command.start, "start");
        int quantity = readQuantity(fc, command, registries);
        return new byte[]{
                (byte) slave, (byte) Integer.parseInt(fc, 16),
                hi(start), lo(start),
                hi(quantity), lo(quantity)
        };
    }

    /**
     * 读请求帧里的数量：01/02 即位数；03/04 的数量是**值的个数**（点表注释见 {@code ModbusCommand#quantity}），
     * 帧里要读的寄存器数由被读起头寄存器（{@code start} 处）的数据类型跨度推出：
     * 非 string = 数量 × 跨度，string / 位 = 数量本身（string 的数量就是其寄存器长、恒为一个值）。
     */
    public static int readQuantity(String fc, ModbusCommand command, Map<Integer, ModbusRegistry> registries) {
        int quantity = command.quantity == null ? 1 : command.quantity;
        if (!"03".equals(fc) && !"04".equals(fc)) {
            return quantity;
        }
        String area = "03".equals(fc) ? "holding" : "input";
        ModbusRegistry head = ModbusRegistries.at(registries, area, require(command.start, "start"));
        if (head == null) {
            throw new IllegalArgumentException("modbus command (fc " + fc
                    + ") reads from missing registry at start " + command.start + " in area " + area);
        }
        if (ModbusRegistries.isBit(head) || "string".equals(ModbusRegistries.format(head))) {
            return quantity;
        }
        return quantity * ModbusRegistries.registerCount(head);
    }

    private static byte[] writeSingleCoilBody(int slave, ModbusCommand command,
                                              Map<Integer, ModbusRegistry> registries) {
        ModbusCommand.ModbusCommandWriteItem item = command.writeItem(0);
        ModbusRegistry reg = registryOf(item, registries);
        int address = require(reg.address, "registry address");
        boolean on = toBit(item.value, itemName(item));
        return new byte[]{
                (byte) slave, (byte) 0x05,
                hi(address), lo(address),
                (byte) (on ? 0xFF : 0x00), (byte) 0x00
        };
    }

    private static byte[] writeSingleRegisterBody(int slave, ModbusCommand command,
                                                  Map<Integer, ModbusRegistry> registries) {
        ModbusCommand.ModbusCommandWriteItem item = command.writeItem(0);
        ModbusRegistry reg = registryOf(item, registries);
        int address = require(reg.address, "registry address");
        int value = (int) toInteger(item.value, itemName(item)) & 0xFFFF;
        return new byte[]{
                (byte) slave, (byte) 0x06,
                hi(address), lo(address),
                hi(value), lo(value)
        };
    }

    private static byte[] writeMultipleCoilsBody(int slave, ModbusCommand command,
                                                 Map<Integer, ModbusRegistry> registries) {
        int start = require(command.start, "start");
        List<ModbusCommand.ModbusCommandWriteItem> items = orderedWriteItems(command, registries);

        int quantity = items.size();
        int byteCount = (quantity + 7) / 8;
        byte[] bits = new byte[byteCount];
        int position = 0;   // 以位计的写入位置
        for (ModbusCommand.ModbusCommandWriteItem item : items) {
            ModbusRegistry reg = registryOf(item, registries);
            // 同服务线 request.fields：0F 的帧没有空洞，每个线圈紧接上一个
            if (reg.address - start != position) {
                throw new IllegalArgumentException("modbus coil offset out of order: " + (reg.address - start)
                        + " (expected " + position + "), registry=" + item.registryIndex);
            }
            if (toBit(item.value, itemName(item))) {
                bits[position / 8] |= (byte) (1 << (position % 8));
            }
            position++;
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

    private static byte[] writeMultipleRegistersBody(int slave, ModbusCommand command,
                                                     Map<Integer, ModbusRegistry> registries) {
        int start = require(command.start, "start");
        List<ModbusCommand.ModbusCommandWriteItem> items = orderedWriteItems(command, registries);

        int byteCount = 0;
        for (ModbusCommand.ModbusCommandWriteItem item : items) {
            byteCount += ModbusRegistries.byteWidth(registryOf(item, registries));
        }

        byte[] data = new byte[byteCount];
        int position = 0;   // 以寄存器计的写入位置
        for (ModbusCommand.ModbusCommandWriteItem item : items) {
            ModbusRegistry reg = registryOf(item, registries);
            String format = ModbusRegistries.format(reg);
            int width = ModbusRegistries.byteWidth(reg);
            // 同 0F：10 的帧也没有空洞，每个寄存器紧接上一个
            if (reg.address - start != position) {
                throw new IllegalArgumentException("modbus register offset out of order: " + (reg.address - start)
                        + " (expected " + position + "), registry=" + item.registryIndex);
            }
            long bits = "float32".equals(format)
                    ? Float.floatToIntBits(toFloat(item.value, itemName(item)))
                    : toInteger(item.value, itemName(item));
            byte[] value = toBytes(bits, width, ModbusRegistries.byteOrder(reg));
            System.arraycopy(value, 0, data, position * 2, width);
            position += width / 2;
        }

        byte[] frame = new byte[7 + byteCount];
        frame[0] = (byte) slave;
        frame[1] = (byte) 0x10;
        frame[2] = hi(start);
        frame[3] = lo(start);
        // 数量是寄存器个数而非明细行数：int32/uint32/float32 一个字段占两个寄存器
        int quantity = byteCount / 2;
        frame[4] = hi(quantity);
        frame[5] = lo(quantity);
        frame[6] = (byte) byteCount;
        System.arraycopy(data, 0, frame, 7, byteCount);
        return frame;
    }

    /** 多条（0F/10）写条目按被引用寄存器的地址升序 —— 决定数据区排列。 */
    private static List<ModbusCommand.ModbusCommandWriteItem> orderedWriteItems(ModbusCommand command,
                                                                                Map<Integer, ModbusRegistry> registries) {
        if (command.writeItems == null || command.writeItems.isEmpty()) {
            throw new IllegalArgumentException("modbus command (fc 0F/10) has no writeItems: index=" + command.index);
        }
        List<ModbusCommand.ModbusCommandWriteItem> ordered = new ArrayList<>(command.writeItems);
        ordered.sort(Comparator.comparingInt(item -> {
            ModbusRegistry reg = registryOf(item, registries);
            return reg.address == null ? 0 : reg.address;
        }));
        return ordered;
    }

    private static ModbusRegistry registryOf(ModbusCommand.ModbusCommandWriteItem item,
                                             Map<Integer, ModbusRegistry> registries) {
        if (item == null || item.registryIndex == null) {
            throw new IllegalArgumentException("modbus write item registryIndex is not set");
        }
        ModbusRegistry reg = registries == null ? null : registries.get(item.registryIndex);
        if (reg == null) {
            throw new IllegalArgumentException("modbus write item references missing registry " + item.registryIndex);
        }
        return reg;
    }

    /** 写条目的展示名（诊断文案用）：优先寄存器名，缺省给序号。 */
    private static String itemName(ModbusCommand.ModbusCommandWriteItem item) {
        return "registry " + (item.registryIndex == null ? "?" : item.registryIndex);
    }

    // ===== 请求帧（服务定义）=====

    /**
     * 按服务的请求定义组帧：一次调用的请求帧在 invoke 时现组，前端不再需要算 CRC16。
     *
     * <p>读（01–04）只用 {@code start/quantity}；写（05–10）的数据区由 {@code request.fields} 按被引用
     * 寄存器的地址升序拼出，每个字段的取值优先取 {@code values} 里以 {@code registryIndex} 为 key 的那个、
     * 缺省回落 {@code field.value}。写值的格式/字节序/字节宽取自被引用的寄存器（{@code registries}）。</p>
     *
     * @param values     invoke 传入的「寄存器序号 → 原始值」；读方法传 null
     * @param registries 点表寄存器按 {@code index} 的查询表（configSnapshot.registries）
     * @throws IllegalArgumentException 定义不成立（方向与字段/数量不匹配、取值缺失或类型不符）
     */
    public static String encodeRequest(ModbusRequest request, Map<String, Object> values,
                                       java.util.Map<Integer, cc.openxiot.matrix.db.modbus.config.ModbusRegistry> registries) {
        if (request == null) {
            throw new IllegalArgumentException("modbus device request is not set");
        }
        int slave = request.slaveId == null ? DEFAULT_SLAVE_ID : request.slaveId;
        String fc = normalizeFc(request.fc);
        int start = require(request.start, "start");

        byte[] body = switch (fc) {
            case "01", "02", "03", "04" -> readBody(slave, fc, start, require(request.quantity, "quantity"));
            case "05" -> writeSingleCoilBody(slave, start, request.fields, values, registries);
            case "06" -> writeSingleRegisterBody(slave, start, request.fields, values, registries);
            case "0F" -> writeMultipleCoilsBody(slave, start, request.fields, values, registries);
            case "10" -> writeMultipleRegistersBody(slave, start, request.fields, values, registries);
            default -> throw new IllegalArgumentException("unsupported modbus function code: " + request.fc);
        };

        return toHex(withCrc(body));
    }

    /** 读请求帧：{@code quantity} 已是帧里的字面值（01/02 为位数、03/04 为寄存器数）。 */
    private static byte[] readBody(int slave, String fc, int start, int quantity) {
        return new byte[]{
                (byte) slave, (byte) Integer.parseInt(fc, 16),
                hi(start), lo(start),
                hi(quantity), lo(quantity)
        };
    }

    private static byte[] writeSingleCoilBody(int slave, int start, List<ModbusRequestField> fields,
                                              Map<String, Object> values,
                                              java.util.Map<Integer, ModbusRegistry> registries) {
        ModbusRequestField field = singleField(fields, registries, "05");
        boolean on = toBit(resolveValue(field, values), fieldName(field));
        return new byte[]{
                (byte) slave, (byte) 0x05,
                hi(start), lo(start),
                (byte) (on ? 0xFF : 0x00), (byte) 0x00
        };
    }

    private static byte[] writeSingleRegisterBody(int slave, int start, List<ModbusRequestField> fields,
                                                  Map<String, Object> values,
                                                  java.util.Map<Integer, ModbusRegistry> registries) {
        ModbusRequestField field = singleField(fields, registries, "06");
        ModbusRegistry reg = registryOf(field, registries);
        if (ModbusRegistries.byteWidth(reg) != 2) {
            // fc 06 只有一个寄存器：4 字节格式在这里会被静默截掉一半
            throw new IllegalArgumentException("modbus fc 06 writes one register, not data type "
                    + ModbusRegistries.format(reg) + ": registry=" + field.registryIndex);
        }
        int value = (int) toInteger(resolveValue(field, values), fieldName(field)) & 0xFFFF;
        return new byte[]{
                (byte) slave, (byte) 0x06,
                hi(start), lo(start),
                hi(value), lo(value)
        };
    }

    private static byte[] writeMultipleCoilsBody(int slave, int start, List<ModbusRequestField> fields,
                                                 Map<String, Object> values,
                                                 java.util.Map<Integer, ModbusRegistry> registries) {
        List<ModbusRequestField> ordered = orderedFields(fields, registries);
        int quantity = ordered.size();
        int byteCount = (quantity + 7) / 8;
        byte[] bits = new byte[byteCount];

        int position = 0;
        for (ModbusRequestField field : ordered) {
            // 位偏移必须与排列位置一致：0F 的帧只有「起始地址 + 个数 + 紧凑位区」，不留空洞
            if (addressOffset(field, registries, start) != position) {
                throw new IllegalArgumentException("modbus coil offset out of order: " + addressOffset(field, registries, start)
                        + " (expected " + position + "), registry=" + field.registryIndex);
            }
            if (toBit(resolveValue(field, values), fieldName(field))) {
                bits[position / 8] |= (byte) (1 << (position % 8));
            }
            position++;
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

    private static byte[] writeMultipleRegistersBody(int slave, int start, List<ModbusRequestField> fields,
                                                     Map<String, Object> values,
                                                     java.util.Map<Integer, ModbusRegistry> registries) {
        List<ModbusRequestField> ordered = orderedFields(fields, registries);

        int byteCount = 0;
        for (ModbusRequestField field : ordered) {
            byteCount += ModbusRegistries.byteWidth(registryOf(field, registries));
        }

        byte[] data = new byte[byteCount];
        int position = 0;   // 以寄存器计的写入位置
        for (ModbusRequestField field : ordered) {
            ModbusRegistry reg = registryOf(field, registries);
            String format = ModbusRegistries.format(reg);
            int width = ModbusRegistries.byteWidth(reg);
            // 同 0F：10 的帧也没有空洞，每个字段紧接上一个
            if (addressOffset(field, registries, start) != position) {
                throw new IllegalArgumentException("modbus register offset out of order: "
                        + addressOffset(field, registries, start)
                        + " (expected " + position + "), registry=" + field.registryIndex);
            }
            long bits = "float32".equals(format)
                    ? Float.floatToIntBits(toFloat(resolveValue(field, values), fieldName(field)))
                    : toInteger(resolveValue(field, values), fieldName(field));
            byte[] value = toBytes(bits, width, ModbusRegistries.byteOrder(reg));
            System.arraycopy(value, 0, data, position * 2, width);
            position += width / 2;
        }

        byte[] frame = new byte[7 + byteCount];
        frame[0] = (byte) slave;
        frame[1] = (byte) 0x10;
        frame[2] = hi(start);
        frame[3] = lo(start);
        // 数量是寄存器个数而非字段行数：int32/uint32/float32 一个字段占两个寄存器
        int quantity = byteCount / 2;
        frame[4] = hi(quantity);
        frame[5] = lo(quantity);
        frame[6] = (byte) byteCount;
        System.arraycopy(data, 0, frame, 7, byteCount);
        return frame;
    }

    /** 05/06 的写请求只有一个字段（帧里没有「第几个」可言）。 */
    private static ModbusRequestField singleField(List<ModbusRequestField> fields,
                                                  java.util.Map<Integer, ModbusRegistry> registries,
                                                  String fc) {
        List<ModbusRequestField> ordered = orderedFields(fields, registries);
        if (ordered.size() != 1) {
            throw new IllegalArgumentException("modbus fc " + fc + " writes one field, got " + ordered.size());
        }
        return ordered.getFirst();
    }

    /** 字段按被引用寄存器的地址升序 —— 决定数据区排列。 */
    private static List<ModbusRequestField> orderedFields(List<ModbusRequestField> fields,
                                                          java.util.Map<Integer, ModbusRegistry> registries) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("modbus write request has no fields");
        }
        List<ModbusRequestField> ordered = new ArrayList<>(fields);
        ordered.sort(Comparator.comparingInt(field -> {
            ModbusRegistry reg = registryOf(field, registries);
            return reg.address == null ? 0 : reg.address;
        }));
        return ordered;
    }

    /** 字段地址相对请求起始地址的偏移（以寄存器计；校验排列连续性用）。 */
    private static int addressOffset(ModbusRequestField field,
                                     java.util.Map<Integer, ModbusRegistry> registries,
                                     int start) {
        ModbusRegistry reg = registryOf(field, registries);
        if (reg.address == null) {
            throw new IllegalArgumentException("modbus write registry has no address: registry=" + field.registryIndex);
        }
        return reg.address - start;
    }

    private static ModbusRegistry registryOf(ModbusRequestField field,
                                             java.util.Map<Integer, ModbusRegistry> registries) {
        if (field == null || field.registryIndex == null) {
            throw new IllegalArgumentException("modbus write field registryIndex is not set");
        }
        ModbusRegistry reg = registries == null ? null : registries.get(field.registryIndex);
        if (reg == null) {
            throw new IllegalArgumentException("modbus write field references missing registry " + field.registryIndex);
        }
        return reg;
    }

    /** 写字段的展示名（诊断文案用）：优先寄存器名，缺省给序号。 */
    private static String fieldName(ModbusRequestField field) {
        return "registry " + (field.registryIndex == null ? "?" : field.registryIndex);
    }

    /** 写字段的取值：invoke 给了就用给的，否则回落字段缺省值；两者都没有则报错。 */
    private static Object resolveValue(ModbusRequestField field, Map<String, Object> values) {
        String key = field.registryIndex == null ? null : String.valueOf(field.registryIndex);
        if (key != null && values != null && values.get(key) != null) {
            return values.get(key);
        }
        if (field.value != null) {
            return field.value;
        }
        throw new IllegalArgumentException("modbus write field has no value: " + fieldName(field));
    }

    private static boolean toBit(Object value, String name) {
        if (value instanceof Boolean bit) {
            return bit;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        throw new IllegalArgumentException("modbus write field is not a bit: " + name + "=" + value);
    }

    /**
     * 整数格式的取值：{@code 2} 过 Jackson 后可能是 {@code Integer} 也可能是 {@code Double}，
     * 判定按「值本身是不是整数」而不是按 Java 类型，非整数直接报错（不静默截断）。
     */
    private static long toInteger(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("modbus write field is not a number: " + name + "=" + value);
        }
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (d != Math.rint(d)) {
                throw new IllegalArgumentException("modbus write field is not an integer: " + name + "=" + value);
            }
            return (long) d;
        }
        return number.longValue();
    }

    private static float toFloat(Object value, String name) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        throw new IllegalArgumentException("modbus write field is not a number: " + name + "=" + value);
    }

    // ===== 应答帧 =====

    /**
     * 把应答帧解回读值（写命令的应答是回显请求帧，没有读值，返回 null）。
     *
     * <p>只解数据区的**首个值**：数量改为「值的个数」后一次读可能有多个值，多值读的调用链
     * 走 {@code ModbusResponseParser}（按服务定义的 response.fields[] 逐个字段解），本方法只服务于
     * 旧的点位式口径与存量单测。</p>
     *
     * @param frameHex   DTU 回传的应答帧（16 进制字符串，大小写与空白不敏感）
     * @param registries 点表寄存器按 {@code index} 的查询表（读 03/04 的数据格式/字节序/缩放取自被读起头寄存器）
     * @throws IllegalArgumentException 帧非法、CRC 不符或从站返回异常码
     */
    public static Object decodeResponse(ModbusCommand command, String frameHex,
                                        Map<Integer, ModbusRegistry> registries) {
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
            case "03", "04" -> decodeRegister(command, frame, frameHex, registries);
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

    private static Object decodeRegister(ModbusCommand command, byte[] frame, String frameHex,
                                         Map<Integer, ModbusRegistry> registries) {
        int byteCount = frame[2] & 0xFF;
        // 以寄存器为中心：数据格式/字节序/缩放取被读起头寄存器（start 处）；无寄存器兜底 uint16/默认序/1
        String area = "03".equals(normalizeFc(command.fc)) ? "holding" : "input";
        ModbusRegistry reg = ModbusRegistries.at(registries, area, require(command.start, "start"));
        String dataType = reg == null || reg.dataType == null || reg.dataType.isBlank()
                ? "uint16"
                : reg.dataType.trim().toLowerCase(Locale.ROOT);

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

        long bits = fromBytes(frame, HEADER_SIZE, width, reg == null ? null : reg.byteOrder);
        Object value = switch (dataType) {
            case "int16" -> (int) (short) bits;
            case "uint16" -> (int) bits & 0xFFFF;
            case "int32" -> (int) bits;
            case "uint32" -> bits & 0xFFFFFFFFL;
            case "float32" -> Float.intBitsToFloat((int) bits);
            default -> throw new IllegalArgumentException("unsupported modbus data type: " + reg.dataType);
        };

        // 缩放取被引用寄存器的 scale（命令不再内联字段）；真正的逐字段缩放走服务路（ModbusResponseParser）。
        double scale = reg != null && reg.scale != null ? reg.scale : 1.0;
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
     * 四种排布都是自逆的，故编码与解码共用本方法；单字节原样返回。{@code FieldDevice}（方案二）
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

    /** 校验帧尾 CRC16。{@code FieldDevice}（方案二）按字段切分应答数据区时复用。 */
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
