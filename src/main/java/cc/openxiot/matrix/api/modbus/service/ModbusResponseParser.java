package cc.openxiot.matrix.api.modbus.service;

import cc.openxiot.matrix.api.device.interceptor.modbus.ModbusFrameCodec;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
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
 */
public final class ModbusResponseParser {

    /** slave + fc + byteCount */
    private static final int HEADER_SIZE = 3;
    /** slave + fc + 数据 + crc16(2) */
    private static final int MIN_FRAME_SIZE = 5;
    private static final int CRC_SIZE = 2;
    private static final int ERROR_MASK = 0x80;

    private ModbusResponseParser() {
    }

    /**
     * 解一帧应答（按字段定义切分数据区并取值）。
     *
     * @param fields   该方法的应答字段定义（顺序由各自的 index 决定）
     * @param frameHex 父设备回传的应答帧（16 进制字符串，大小写与空白不敏感）
     * @throws IllegalArgumentException 帧非法、CRC 不符、从站返回异常码，或字段声明与帧长对不上
     */
    public static JsonObject parse(List<ModbusServiceField> fields, String frameHex) {
        byte[] frame = ModbusFrameCodec.parseHex(frameHex);
        if (frame.length < MIN_FRAME_SIZE) {
            throw new IllegalArgumentException("invalid modbus response: " + frameHex);
        }

        int fc = frame[1] & 0xFF;
        if ((fc & ERROR_MASK) != 0) {
            throw new IllegalArgumentException("modbus exception response: code=" + (frame[2] & 0xFF)
                    + ", frame=" + frameHex);
        }
        if (!ModbusFrameCodec.checkCrc(frame)) {
            throw new IllegalArgumentException("modbus response crc mismatch: " + frameHex);
        }

        List<ModbusServiceField> ordered = sorted(fields);
        int expected = 0;
        for (ModbusServiceField field : ordered) {
            expected += field.bytes;
        }

        int byteCount = frame[2] & 0xFF;
        if (byteCount != expected) {
            throw new IllegalArgumentException("modbus response length mismatch: byteCount=" + byteCount
                    + ", expected=" + expected + ", frame=" + frameHex);
        }
        if (HEADER_SIZE + byteCount > frame.length - CRC_SIZE) {
            throw new IllegalArgumentException("invalid modbus response: " + frameHex);
        }

        JsonObject values = new JsonObject();
        int offset = HEADER_SIZE;
        for (ModbusServiceField field : ordered) {
            byte[] data = Arrays.copyOfRange(frame, offset, offset + field.bytes);
            values.put(field.field, decodeValue(field, data));
            offset += field.bytes;
        }
        return values;
    }

    /** 按字段定义解出该段的最终取值（原始值 → value-list / scale）。 */
    private static Object decodeValue(ModbusServiceField field, byte[] data) {
        String format = field.format == null ? "" : field.format.trim().toLowerCase();
        Object raw = switch (format) {
            case "string" -> text(data);
            case "int8", "uint8", "int16", "uint16", "int32", "uint32", "float32" -> number(format, bits(data, field));
            default -> throw new IllegalArgumentException("unsupported modbus field format: " + field.format);
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

    /** 数据区原始字节 → 按字节序排布后的位模式（单字节无需排布）。 */
    private static long bits(byte[] data, ModbusServiceField field) {
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
