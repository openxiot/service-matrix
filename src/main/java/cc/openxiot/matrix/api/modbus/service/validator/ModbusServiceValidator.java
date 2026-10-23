package cc.openxiot.matrix.api.modbus.service.validator;

import cc.openxiot.matrix.api.modbus.alarm.ModbusAlarmPolicy;
import cc.openxiot.matrix.api.modbus.service.ModbusServiceService;
import cc.openxiot.matrix.typedef.modbus.frame.ModbusFrameCodec;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseField;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseFieldValue;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPollingFrom;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequest;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequestField;
import cc.openxiot.matrix.db.modbus.service.DeviceOn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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

    /** 请求功能码词表：01/02/03/04 读 · 05/06/0F/10 写。 */
    private static final Set<String> READ_FCS = Set.of("01", "02", "03", "04");
    private static final Set<String> WRITE_FCS = Set.of("05", "06", "0F", "10");

    /** 写字段的数据格式：位区（05/0F）只有 bit，寄存器区（06/10）只有 16/32 位数值类型。 */
    private static final Set<String> BIT_FORMATS = Set.of("bit");
    private static final Set<String> REGISTER_FORMATS = Set.of("int16", "uint16", "int32", "uint32", "float32");
    /** fc 06 只写一个寄存器：4 字节格式在这里会被静默截掉一半，必须挡在定义期。 */
    private static final Set<String> SINGLE_REGISTER_FORMATS = Set.of("int16", "uint16");

    /**
     * 一次读的数量上限（帧里那个数量字段的字面值）：03/04 是寄存器数（Modbus 规定 125），
     * 01/02 是位数（规定 2000）。
     */
    private static final int MAX_READ_REGISTERS = 125;
    private static final int MAX_READ_BITS = 2000;

    /**
     * 自动调用周期（秒）的上下限：下限 5 秒（RS485 是共享总线，周期太密会把总线吃满），
     * 上限 3600 秒（再长就该由人手动 invoke，而不是挂个几乎不跑的定时器）。
     *
     * <p>前端 device.service.editor.ts 里有一份同口径的常量（输入框的 nzMin/nzMax），改动要同步。</p>
     */
    private static final int MIN_INTERVAL_SECONDS = 5;
    private static final int MAX_INTERVAL_SECONDS = 3600;

    /**
     * 告警文本的长度上限（去首尾空白后算）：它是一条告警在人眼前的名字，不是备注栏。
     *
     * <p>前端 device.service.editor.html 里有一份同口径的常量（输入框的 maxlength），改动要同步。</p>
     */
    private static final int MAX_ALARM_TEXT_LENGTH = 64;

    /**
     * 一个出值（或一个位）能配的告警规则条数上限。
     *
     * <p>它<b>不是安全边界</b> —— 多几条规则只是多几次纯内存比较。这是给「界面上误加了一堆规则」
     * 一个明确的上限：分级的实际用法是低侧一条、高侧两三条，8 条已经宽裕。</p>
     *
     * <p>前端 device.service.editor.ts 里有一份同口径的常量（「添加规则」按钮的禁用点），改动要同步。</p>
     */
    private static final int MAX_ALARM_RULES = 8;

    /**
     * 规则标识的长度上限（见 {@link ModbusRegistryAlarm.id}）：前端生成的一小串随机码，
     * 不是给人看的，给足余量即可。
     */
    private static final int MAX_ALARM_ID_LENGTH = 64;

    private ModbusServiceValidator() {
    }

    /**
     * 整份定义：name / version / configSnapshot / device / functions 逐层校验。
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
        if (service.version != ModbusService.VERSION) {
            // 硬拒旧口径：version=1 的定义里 request 是一整串帧字符串，读成结构化对象只会得到
            // 一堆 null，然后报一句和病因无关的「request.slaveId is required」
            return "invalid version: " + service.version + " (this server serves version "
                    + ModbusService.VERSION + ")";
        }

        // 点表快照是服务的**定义来源**（寄存器缓存读侧投影直接读它，不再回查点表），
        // 缺了它服务就只剩下方法列表 —— 新口径一律要求带上；存量文档由迁移脚本补齐。
        if (service.configSnapshot == null) {
            return "configSnapshot is required";
        }
        // id 是溯源与统计分组键：快照可以没有 owner/lifecycle（会话裁剪），但必须知道自己取自哪条点表
        if (service.configSnapshot.id == null) {
            return "configSnapshot.id is required";
        }

        String deviceErr = checkDevice(service.device);
        if (deviceErr != null) {
            return deviceErr;
        }
        if (service.slaveId == null) {
            return "slaveId is required";
        }
        if (service.slaveId < 1 || service.slaveId > 247) {
            return "invalid slaveId: " + service.slaveId + " (must be 1..247)";
        }
        // 告警规则属寄存器（寄存器中心）：逐寄存器校验 configSnapshot.registries[].alarms
        String registryAlarmErr = validateRegistryAlarms(service.configSnapshot.registries, "configSnapshot");
        if (registryAlarmErr != null) {
            return registryAlarmErr;
        }
        return validateFunctions(service.functions, service.slaveId);
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

    private static String validateFunctions(List<ModbusPolling> functions, Integer serviceSlaveId) {
        if (functions == null) {
            return "functions is required";
        }
        Set<Integer> seenIndex = new HashSet<>();
        for (int i = 0; i < functions.size(); i++) {
            ModbusPolling function = functions.get(i);
            String base = "functions[" + i + "]";
            if (function == null) {
                return base + " is null";
            }
            // 方法身份：index 必填、>=1、同服务唯一 —— 轮询身份（modbus.polls.pollingIndex）、
            // 告警/影子的方法键都读它
            if (function.index == null) {
                return base + ".index is required";
            }
            if (function.index < 1) {
                return "invalid " + base + ".index: " + function.index
                        + " (index must be a natural number, 1-based)";
            }
            if (!seenIndex.add(function.index)) {
                return "invalid " + base + ".index: " + function.index + " (duplicated)";
            }
            // 来源：from 必填、type.type ∈ {registry, command}；from.index 仅作**溯源**
            // （指向 config 的 ModbusRegistry#index / ModbusCommand#index）—— 可选、给了必须 >=1，
            // 但**不**强制唯一（它不是方法身份，允许同服务的 command/registry 溯源到同一 config 序号）
            ModbusPollingFrom type = function.from;
            if (type == null) {
                return base + ".from is required";
            }
            if (type.type == null || type.type.isBlank()) {
                return base + ".from.type is required";
            }
            boolean registry = "registry".equals(type.type);
            if (!registry && !"command".equals(type.type)) {
                return "invalid " + base + ".from.type: " + type.type + " (must be registry or command)";
            }
            if (type.index != null && type.index < 1) {
                return "invalid " + base + ".from.index: " + type.index
                        + " (index must be a natural number, 1-based)";
            }
            // access 若给出必须落在 read/write 内：它只对 registry 来源有意义（command 的方向由 request.fc 决定）
            if (type.access != null && !"read".equals(type.access) && !"write".equals(type.access)) {
                return "invalid " + base + ".from.access: " + type.access + " (must be read or write)";
            }
            if (function.name == null || function.name.isBlank()) {
                return base + ".name is required";
            }
            String requestErr = validateRequest(function.request, base);
            if (requestErr != null) {
                return requestErr;
            }
            // 每个方法的 request.slaveId 必须等于服务级从站地址（service.slaveId）：
            // 服务级 slaveId 是本服务「读哪台从站」的顶层事实，方法里走私站的地址若不一致，
            // 会静默读到隔壁设备——这里硬校验。
            Integer slaveId = function.request.slaveId;
            if (!serviceSlaveId.equals(slaveId)) {
                return "invalid " + base + ".request.slaveId: " + slaveId
                        + " (must equal service slaveId " + serviceSlaveId + ")";
            }
            String pollingErr = validatePolling(function, base);
            if (pollingErr != null) {
                return pollingErr;
            }
            String intervalErr = validateInterval(function, base);
            if (intervalErr != null) {
                return intervalErr;
            }
            boolean read = isReadFunction(function);
            // registry（寄存器块读）必须是真读：它天然由读块展开，配写功能码说明是前端拼错了，
            // 而「写方法不许轮询、不许有应答」这两条在下面的通用校验里给的是另一套更费解的文案 ——
            // 在这里先说破「来源与方向打架」这一句，再让通用校验接手字段本身。
            if (registry && !read) {
                return "invalid " + base + ".from.type=registry: a registry poll must be a read function (fc 01/02/03/04), got fc "
                        + (function.request == null ? null : function.request.fc);
            }
            // registry 的应答解析规则不能没有：读方法缺 response 是静默失效（返回 {}），
            // 而 registry 的 request/response 都是前端从点表展开的，空应答只能是端点表时漏了。
            if (registry && ModbusPolling.responseFields(function).isEmpty()) {
                return base + ".response is required for a registry poll (the register-derived read block must declare fields)";
            }
            String responseErr = validateFields(ModbusPolling.responseFields(function), base);
            if (responseErr != null) {
                return responseErr;
            }
            // 对账放在字段校验之后：它要拿已经验过一遍的 bytes 去求和
            String reconcileErr = validateRequestResponse(function, base, read);
            if (reconcileErr != null) {
                return reconcileErr;
            }
        }
        return null;
    }

    /**
     * 请求定义（{@link ModbusPollingRequest}）：公共字段 → 按方向分叉。
     *
     * <p>读写方向由 {@code fc} 推出（与点表同一口径），且**决定另外两个字段谁有效**：
     * 读只有 {@code quantity}，写只有 {@code fields}，两者都给或都不给都是定义错误 ——
     * 这正是结构化之后白拿的那层校验：以前 {@code request} 是整整一帧，方向得靠
     * {@code parseHex(request)[1]} 猜，帧里的数量与 {@code response} 对不对得上也没人管。</p>
     */
    private static String validateRequest(ModbusPollingRequest request, String base) {
        String rbase = base + ".request";
        if (request == null) {
            return rbase + " is required";
        }
        if (request.slaveId == null) {
            return rbase + ".slaveId is required";
        }
        if (request.slaveId < 1 || request.slaveId > 247) {
            return "invalid " + rbase + ".slaveId: " + request.slaveId + " (must be 1..247)";
        }
        if (request.fc == null || request.fc.isBlank()) {
            return rbase + ".fc is required";
        }
        String fc = normalizeFc(request.fc);
        if (!READ_FCS.contains(fc) && !WRITE_FCS.contains(fc)) {
            return "invalid " + rbase + ".fc: " + request.fc + " (must be one of 01 02 03 04 05 06 0F 10)";
        }
        if (request.start == null) {
            return rbase + ".start is required";
        }
        if (request.start < 0 || request.start > 0xFFFF) {
            return "invalid " + rbase + ".start: " + request.start + " (must be 0..65535)";
        }
        return READ_FCS.contains(fc)
                ? validateReadRequest(request, rbase, fc)
                : validateWriteRequest(request, rbase, fc);
    }

    /** 读请求：数量必填并受 Modbus 上限约束；{@code fields} 是写请求的东西，出现在读请求里必须报错。 */
    private static String validateReadRequest(ModbusPollingRequest request, String rbase, String fc) {
        if (request.fields != null && !request.fields.isEmpty()) {
            return "invalid " + rbase + ".fields: not allowed on a read function (fc " + fc + ")";
        }
        if (request.quantity == null) {
            return rbase + ".quantity is required";
        }
        boolean bits = "01".equals(fc) || "02".equals(fc);
        int max = bits ? MAX_READ_BITS : MAX_READ_REGISTERS;
        if (request.quantity < 1 || request.quantity > max) {
            return "invalid " + rbase + ".quantity: " + request.quantity + " (must be 1.." + max
                    + (bits ? " bits" : " registers") + ")";
        }
        return null;
    }

    /**
     * 写请求：{@code fields} 必填、{@code quantity} 必须不填（帧里的数量由字段推出），
     * 逐字段校验后再按 index 升序核对偏移首尾相接。
     */
    private static String validateWriteRequest(ModbusPollingRequest request, String rbase, String fc) {
        if (request.quantity != null) {
            return "invalid " + rbase + ".quantity: " + request.quantity
                    + " (not allowed on a write function (fc " + fc + "), the frame's count comes from fields)";
        }
        List<ModbusPollingRequestField> fields = request.fields;
        if (fields == null || fields.isEmpty()) {
            return rbase + ".fields is required (a write function needs the fields it writes)";
        }

        boolean bits = "05".equals(fc) || "0F".equals(fc);
        boolean single = "05".equals(fc) || "06".equals(fc);
        if (single && fields.size() != 1) {
            return "invalid " + rbase + ".fields: fc " + fc + " writes exactly one field, got " + fields.size();
        }
        Set<String> formats = bits ? BIT_FORMATS
                : ("06".equals(fc) ? SINGLE_REGISTER_FORMATS : REGISTER_FORMATS);

        Set<Integer> seenIndex = new HashSet<>();
        Set<String> seenField = new HashSet<>();
        List<ModbusPollingRequestField> ordered = new ArrayList<>(fields);
        for (int i = 0; i < fields.size(); i++) {
            ModbusPollingRequestField field = fields.get(i);
            String fbase = rbase + ".fields[" + i + "]";
            if (field == null) {
                return fbase + " is null";
            }
            if (field.index == null) {
                return fbase + ".index is required";
            }
            if (field.index < 1) {
                return "invalid " + fbase + ".index: " + field.index + " (index must be a natural number, 1-based)";
            }
            if (!seenIndex.add(field.index)) {
                return "invalid " + fbase + ".index: " + field.index + " (duplicated)";
            }
            if (field.field == null || field.field.isBlank()) {
                return fbase + ".field is required";
            }
            if (!seenField.add(field.field)) {
                return "invalid " + fbase + ".field: " + field.field + " (duplicated)";
            }
            if (field.format == null || field.format.isBlank()) {
                return fbase + ".format is required";
            }
            String format = normalizeFormat(field.format);
            if (!formats.contains(format)) {
                return "invalid " + fbase + ".format: " + field.format + " (fc " + fc + " writes "
                        + String.join(" ", new TreeSet<>(formats)) + ")";
            }
            if (single && field.offset != null) {
                // fc 05/06 的帧里没有「第几个」可言，写了偏移说明是按多值那条路想的
                return "invalid " + fbase + ".offset: " + field.offset + " (fc " + fc + " writes one value)";
            }
            if (!bits && registerWidth(format) == 4) {
                if (field.byteOrder == null || field.byteOrder.isBlank()) {
                    return fbase + ".byteOrder is required";
                }
            }
            if (field.byteOrder != null && !field.byteOrder.isBlank()
                    && !BYTE_ORDERS.contains(field.byteOrder)) {
                return "invalid " + fbase + ".byteOrder: " + field.byteOrder;
            }
            String valueErr = validateWriteValue(field.value, format, bits, fbase + ".value");
            if (valueErr != null) {
                return valueErr;
            }
        }

        if (single) {
            return null;
        }
        return validateWriteOffsets(ordered, rbase, bits);
    }

    /**
     * 写字段的偏移必须**按 index 升序首尾相接**（0F 是位、10 是寄存器）：fc 0F/10 的帧只有
     * 「起始地址 + 个数 + 紧凑数据区」，没有空洞可言 —— 点表那条线的
     * {@code ModbusFrameCodec.writeMultipleRegistersBody} 也正是这么要求的。
     */
    private static String validateWriteOffsets(List<ModbusPollingRequestField> fields, String rbase, boolean bits) {
        fields.sort(Comparator.comparingInt(field -> field.index));
        int position = 0;
        for (ModbusPollingRequestField field : fields) {
            if (!Objects.equals(field.offset, position)) {
                return "invalid " + rbase + ".fields: " + (bits ? "bit" : "register") + " offset of field "
                        + field.field + " is " + field.offset + " (must be " + position
                        + ", fields are ordered by index without gaps)";
            }
            position += bits ? 1 : registerWidth(normalizeFormat(field.format)) / 2;
        }
        return null;
    }

    /**
     * 请求 ↔ 应答对账（结构化之后白拿的那层校验）：
     *
     * <ul>
     *   <li>读：{@code Σ response.fields[].bytes} 必须等于这一读实际取回的字节数
     *       （01/02 = {@code ceil(quantity / 8)}、03/04 = {@code quantity * 2}）。</li>
     *   <li>读方法必须有 {@code response}：没有解析规则的读方法返回 {@code {}}，是静默失效。</li>
     *   <li>写方法必须没有 {@code response}：写应答是请求回显，解析规则无从谈起。</li>
     * </ul>
     */
    private static String validateRequestResponse(ModbusPolling function, String base, boolean read) {
        List<ModbusPollingResponseField> fields = ModbusPolling.responseFields(function);
        if (!read) {
            if (!fields.isEmpty()) {
                return "invalid " + base + ".response: not allowed on a write function (the reply echoes the request)";
            }
            return null;
        }
        if (fields.isEmpty()) {
            return base + ".response is required for a read function (a method with no field returns {})";
        }

        int declared = 0;
        for (ModbusPollingResponseField field : fields) {
            declared += field.bytes;
        }
        ModbusPollingRequest request = function.request;
        String fc = normalizeFc(request.fc);
        int expected = ("01".equals(fc) || "02".equals(fc))
                ? (request.quantity + 7) / 8
                : request.quantity * 2;
        if (declared != expected) {
            return "invalid " + base + ".response: declares " + declared + " bytes, but fc " + fc
                    + " with quantity " + request.quantity + " reads " + expected;
        }
        return null;
    }

    /**
     * 一个写字段的取值自洽性（定义期的缺省值、执行期的 invoke 入参共用这一处）：
     * 位是布尔，寄存器是有限数值，整数格式还必须是整数值且不越界。
     *
     * <p>「整数值」按**值本身**判而不是按 Java 类型判：请求体过 Jackson 之后
     * {@code 2} 可能是 {@code Integer}、也可能是 {@code Double}（而 {@code Double} 能精确表示
     * 全部 int32/uint32，按类型判会把合法值挡在外面）。</p>
     *
     * @param format 已归一的小写格式（{@code bit} 或寄存器格式）
     * @param bits   {@code format} 是不是位格式
     * @param vbase  出错文案里的取值位置，如 {@code functions[0].request.fields[1].value}
     */
    private static String validateWriteValue(Object value, String format, boolean bits, String vbase) {
        if (value == null) {
            return null;
        }
        if (bits) {
            return value instanceof Boolean ? null
                    : "invalid " + vbase + ": " + value + " (a bit is true or false)";
        }
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            return "invalid " + vbase + ": " + value + " (a register value is a finite number)";
        }
        if ("float32".equals(format)) {
            return null;
        }
        double d = number.doubleValue();
        if (d != Math.rint(d)) {
            return "invalid " + vbase + ": " + value + " (format " + format + " needs an integer)";
        }
        long bits32 = (long) d;
        boolean ok = switch (format) {
            case "int16" -> bits32 >= Short.MIN_VALUE && bits32 <= Short.MAX_VALUE;
            case "uint16" -> bits32 >= 0 && bits32 <= 0xFFFFL;
            case "int32" -> bits32 >= Integer.MIN_VALUE && bits32 <= Integer.MAX_VALUE;
            case "uint32" -> bits32 >= 0 && bits32 <= 0xFFFFFFFFL;
            default -> true;
        };
        return ok ? null : "invalid " + vbase + ": " + value + " (out of range for " + format + ")";
    }

    /** 写字段的寄存器跨度（字段的 format 必须已经过 {@link #validateWriteRequest}）。 */
    private static int registerWidth(String format) {
        return switch (format) {
            case "int32", "uint32", "float32" -> 4;
            default -> 2;
        };
    }

    /** 功能码归一到大写：{@code "0f"} 与 {@code "0F"} 是同一个东西（词表都是大写）。 */
    private static String normalizeFc(String fc) {
        return fc == null ? "" : fc.trim().toUpperCase(Locale.ROOT);
    }

    /** 数据格式归一到小写：{@code "UINT16"} 与 {@code "uint16"} 是同一个东西（词表都是小写）。 */
    private static String normalizeFormat(String format) {
        return format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 自动轮询开关（{@link ModbusPolling#polling}）：缺省 / false 都不必校验 ——
     * 没开轮询时 {@code interval} 只是一份没生效的配置（false 的用法就是「留着周期、先不跑」）。
     *
     * <p>{@code true} 有两条要求，都在这里说清楚：<b>只允许读方法</b>（写方法开轮询 = 让定时器
     * 周期性往寄存器里写值，是危险动作），以及开了轮询必须有周期（否则服务端无从知道多久调一次）。
     * 前者以前是间接成立的（{@code polling=true} 要求 {@code interval}，而 {@code interval}
     * 不允许配在写方法上），绕了一层，读代码的人看不出「写方法一律不许轮询」这条规矩。</p>
     */
    private static String validatePolling(ModbusPolling function, String base) {
        if (!Boolean.TRUE.equals(function.polling)) {
            return null;
        }
        if (!isReadFunction(function)) {
            return "invalid " + base + ".polling: " + function.polling
                    + " (only read functions (fc 01/02/03/04) can be polled)";
        }
        if (function.interval == null) {
            return "invalid " + base + ".polling: " + function.polling + " (polling requires an interval)";
        }
        // 周期本身的取值范围与「只允许读方法」交给 validateInterval
        return null;
    }

    /**
     * 自动调用周期（{@link ModbusPolling#interval}，秒）：缺省 = 没配周期。
     * 配了周期不等于会跑 —— 跑不跑看 {@link ModbusPolling#polling}（缺省 = 有周期即跑）。
     *
     * <p><b>只允许读方法</b>（fc 01/02/03/04）：写方法的应答是请求回显，周期调用等于让服务端
     * 周期性地往寄存器里写值，是危险动作，不能由定时器发起。</p>
     */
    private static String validateInterval(ModbusPolling function, String base) {
        Integer interval = function.interval;
        if (interval == null) {
            return null;
        }
        if (!isReadFunction(function)) {
            return "invalid " + base + ".interval: " + interval
                    + " (only read functions (fc 01/02/03/04) can be polled)";
        }
        if (interval < MIN_INTERVAL_SECONDS || interval > MAX_INTERVAL_SECONDS) {
            return "invalid " + base + ".interval: " + interval
                    + " (interval must be between " + MIN_INTERVAL_SECONDS + " and " + MAX_INTERVAL_SECONDS + " seconds)";
        }
        return null;
    }

    /**
     * 是不是读方法（fc 01/02/03/04）。周期与告警共用这一处判断 —— 两条规则都只允许配在读方法上
     * （读方法才有「读到的值」），各自抄一遍迟早会漂移。
     *
     * <p>功能码直接读 {@code request.fc}：结构化了就是这一处白拿的好处 —— 以前要从请求帧里
     * {@code parseHex(request)[1]} 取第二个字节，还得为库里的脏数据包一层 try/catch。</p>
     */
    private static boolean isReadFunction(ModbusPolling function) {
        return function.request != null && ModbusFrameCodec.isRead(function.request.fc);
    }

    /**
     * invoke 入参（{@link cc.openxiot.matrix.api.modbus.service.params.InvokeModbusServiceRequest#values}）
     * 与写方法的字段定义对账。
     *
     * <p><b>读方法给了 values 要报错</b>：静默忽略一个用户以为生效了的载荷，比报错糟得多。</p>
     *
     * <p>写方法逐字段查：没有缺省值的字段必须给值、给了未声明的字段名报错、类型不符 / 越界报错
     * （取值判定与定义期的缺省值共用 {@link #validateWriteValue}）。</p>
     *
     * <p>这是**调用方的请求错误**，与「function not found」同类，由调用链在 {@code execute} 之前
     * 拦下，因此不进 {@code service-failures}（那是设备故障记录）。</p>
     *
     * @param values 可为 null（读方法，或写方法的字段全都带缺省值）
     */
    public static String validateInvoke(ModbusPolling function, Map<String, Object> values) {
        if (function == null || function.request == null) {
            return "request is not set";
        }
        ModbusPollingRequest request = function.request;
        if (isReadFunction(function)) {
            if (values != null && !values.isEmpty()) {
                return "invalid values: a read function (fc " + request.fc + ") takes no values";
            }
            return null;
        }

        List<ModbusPollingRequestField> fields = request.fields;
        if (fields == null || fields.isEmpty()) {
            return "request.fields is not set for a write function";
        }
        String fc = normalizeFc(request.fc);
        boolean bits = "05".equals(fc) || "0F".equals(fc);

        Set<String> declared = new HashSet<>();
        for (ModbusPollingRequestField field : fields) {
            declared.add(field.field);
            Object value = values == null ? null : values.get(field.field);
            if (value == null) {
                if (field.value == null) {
                    return "values." + field.field + " is required (this field has no default)";
                }
                continue;
            }
            String err = validateWriteValue(value, normalizeFormat(field.format), bits, "values." + field.field);
            if (err != null) {
                return err;
            }
        }
        if (values != null) {
            for (String name : values.keySet()) {
                if (!declared.contains(name)) {
                    return "invalid values." + name + ": not a field of this function";
                }
            }
        }
        return null;
    }

    private static String validateFields(List<ModbusPollingResponseField> fields, String base) {
        if (fields == null) {
            return null;
        }
        Set<Integer> seenIndex = new HashSet<>();
        // 同一应答内所有出值名（各字段名 + 各位名）必须唯一：parser 把它们放进同一个结果对象，重名会互相覆盖
        Set<String> seenField = new HashSet<>();
        for (int j = 0; j < fields.size(); j++) {
            ModbusPollingResponseField field = fields.get(j);
            String ibase = base + ".response.fields[" + j + "]";
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
            if (!seenField.add(field.field)) {
                return "invalid " + ibase + ".field: " + field.field + " (duplicated)";
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
            String bitListErr = validateBitList(field, ibase, seenField);
            if (bitListErr != null) {
                return bitListErr;
            }
        }
        return null;
    }

    /**
     * 校验位清单（{@link ModbusPollingResponseField#bitList}，01/02 位区）：偏移必须落在该字段覆盖的位区间内
     * （{@code 0 <= offset < bytes * 8}）、不重复，名称非空且在整份应答内唯一（出值名会进同一个结果对象）。
     *
     * <p>位是**独立的结果键**（parser 把每个命名位单独放进结果对象），所以告警配在位上而不是父字段上：
     * 父字段那份是整个位掩码，与「进水阀开没开」不是一回事。</p>
     */
    private static String validateBitList(ModbusPollingResponseField field, String ibase, Set<String> seenField) {
        if (field.bitList == null || field.bitList.isEmpty()) {
            return null;
        }
        Set<Integer> seenOffset = new HashSet<>();
        for (int k = 0; k < field.bitList.size(); k++) {
            var bit = field.bitList.get(k);
            String bbase = ibase + ".bit-list[" + k + "]";
            if (bit == null || bit.offset == null) {
                return bbase + ".offset is required";
            }
            if (bit.offset < 0 || bit.offset >= field.bytes * 8) {
                return "invalid " + bbase + ".offset: " + bit.offset
                        + " (field covers " + field.bytes * 8 + " bits)";
            }
            if (!seenOffset.add(bit.offset)) {
                return "invalid " + bbase + ".offset: " + bit.offset + " (duplicated)";
            }
            if (bit.field == null || bit.field.isBlank()) {
                return bbase + ".field is required";
            }
            if (!seenField.add(bit.field)) {
                return "invalid " + bbase + ".field: " + bit.field + " (duplicated)";
            }
        }
        return null;
    }

    /**
     * 寄存器的**一组**阈值告警规则（{@link ModbusRegistry#alarms}）：逐条校验，
     * 外加两条只有「一组」才有意义的要求 —— 条数上限，以及启用规则的 {@code id} 非空、不超长、组内唯一。
     *
     * <p>{@code id} 是「这条开着的告警是哪条规则开的」的唯一依据（见 {@link ModbusRegistryAlarm#id}），
     * 所以它必须存在且唯一。<b>只在 {@code enabled == true} 时查</b>，与下面 {@link #validateAlarm}
     * 同一条规矩：停用的规则不参与判定，它的 {@code id} 不影响任何事 —— 半配置合法。</p>
     *
     * @param abase 出错文案里的位置，如 {@code configSnapshot.registries[0].alarms}
     */
    static String validateAlarms(List<ModbusRegistryAlarm> alarms,
                                 ModbusRegistry reg,
                                 String abase) {
        if (alarms == null || alarms.isEmpty()) {
            return null;
        }
        if (alarms.size() > MAX_ALARM_RULES) {
            return "invalid " + abase + ": too many rules (" + MAX_ALARM_RULES + " max)";
        }
        boolean isBit = reg != null && ("coil".equals(reg.area) || "discrete".equals(reg.area));
        boolean readable = reg == null || !"W".equals(reg.access);
        Set<String> seenId = new HashSet<>();
        for (int k = 0; k < alarms.size(); k++) {
            ModbusRegistryAlarm alarm = alarms.get(k);
            String rbase = abase + "[" + k + "]";
            if (alarm == null) {
                return rbase + " is null";
            }
            if (ModbusAlarmPolicy.isEnabled(alarm)) {
                if (alarm.id == null || alarm.id.isBlank()) {
                    return rbase + ".id is required";
                }
                if (alarm.id.trim().length() > MAX_ALARM_ID_LENGTH) {
                    return "invalid " + rbase + ".id: too long (" + MAX_ALARM_ID_LENGTH + " characters max)";
                }
                if (!seenId.add(alarm.id)) {
                    return "invalid " + rbase + ".id: " + alarm.id + " (duplicated)";
                }
            }
            String err = validateAlarm(alarm, reg, isBit, readable, rbase);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    private static String validateRegistryAlarms(List<ModbusRegistry> registries, String base) {
        if (registries == null) {
            return null;
        }
        for (int i = 0; i < registries.size(); i++) {
            ModbusRegistry reg = registries.get(i);
            if (reg == null) {
                continue;
            }
            String err = validateAlarms(reg.alarms, reg, base + ".registries[" + i + "].alarms");
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    /**
     * 一条阈值告警规则（{@link ModbusRegistryAlarm}）。
     *
     * <p><b>只在 {@code enabled == true} 时校验</b> —— 半配置合法，与 {@link #validatePolling}
     * 同一条规矩：false 的用法就是「把配置留着、先不报」。所以下面的规则读作「启用之后必须自洽」。
     * 判定口径（{@link ModbusAlarmPolicy}）共用 {@code OPERATORS} / {@code LEVELS} 两张白名单，
     * 不各写一份。</p>
     *
     * <p>「能比什么」由寄存器的取值形态决定，而不是留给运行期碰运气：{@code string} 寄存器没有可比的量，
     * 一律不收；带 {@code enumMap} 的寄存器出的是显示名，只能用 {@code =} 比 {@code state}
     * —— 给它配 {@code >} 会让比较随原始值是否命中枚举映射而时灵时不灵，正是要避免的静默失效。</p>
     *
     * @param reg      配置所在寄存器：借它的 dataType / enumMap / area（isBit）判断可比性
     * @param isBit    寄存器区是 coil/discrete（值恒为 0/1，只能比 0 或 1）
     * @param readable 寄存器是否可读（access 非 W）——只读不可读；不可读的寄存器没有「读到的值」，不收告警
     * @param abase    出错文案里的位置，如 {@code configSnapshot.registries[0].alarms[0]}
     */
    private static String validateAlarm(ModbusRegistryAlarm alarm,
                                        ModbusRegistry reg,
                                        boolean isBit,
                                        boolean readable,
                                        String abase) {
        if (!ModbusAlarmPolicy.isEnabled(alarm)) {
            return null;
        }
        if (!readable) {
            return "invalid " + abase + ": only read functions (fc 01/02/03/04) can have alarms";
        }
        if (alarm.compare == null || !ModbusAlarmPolicy.OPERATORS.contains(alarm.compare)) {
            // 白名单里逐字列出，不用 OPERATORS 拼串：Set.of 的迭代顺序不保证，文案会随机
            return "invalid " + abase + ".compare: " + alarm.compare + " (must be one of > >= < <= =)";
        }
        if (alarm.level == null || !ModbusAlarmPolicy.LEVELS.contains(alarm.level)) {
            return "invalid " + abase + ".level: " + alarm.level + " (must be one of INFO WARN CRITICAL)";
        }
        if (alarm.text == null || alarm.text.isBlank()) {
            return abase + ".text is required";
        }
        if (alarm.text.trim().length() > MAX_ALARM_TEXT_LENGTH) {
            return "invalid " + abase + ".text: too long (" + MAX_ALARM_TEXT_LENGTH + " characters max)";
        }
        String dataType = reg == null ? null : reg.dataType;
        if (dataType != null && "string".equals(dataType.trim().toLowerCase(Locale.ROOT))) {
            return "invalid " + abase + ": a string field has no comparable value";
        }

        boolean named = reg != null && reg.enumMap != null && !reg.enumMap.isEmpty();
        if ("=".equals(alarm.compare)) {
            if (named) {
                if (alarm.threshold != null) {
                    return "invalid " + abase + ".threshold: not allowed on a value-list field (use state)";
                }
                if (alarm.state == null || alarm.state.isBlank()) {
                    return abase + ".state is required (compare = on a value-list field)";
                }
                if (!hasDescription(reg, alarm.state)) {
                    return "invalid " + abase + ".state: " + alarm.state + " (not in this field's value-list)";
                }
                return null;
            }
            if (alarm.state != null) {
                return "invalid " + abase + ".state: only allowed on a value-list field (use threshold)";
            }
            if (alarm.threshold == null) {
                return abase + ".threshold is required (compare =)";
            }
            if (!Double.isFinite(alarm.threshold)) {
                return "invalid " + abase + ".threshold: " + alarm.threshold;
            }
            if (isBit && alarm.threshold != 0 && alarm.threshold != 1) {
                return "invalid " + abase + ".threshold: " + alarm.threshold + " (a bit is 0 or 1, use =)";
            }
            return null;
        }

        // > >= < <=：只对数值有意义
        if (isBit) {
            return "invalid " + abase + ".compare: " + alarm.compare + " (a bit is 0 or 1, use =)";
        }
        if (named) {
            return "invalid " + abase + ".compare: " + alarm.compare
                    + " (a value-list field yields names, use =)";
        }
        if (alarm.state != null) {
            return "invalid " + abase + ".state: only allowed when compare is =";
        }
        if (alarm.threshold == null) {
            return abase + ".threshold is required (compare " + alarm.compare + ")";
        }
        if (!Double.isFinite(alarm.threshold)) {
            return "invalid " + abase + ".threshold: " + alarm.threshold;
        }
        return null;
    }

    /** {@code state} 是否逐字命中枚举映射里的某个显示名 —— 判定时就按这个字符串相等去比。 */
    private static boolean hasDescription(ModbusRegistry reg, String state) {
        if (reg == null || reg.enumMap == null) {
            return false;
        }
        return reg.enumMap.containsValue(state);
    }

    private static String validateValueList(ModbusPollingResponseField field, String ibase) {
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
