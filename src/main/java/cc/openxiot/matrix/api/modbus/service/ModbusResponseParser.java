package cc.openxiot.matrix.api.modbus.service;

import cc.openxiot.matrix.api.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.api.modbus.failure.ModbusInvokeException;
import cc.openxiot.matrix.api.modbus.frame.ModbusFrameCodec;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldValue;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 按 {@link ModbusServiceField} 把父设备回传的应答帧解成「字段 → 值」。
 *
 * <pre>
 * 应答帧: [slave][fc][byteCount][data...][crcLo][crcHi]
 *                              └─ 按 response[].index 升序、以 bytes 依次累加偏移切分 ─┘
 * </pre>
 *
 * <p>帧的合法性（长度 / 异常码 / CRC16）与 {@code ModbusServiceFunction#request} 同口径，
 * 复用 {@link ModbusFrameCodec} 的字节序与 CRC 原语，不再另写一套。</p>
 *
 * <p>取值：原始值命中 {@code value-list} 时输出其 description（不再应用 scale）；否则输出
 * scale 后的数值（scale 缺省或为 1 时不缩放）。{@code unit} 只做展示、不参与取值，
 * 调用方可从服务定义里查。</p>
 *
 * <p>01/02 的数据区是**按位打包**的位数组，不按字节序拼装：第 i 位 = 第 i/8 个字节的第 i%8 位
 * （LSB-first，第 0 位即起始地址那个线圈）。字段的 {@code bit-list} 在字段值之外，把其中的若干位
 * 各自再作为一个 0/1 取值输出（key 即该位的 field）；未声明位清单时只出整段位掩码。</p>
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
     * 解一帧应答（按字段定义切分数据区并取值）。
     *
     * <p>失败一律抛 {@link ModbusInvokeException}，**在抛出的地方带上分类** —— 只有这里知道
     * 是 CRC 不符还是从站异常码、异常码是几号（见 {@link ModbusFailureType}）。
     * message 文本保持原样：它是排查时唯一能看到的东西。</p>
     *
     * @param fields   该方法的应答字段定义（顺序由各自的 index 决定）
     * @param frameHex 父设备回传的应答帧（16 进制字符串，大小写与空白不敏感）
     * @throws ModbusInvokeException 帧非法、CRC 不符、从站返回异常码，或字段声明与帧长对不上
     */
    public static JsonObject parse(List<ModbusServiceField> fields, String frameHex) {
        try {
            return parseFrame(fields, frameHex);
        } catch (ModbusInvokeException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // ModbusFrameCodec 的帧原语（十六进制、长度、结构）抛的是无分类的 IllegalArgumentException，
            // 而它同时被 request 侧复用，不该由它来决定「执行失败」的分类 —— 在这里补上。
            throw ModbusInvokeException.of(ModbusFailureType.INVALID_FRAME, e.getMessage(), e);
        }
    }

    private static JsonObject parseFrame(List<ModbusServiceField> fields, String frameHex) {
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

        List<ModbusServiceField> ordered = sorted(fields);
        int expected = 0;
        for (ModbusServiceField field : ordered) {
            expected += field.bytes;
        }

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

        boolean bitRegion = fc == FC_READ_COILS || fc == FC_READ_DISCRETE_INPUTS;
        JsonObject values = new JsonObject();
        int offset = HEADER_SIZE;
        for (ModbusServiceField field : ordered) {
            byte[] data = Arrays.copyOfRange(frame, offset, offset + field.bytes);
            values.put(field.field, decodeValue(field, data, bitRegion));
            putBits(values, field, data);
            offset += field.bytes;
        }
        return values;
    }

    /**
     * 位清单：把字段覆盖的位区里的若干位各自作为一个 0/1 取值补进结果（key 即该位的 field）。
     * 取位与功能码无关、只按该字段自己的数据字节 LSB-first 算：第 offset 位 = 第 offset/8 字节的第 offset%8 位。
     */
    private static void putBits(JsonObject values, ModbusServiceField field, byte[] data) {
        if (field.bitList == null) {
            return;
        }
        for (ModbusServiceFieldBit bit : field.bitList) {
            if (bit == null || bit.offset == null || bit.field == null || bit.offset < 0
                    || bit.offset / 8 >= data.length) {
                throw ModbusInvokeException.of(ModbusFailureType.FIELD_DEFINITION_ERROR,
                        "invalid modbus field bit: "
                                + (bit == null ? null : bit.offset) + ", field=" + field.field);
            }
            values.put(bit.field, (data[bit.offset / 8] >> (bit.offset % 8)) & 1);
        }
    }

    /** 按字段定义解出该段的最终取值（原始值 → value-list / scale）。 */
    private static Object decodeValue(ModbusServiceField field, byte[] data, boolean bitRegion) {
        String format = field.format == null ? "" : field.format.trim().toLowerCase();
        Object raw = switch (format) {
            case "string" -> text(data);
            case "int8", "uint8", "int16", "uint16", "int32", "uint32", "float32" ->
                    number(format, bits(data, field, bitRegion));
            default -> throw ModbusInvokeException.of(ModbusFailureType.FIELD_DEFINITION_ERROR,
                    "unsupported modbus field format: " + field.format);
        };

        String description = descriptionOf(field, raw);
        if (description != null) {
            return description;
        }
        if (raw instanceof Number value) {
            double scale = field.scale == null ? 1.0 : field.scale;
            return scale == 1.0 ? raw : value.doubleValue() * scale;
        }
        return raw;
    }

    /**
     * 数据区原始字节 → 位模式。
     *
     * <p>位区（01/02）按位数组读：字节低位在前（第 i 位 = 第 i/8 字节的第 i%8 位），故按小端拼装，
     * 掩码里的第 i 位就是第 i 个线圈；其余字段按声明的字节序拼装（单字节无需排布，多字节缺省 ABCD = 大端）。</p>
     */
    private static long bits(byte[] data, ModbusServiceField field, boolean bitRegion) {
        if (bitRegion) {
            long little = 0;
            for (int i = data.length - 1; i >= 0; i--) {
                little = (little << 8) | (data[i] & 0xFF);
            }
            return little;
        }
        byte[] ordered = data.length == 1 ? data : ModbusFrameCodec.reorder(data, field.byteOrder);
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

    /** 定长字符串：尾部常以 0x00 或空格补满，去掉尾部空白后再返回。 */
    private static String text(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) <= ' ') {
            end--;
        }
        return text.substring(0, end);
    }

    /** 命中取值表则返回其 description，否则 null。 */
    private static String descriptionOf(ModbusServiceField field, Object raw) {
        if (!(raw instanceof Number value) || field.valueList == null) {
            return null;
        }
        for (ModbusServiceFieldValue entry : field.valueList) {
            if (entry != null && entry.value != null && entry.value == value.longValue()) {
                return entry.description;
            }
        }
        return null;
    }

    private static List<ModbusServiceField> sorted(List<ModbusServiceField> fields) {
        List<ModbusServiceField> ordered = new ArrayList<>(fields);
        ordered.sort(Comparator.comparingInt(field -> field.index == null ? 0 : field.index));
        return ordered;
    }
}
