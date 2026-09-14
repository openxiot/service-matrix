package cc.openxiot.matrix.api.modbus.service.validator;

import cc.openxiot.matrix.api.modbus.alarm.ModbusAlarmPolicy;
import cc.openxiot.matrix.api.modbus.service.ModbusServiceService;
import cc.openxiot.matrix.typedef.modbus.frame.ModbusFrameCodec;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldValue;
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
     * 规则标识的长度上限（见 {@link ModbusServiceFieldAlarm#id}）：前端生成的一小串随机码，
     * 不是给人看的，给足余量即可。
     */
    private static final int MAX_ALARM_ID_LENGTH = 64;

    private ModbusServiceValidator() {
    }

    /**
     * 整份定义：name / version / device / functions 逐层校验。
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
            String pollingErr = validatePolling(function, base);
            if (pollingErr != null) {
                return pollingErr;
            }
            String intervalErr = validateInterval(function, base);
            if (intervalErr != null) {
                return intervalErr;
            }
            String responseErr = validateFields(function.response, base, isReadFunction(function));
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

    /**
     * 自动轮询开关（{@link ModbusServiceFunction#polling}）：缺省 / false 都不必校验 ——
     * 没开轮询时 {@code interval} 只是一份没生效的配置（false 的用法就是「留着周期、先不跑」）。
     *
     * <p>只有 {@code true} 有话说：开了轮询却没有周期，服务端无从知道多久调一次，
     * 这种定义是自相矛盾的，直接拒。</p>
     */
    private static String validatePolling(ModbusServiceFunction function, String base) {
        if (!Boolean.TRUE.equals(function.polling)) {
            return null;
        }
        if (function.interval == null) {
            return "invalid " + base + ".polling: " + function.polling + " (polling requires an interval)";
        }
        // 周期本身的取值范围与「只允许读方法」交给 validateInterval
        return null;
    }

    /**
     * 自动调用周期（{@link ModbusServiceFunction#interval}，秒）：缺省 = 没配周期。
     * 配了周期不等于会跑 —— 跑不跑看 {@link ModbusServiceFunction#polling}（缺省 = 有周期即跑）。
     *
     * <p><b>只允许读方法</b>（fc 01/02/03/04）：写方法的应答是请求回显，周期调用等于让服务端
     * 周期性地往寄存器里写值，是危险动作，不能由定时器发起。</p>
     */
    private static String validateInterval(ModbusServiceFunction function, String base) {
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
     * <p>功能码取请求帧的第二个字节：调用前 {@code request} 必须已经过 {@link #validateRequest}，
     * 否则这里会抛 {@link IllegalArgumentException}。</p>
     */
    private static boolean isReadFunction(ModbusServiceFunction function) {
        String fc = ModbusFrameCodec.toHex(new byte[]{ModbusFrameCodec.parseHex(function.request)[1]});
        return ModbusFrameCodec.isRead(fc);
    }

    private static String validateFields(List<ModbusServiceField> fields, String base, boolean readFunction) {
        if (fields == null) {
            return null;
        }
        Set<Integer> seenIndex = new HashSet<>();
        // 同一应答内所有出值名（各字段名 + 各位名）必须唯一：parser 把它们放进同一个结果对象，重名会互相覆盖
        Set<String> seenField = new HashSet<>();
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
            // 字段自身的告警放在取值表之后：它要拿 value-list 的 description 去核对 state
            String alarmErr = validateAlarms(field.alarms, field, false, ibase + ".alarms", readFunction);
            if (alarmErr != null) {
                return alarmErr;
            }
            String bitListErr = validateBitList(field, ibase, seenField, readFunction);
            if (bitListErr != null) {
                return bitListErr;
            }
        }
        return null;
    }

    /**
     * 校验位清单（{@link ModbusServiceField#bitList}，01/02 位区）：偏移必须落在该字段覆盖的位区间内
     * （{@code 0 <= offset < bytes * 8}）、不重复，名称非空且在整份应答内唯一（出值名会进同一个结果对象）。
     *
     * <p>位是**独立的结果键**（parser 把每个命名位单独放进结果对象），所以告警配在位上而不是父字段上：
     * 父字段那份是整个位掩码，与「进水阀开没开」不是一回事。</p>
     */
    private static String validateBitList(ModbusServiceField field, String ibase, Set<String> seenField,
                                          boolean readFunction) {
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
            String alarmErr = validateAlarms(bit.alarms, field, true, bbase + ".alarms", readFunction);
            if (alarmErr != null) {
                return alarmErr;
            }
        }
        return null;
    }

    /**
     * 一个出值（或一个位）的**一组**阈值告警规则（{@link ModbusServiceFieldAlarm}）：逐条校验，
     * 外加两条只有「一组」才有意义的要求 —— 条数上限，以及启用规则的 {@code id} 非空、不超长、组内唯一。
     *
     * <p>{@code id} 是「这条开着的告警是哪条规则开的」的唯一依据（见 {@link ModbusServiceFieldAlarm#id}），
     * 所以它必须存在且唯一。<b>只在 {@code enabled == true} 时查</b>，与下面 {@link #validateAlarm}
     * 同一条规矩：停用的规则不参与判定，它的 {@code id} 不影响任何事 —— 半配置合法。</p>
     *
     * @param abase 出错文案里的位置，如 {@code functions[0].response[1].alarms}
     */
    private static String validateAlarms(List<ModbusServiceFieldAlarm> alarms,
                                         ModbusServiceField field,
                                         boolean isBit,
                                         String abase,
                                         boolean readFunction) {
        if (alarms == null || alarms.isEmpty()) {
            return null;
        }
        if (alarms.size() > MAX_ALARM_RULES) {
            return "invalid " + abase + ": too many rules (" + MAX_ALARM_RULES + " max)";
        }
        Set<String> seenId = new HashSet<>();
        for (int k = 0; k < alarms.size(); k++) {
            ModbusServiceFieldAlarm alarm = alarms.get(k);
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
            String err = validateAlarm(alarm, field, isBit, rbase, readFunction);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    /**
     * 一条阈值告警规则（{@link ModbusServiceFieldAlarm}）。
     *
     * <p><b>只在 {@code enabled == true} 时校验</b> —— 半配置合法，与 {@link #validatePolling}
     * 同一条规矩：false 的用法就是「把配置留着、先不报」。所以下面的规则读作「启用之后必须自洽」。
     * 判定口径（{@link ModbusAlarmPolicy}）共用 {@code OPERATORS} / {@code LEVELS} 两张白名单，
     * 不各写一份。</p>
     *
     * <p>「能比什么」由字段的取值形态决定，而不是留给运行期碰运气：{@code string} 字段没有可比的量，
     * 一律不收；带 {@code value-list} 的字段出的是描述字符串，只能用 {@code =} 比 {@code state}
     * —— 给它配 {@code >} 会让比较随原始值是否命中取值表而时灵时不灵，正是要避免的静默失效。</p>
     *
     * @param field 配置所在字段：位（{@code isBit}）借它的 format / value-list 判断可比性
     * @param isBit 校验的是 {@code bit-list} 里某个位的配置（值恒为 0/1，只能比 0 或 1）
     * @param abase 出错文案里的位置，如 {@code functions[0].response[1].bit-list[2].alarms[0]}
     */
    private static String validateAlarm(ModbusServiceFieldAlarm alarm,
                                        ModbusServiceField field,
                                        boolean isBit,
                                        String abase,
                                        boolean readFunction) {
        if (!ModbusAlarmPolicy.isEnabled(alarm)) {
            return null;
        }
        if (!readFunction) {
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
        if (field.format != null && "string".equals(field.format.trim().toLowerCase(Locale.ROOT))) {
            return "invalid " + abase + ": a string field has no comparable value";
        }

        boolean named = field.valueList != null && !field.valueList.isEmpty();
        if ("=".equals(alarm.compare)) {
            if (named) {
                if (alarm.threshold != null) {
                    return "invalid " + abase + ".threshold: not allowed on a value-list field (use state)";
                }
                if (alarm.state == null || alarm.state.isBlank()) {
                    return abase + ".state is required (compare = on a value-list field)";
                }
                if (!hasDescription(field, alarm.state)) {
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

    /** {@code state} 是否逐字命中取值表里的某个 description —— 判定时就按这个字符串相等去比。 */
    private static boolean hasDescription(ModbusServiceField field, String state) {
        for (ModbusServiceFieldValue entry : field.valueList) {
            if (entry != null && state.equals(entry.description)) {
                return true;
            }
        }
        return false;
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
