package cc.openxiot.matrix.api.modbus.fd.parser;

import cc.openxiot.matrix.typedef.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.typedef.modbus.failure.ModbusInvokeException;
import cc.openxiot.matrix.typedef.modbus.frame.ModbusFrameCodec;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistries;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequest;
import cc.openxiot.matrix.db.modbus.fd.poll.response.field.ModbusResponseField;
import io.vertx.core.json.JsonObject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 按 {@link ModbusResponseField}（寄存器引用）把父设备回传的应答帧解成「寄存器地址 → 值」。
 *
 * <pre>
 * 应答帧: [slave][fc][byteCount][data...][crcLo][crcHi]
 *                          └─ 按 response.fields[].registryIndex 指向的寄存器，以各自字节宽依次切分 ─┘
 * </pre>
 *
 * <p>每个字段引用点表里一只 {@link ModbusRegistry}：数据格式/字节序/缩放/枚举映射/位区全部取自该寄存器，
 * 故响应字段本身不含任何解码元数据。返回值以<b>寄存器地址</b>作 key（跨四区地址可重复时以 area+address 消解）。</p>
 *
 * <p>01/02 的数据区是**按位打包**的位数组，不按字节序拼装：第 i 位 = 第 i/8 个字节的第 i%8 位
 * （LSB-first，第 0 位即起始地址那个线圈）。每个字段取「自己那个寄存器」的位（位偏移 = 寄存器地址 − 请求起始地址）。</p>
 */
public final class ModbusResponseParser {

    /** slave + fc + byteCount */
    private static final int HEADER_SIZE = 3;
    /** slave + fc + 数据 + crc16(2) */
    private static final int MIN_FRAME_SIZE = 5;
    private static final int CRC_SIZE = 2;
    private static final int ERROR_MASK = 0x80;
    /** 读位功能码：01 读线圈、02 读离散输入（应答数据区按位打包） */
    private static final int FC_READ_COILS = 0x01;
    private static final int FC_READ_DISCRETE_INPUTS = 0x02;

    private ModbusResponseParser() {
    }

    /**
     * 解一帧应答（按寄存器引用切分数据区并取值）。
     *
     * @param fields      该方法的应答字段定义（各引用一只寄存器）
     * @param registries  点表寄存器按 {@code index} 的查询表（configSnapshot.registries）
     * @param request     该读方法的请求定义（用它的 {@code fc/start/quantity} 做位区切分与对账）
     * @param frameHex    父设备回传的应答帧（16 进制字符串，大小写与空白不敏感）
     * @return 「寄存器地址 → 值」的结果
     * @throws ModbusInvokeException 帧非法、CRC 不符、从站返回异常码，或字段声明与帧长对不上
     */
    public static JsonObject parse(List<ModbusResponseField> fields,
                                   Map<Integer, ModbusRegistry> registries,
                                   ModbusRequest request,
                                   String frameHex) {
        try {
            return parseFrame(fields, registries, request, frameHex);
        } catch (ModbusInvokeException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // ModbusFrameCodec 的帧原语（十六进制、长度、结构）抛的是无分类的 IllegalArgumentException，
            // 而它同时被 request 侧复用，不该由它来决定「执行失败」的分类 —— 在这里补上。
            throw ModbusInvokeException.of(ModbusFailureType.INVALID_FRAME, e.getMessage(), e);
        }
    }

    private static JsonObject parseFrame(List<ModbusResponseField> fields,
                                         Map<Integer, ModbusRegistry> registries,
                                         ModbusRequest request,
                                         String frameHex) {
        byte[] frame = ModbusFrameCodec.parseHex(frameHex);
        if (frame.length < MIN_FRAME_SIZE) {
            throw ModbusInvokeException.of(ModbusFailureType.INVALID_FRAME,
                    "invalid modbus response: " + frameHex);
        }

        int fc = frame[1] & 0xFF;
        if ((fc & ERROR_MASK) != 0) {
            int code = frame[2] & 0xFF;
            throw ModbusInvokeException.remote(ModbusFailureType.SLAVE_EXCEPTION, code,
                    "modbus exception response: code=" + code + ", frame=" + frameHex);
        }
        if (!ModbusFrameCodec.checkCrc(frame)) {
            throw ModbusInvokeException.of(ModbusFailureType.CRC_MISMATCH,
                    "modbus response crc mismatch: " + frameHex);
        }

        boolean bitRegion = fc == FC_READ_COILS || fc == FC_READ_DISCRETE_INPUTS;
        int expected = expectedByteCount(fields, registries, bitRegion, request);
        int byteCount = frame[2] & 0xFF;
        if (byteCount != expected) {
            throw ModbusInvokeException.of(ModbusFailureType.FRAME_MISMATCH,
                    "modbus response length mismatch: byteCount=" + byteCount
                            + ", expected=" + expected + ", frame=" + frameHex);
        }
        if (HEADER_SIZE + byteCount > frame.length - CRC_SIZE) {
            throw ModbusInvokeException.of(ModbusFailureType.INVALID_FRAME,
                    "invalid modbus response: " + frameHex);
        }

        JsonObject values = new JsonObject();
        if (bitRegion) {
            parseBits(fields, registries, request, frame, values);
        } else {
            parseRegisters(fields, registries, frame, values);
        }
        return values;
    }

    /** 应答数据区应有字节数：位区 = 请求量的位打包字节；寄存器区 = Σ 各寄存器字节宽。 */
    private static int expectedByteCount(List<ModbusResponseField> fields,
                                         Map<Integer, ModbusRegistry> registries,
                                         boolean bitRegion,
                                         ModbusRequest request) {
        if (bitRegion) {
            int quantity = request == null || request.quantity == null ? fields.size() : request.quantity;
            return (quantity + 7) / 8;
        }
        int total = 0;
        for (ModbusResponseField field : fields) {
            total += ModbusRegistries.byteWidth(resolve(field, registries));
        }
        return total;
    }

    /** 寄存器区：按引用寄存器的地址升序切分，逐只解出「地址 → 值」。 */
    private static void parseRegisters(List<ModbusResponseField> fields,
                                       Map<Integer, ModbusRegistry> registries,
                                       byte[] frame,
                                       JsonObject values) {
        List<ModbusResponseField> ordered = new java.util.ArrayList<>(fields);
        ordered.sort(java.util.Comparator.comparingInt(field -> {
            ModbusRegistry reg = resolve(field, registries);
            return reg.address == null ? 0 : reg.address;
        }));
        int offset = HEADER_SIZE;
        for (ModbusResponseField field : ordered) {
            ModbusRegistry reg = resolve(field, registries);
            byte[] data = Arrays.copyOfRange(frame, offset, offset + ModbusRegistries.byteWidth(reg));
            values.put(String.valueOf(reg.address), decodeValue(reg, data));
            offset += ModbusRegistries.byteWidth(reg);
        }
    }

    /** 位区：整段按位打包；每个字段取「自己那个寄存器」的位（位偏移 = 地址 − 请求起始地址）。 */
    private static void parseBits(List<ModbusResponseField> fields,
                                  Map<Integer, ModbusRegistry> registries,
                                  ModbusRequest request,
                                  byte[] frame,
                                  JsonObject values) {
        int start = request == null ? 0 : (request.start == null ? 0 : request.start);
        byte[] data = Arrays.copyOfRange(frame, HEADER_SIZE, frame.length - CRC_SIZE);
        for (ModbusResponseField field : fields) {
            ModbusRegistry reg = resolve(field, registries);
            int offset = reg.address - start;
            if (offset < 0 || offset / 8 >= data.length) {
                throw ModbusInvokeException.of(ModbusFailureType.FIELD_DEFINITION_ERROR,
                        "invalid modbus bit offset: " + offset + " (registry " + reg.index + ")");
            }
            int bit = (data[offset / 8] >> (offset % 8)) & 1;
            values.put(String.valueOf(reg.address), bit);
        }
    }

    private static ModbusRegistry resolve(ModbusResponseField field, Map<Integer, ModbusRegistry> registries) {
        if (field == null || field.registryIndex == null) {
            throw ModbusInvokeException.of(ModbusFailureType.FIELD_DEFINITION_ERROR,
                    "modbus response field registryIndex is not set");
        }
        ModbusRegistry reg = registries == null ? null : registries.get(field.registryIndex);
        if (reg == null) {
            throw ModbusInvokeException.of(ModbusFailureType.FIELD_DEFINITION_ERROR,
                    "modbus response field references missing registry " + field.registryIndex);
        }
        return reg;
    }

    /** 按寄存器解出该段的取值（原始值 → enumMap / scale）。 */
    private static Object decodeValue(ModbusRegistry reg, byte[] data) {
        String format = ModbusRegistries.format(reg);
        Object raw = switch (format) {
            case "string" -> ModbusRegistries.decodeString(data, reg.length);
            case "int8", "uint8", "int16", "uint16", "int32", "uint32", "float32" ->
                    number(format, bits(reg, data, false));
            default -> throw ModbusInvokeException.of(ModbusFailureType.FIELD_DEFINITION_ERROR,
                    "unsupported modbus registry data type: " + reg.dataType + " (registry " + reg.index + ")");
        };

        String description = descriptionOf(reg, raw);
        if (description != null) {
            return description;
        }
        if (raw instanceof Number value) {
            double scale = reg.scale == null ? 1.0 : reg.scale;
            return scale == 1.0 ? raw : value.doubleValue() * scale;
        }
        return raw;
    }

    /** 数据区原始字节 → 位模式；按寄存器字节序拼装（单字节无需排布，多字节缺省 ABCD = 大端）。 */
    private static long bits(ModbusRegistry reg, byte[] data, boolean bitRegion) {
        if (bitRegion) {
            long little = 0;
            for (int i = data.length - 1; i >= 0; i--) {
                little = (little << 8) | (data[i] & 0xFF);
            }
            return little;
        }
        byte[] ordered = data.length == 1 ? data : ModbusFrameCodec.reorder(data, ModbusRegistries.byteOrder(reg));
        long bits = 0;
        for (byte b : ordered) {
            bits = (bits << 8) | (b & 0xFF);
        }
        return bits;
    }

    private static Object number(String format, long bits) {
        return switch (format) {
            case "int8" -> (int) (byte) bits;
            case "uint8" -> (int) bits & 0xFF;
            case "int16" -> (int) (short) bits;
            case "uint16" -> (int) bits & 0xFFFF;
            case "int32" -> (int) bits;
            case "uint32" -> bits & 0xFFFFFFFFL;
            default -> Float.intBitsToFloat((int) bits);
        };
    }

    /** 命中寄存器的枚举映射则返回其描述，否则 null。 */
    private static String descriptionOf(ModbusRegistry reg, Object raw) {
        if (!(raw instanceof Number value) || reg.enumMap == null || reg.enumMap.isEmpty()) {
            return null;
        }
        return reg.enumMap.get(String.valueOf(value.longValue()));
    }
}