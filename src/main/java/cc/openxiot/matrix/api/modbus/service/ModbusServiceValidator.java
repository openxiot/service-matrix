package cc.openxiot.matrix.api.modbus.service;

import cc.openxiot.matrix.api.modbus.frame.ModbusFrameCodec;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import cc.openxiot.matrix.db.modbus.service.DeviceOn;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@link ModbusService} 定义的内容校验（规则见 MODBUS.md 第七节）。
 *
 * <p>纯函数、无状态、不碰数据库：返回 {@code null} 表示通过，否则返回给前端看的错误文案。
 * 调用方（{@link ModbusServiceService}）把它转成 {@link IllegalArgumentException}。</p>
 */
public final class ModbusServiceValidator {

    /** 应答字段的数据格式词表（字节宽度见 {@link #formatWidth}）。 */
    private static final Set<String> FORMATS = Set.of("int8", "uint8", "int16", "uint16", "int32", "uint32", "float32", "string");
    private static final Set<String> BYTE_ORDERS = Set.of("ABCD", "DCBA", "BADC", "CDAB");

    private ModbusServiceValidator() {
    }

    /**
     * 整份定义：name / version / device / functions 逐层校验。
     *
     * <p>{@code orgId} 不在校验之列：它只记录创建时的组织，不参与鉴权（按空间判）与查询。</p>
     */
    public static String validate(ModbusService service) {
        if (service == null) {
            return "body is required";
        }
        if (service.name == null || service.name.isBlank()) {
            return "name is required";
        }
        if (service.version == null) {
            return "version is required";
        }

        String deviceErr = checkDevice(service.device);
        if (deviceErr != null) {
            return deviceErr;
        }
        return validateFunctions(service.functions);
    }

    /** 依赖设备的调用坐标：invoke 前也要查一遍（文档可能来自更早的口径）。 */
    public static String checkDevice(DeviceOn device) {
        if (device == null) {
            return "device is required";
        }
        if (device.did == null || device.did.isBlank()) {
            return "device.did is required";
        }
        if (device.siid == null || device.siid < 1) {
            return "device.siid is required";
        }
        if (device.aiid == null || device.aiid < 1) {
            return "device.aiid is required";
        }
        if (device.argument == null || device.argument < 1) {
            return "device.argument is required";
        }
        return null;
    }

    private static String validateFunctions(List<ModbusServiceFunction> functions) {
        if (functions == null) {
            return "functions is required";
        }
        Set<Integer> seenIndex = new HashSet<>();
        for (int i = 0; i < functions.size(); i++) {
            ModbusServiceFunction function = functions.get(i);
            String base = "functions[" + i + "]";
            if (function == null) {
                return base + " is null";
            }
            if (function.index == null) {
                return base + ".index is required";
            }
            if (function.index < 1) {
                return "invalid " + base + ".index: " + function.index + " (index must be a natural number, 1-based)";
            }
            if (!seenIndex.add(function.index)) {
                return "invalid " + base + ".index: " + function.index + " (duplicated)";
            }
            if (function.name == null || function.name.isBlank()) {
                return base + ".name is required";
            }
            String requestErr = validateRequest(function.request, base);
            if (requestErr != null) {
                return requestErr;
            }
            String responseErr = validateFields(function.response, base);
            if (responseErr != null) {
                return responseErr;
            }
        }
        return null;
    }

    /** 请求帧由前端生成：这里只校验它是合法且完整的 16 进制（CRC 是否正确留给运行期应答校验）。 */
    private static String validateRequest(String request, String base) {
        if (request == null || request.isBlank()) {
            return base + ".request is required";
        }
        try {
            if (ModbusFrameCodec.parseHex(request).length < 4) {
                return "invalid " + base + ".request: too short";
            }
        } catch (IllegalArgumentException e) {
            return "invalid " + base + ".request: " + request;
        }
        return null;
    }

    private static String validateFields(List<ModbusServiceField> fields, String base) {
        if (fields == null) {
            return null;
        }
        Set<Integer> seenIndex = new HashSet<>();
        for (int j = 0; j < fields.size(); j++) {
            ModbusServiceField field = fields.get(j);
            String ibase = base + ".response[" + j + "]";
            if (field == null) {
                return ibase + " is null";
            }
            if (field.index == null) {
                return ibase + ".index is required";
            }
            if (field.index < 1) {
                return "invalid " + ibase + ".index: " + field.index + " (index must be a natural number, 1-based)";
            }
            if (!seenIndex.add(field.index)) {
                return "invalid " + ibase + ".index: " + field.index + " (duplicated)";
            }
            if (field.field == null || field.field.isBlank()) {
                return ibase + ".field is required";
            }
            if (field.bytes == null || field.bytes < 1) {
                return ibase + ".bytes is required";
            }
            if (field.format == null || field.format.isBlank()) {
                return ibase + ".format is required";
            }
            String format = field.format.trim().toLowerCase(Locale.ROOT);
            if (!FORMATS.contains(format)) {
                return "invalid " + ibase + ".format: " + field.format;
            }
            Integer width = formatWidth(format);
            if (width != null && !width.equals(field.bytes)) {
                return "invalid " + ibase + ".bytes: " + field.bytes + " (format " + format + " needs " + width + ")";
            }
            if (field.bytes > 1) {
                if (field.byteOrder == null || field.byteOrder.isBlank()) {
                    return ibase + ".byteOrder is required";
                }
                if (!BYTE_ORDERS.contains(field.byteOrder)) {
                    return "invalid " + ibase + ".byteOrder: " + field.byteOrder;
                }
            }
            String valueListErr = validateValueList(field, ibase);
            if (valueListErr != null) {
                return valueListErr;
            }
        }
        return null;
    }

    private static String validateValueList(ModbusServiceField field, String ibase) {
        if (field.valueList == null) {
            return null;
        }
        Set<Long> seen = new HashSet<>();
        for (int k = 0; k < field.valueList.size(); k++) {
            var entry = field.valueList.get(k);
            String vbase = ibase + ".value-list[" + k + "]";
            if (entry == null || entry.value == null) {
                return vbase + ".value is required";
            }
            if (entry.description == null || entry.description.isBlank()) {
                return vbase + ".description is required";
            }
            if (!seen.add(entry.value)) {
                return "invalid " + vbase + ".value: " + entry.value + " (duplicated)";
            }
        }
        return null;
    }

    /** 类型占用的字节数：8 位→1、16 位→2、32 位→4、string→不固定（null，由 bytes 决定）。 */
    private static Integer formatWidth(String format) {
        return switch (format) {
            case "int8", "uint8" -> 1;
            case "int16", "uint16" -> 2;
            case "int32", "uint32", "float32" -> 4;
            default -> null;
        };
    }
}
