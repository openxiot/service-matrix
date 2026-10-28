package cc.openxiot.matrix.api.modbus.fd.validator;

import cc.openxiot.matrix.api.modbus.alarm.policy.ModbusAlarmPolicy;
import cc.openxiot.matrix.api.modbus.fd.FieldDeviceService;
import cc.openxiot.matrix.api.modbus.fd.params.PollOnce;
import cc.openxiot.matrix.typedef.modbus.frame.ModbusFrameCodec;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistries;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.modbus.fd.poll.response.field.ModbusResponseField;
import cc.openxiot.matrix.db.modbus.fd.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.fd.poll.ModbusPollFrom;
import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequest;
import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequestField;
import cc.openxiot.matrix.db.modbus.fd.DeviceOn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link FieldDevice} 定义的内容校验（规则见 MODBUS.md 第七节）。
 *
 * <p>纯函数、无状态、不碰数据库：返回 {@code null} 表示通过，否则返回给前端看的错误文案。
 * 调用方（{@link FieldDeviceService}）把它转成 {@link IllegalArgumentException}。</p>
 *
 * <p>寄存器中心：request/response 字段只引用 {@code registryIndex}，解码/编码元数据全部取自被引用的
 * {@link ModbusRegistry}（见 {@link ModbusRegistries}）。因此本类不再校验字段上的 format/bytes 等，
 * 改为「引用必须存在 + 方向与寄存器形态自洽」。</p>
 */
public final class FieldDeviceValidator {

    /**
     * 请求功能码词表：01/02/03/04 读 · 05/06/0F/10 写。
     */
    private static final Set<String> READ_FCS = Set.of("01", "02", "03", "04");
    private static final Set<String> WRITE_FCS = Set.of("05", "06", "0F", "10");

    /** 写允许的寄存器数据类型（按功能码）：05/0F 只写线圈(bit)、06 只写 16 位、10 写 16/32 位数值。 */
    private static final Set<String> BIT_WRITE_TYPES = Set.of("bit");
    private static final Set<String> SINGLE_REGISTER_WRITE_TYPES = Set.of("int16", "uint16");
    private static final Set<String> MULTI_REGISTER_WRITE_TYPES = Set.of("int16", "uint16", "int32", "uint32", "float32");

    /** 读允许的寄存器数据类型（数值 + string）。 */
    private static final Set<String> READ_TYPES = Set.of(
            "int8", "uint8", "int16", "uint16", "int32", "uint32", "float32", "string");

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
     * 一个出值一条阈值告警规则（{@link ModbusRegistry#alarms} 参数）的条数上限。
     *
     * <p>前端 device.service.editor.ts 里有一份同口径的常量（「添加规则」按钮的禁用点），改动要同步。</p>
     */
    private static final int MAX_ALARM_RULES = 8;

    /**
     * 告警文本的长度上限（去首尾空白后算）：它是一条告警在人眼前的名字，不是备注栏。
     *
     * <p>前端 device.service.editor.html 里有一份同口径的常量（输入框的 maxlength），改动要同步。</p>
     */
    private static final int MAX_ALARM_TEXT_LENGTH = 64;

    /**
     * 规则标识的长度上限（见 {@link ModbusRegistryAlarm.id}）：前端生成的一小串随机码，
     * 不是给人看的，给足余量即可。
     */
    private static final int MAX_ALARM_ID_LENGTH = 64;

    private FieldDeviceValidator() {
    }

    /**
     * 整份定义：name / version / configSnapshot / device / functions 逐层校验。
     */
    public static String validate(FieldDevice fd) {
        if (fd == null) {
            return "body is required";
        }
        if (fd.name == null || fd.name.isBlank()) {
            return "name is required";
        }
        if (fd.version == null) {
            return "version is required";
        }
        if (fd.version != FieldDevice.VERSION) {
            // 硬拒旧口径：version=2 的字段里是内联 name/format/bytes，读成注册表引用只会得到
            // 一串 null，然后报一句和病因无关的「registryIndex is required」
            return "invalid version: " + fd.version + " (this server serves version "
                    + FieldDevice.VERSION + ")";
        }

        // 点表快照是服务的**定义来源**（寄存器缓存读侧投影直接读它，不再回查点表），
        // 缺了它服务就只剩下方法列表 —— 新口径一律要求带上；存量文档由迁移脚本补齐。
        if (fd.configSnapshot == null) {
            return "configSnapshot is required";
        }
        // id 是溯源与统计分组键：快照可以没有 owner/lifecycle（会话裁剪），但必须知道自己取自哪条点表
        if (fd.configSnapshot.id == null) {
            return "configSnapshot.id is required";
        }
        // 字段只引用寄存器，寄存器查询表就是唯一事实源——先建出来供下方所有校验段复用
        Map<Integer, ModbusRegistry> registries =
                ModbusRegistries.byIndex(fd.configSnapshot.registries);

        String deviceErr = checkDevice(fd.device);
        if (deviceErr != null) {
            return deviceErr;
        }
        if (fd.slaveId == null) {
            return "slaveId is required";
        }
        if (fd.slaveId < 1 || fd.slaveId > 247) {
            return "invalid slaveId: " + fd.slaveId + " (must be 1..247)";
        }
        // 告警规则属寄存器（寄存器中心）：逐寄存器校验 configSnapshot.registries[].alarms
        String registryAlarmErr = validateRegistryAlarms(fd.configSnapshot.registries, "configSnapshot");
        if (registryAlarmErr != null) {
            return registryAlarmErr;
        }
        return validateFunctions(fd.polls, fd.slaveId, registries);
    }

    /**
     * 依赖设备的调用坐标：invoke 前也要查一遍（文档可能来自更早的口径）。
     */
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

    private static String validateFunctions(List<ModbusPoll> polls, Integer serviceSlaveId,
                                            Map<Integer, ModbusRegistry> registries) {
        if (polls == null) {
            return "polls is required";
        }
        Set<Integer> seenIndex = new HashSet<>();
        for (int i = 0; i < polls.size(); i++) {
            ModbusPoll poll = polls.get(i);
            String base = "polls[" + i + "]";
            if (poll == null) {
                return base + " is null";
            }
            // 方法身份：index 必填、>=1、同服务唯一 —— 轮询身份（modbus.polls.pollIndex）、
            // 告警/影子的方法键都读它
            if (poll.index == null) {
                return base + ".index is required";
            }
            if (poll.index < 1) {
                return "invalid " + base + ".index: " + poll.index
                        + " (index must be a natural number, 1-based)";
            }
            if (!seenIndex.add(poll.index)) {
                return "invalid " + base + ".index: " + poll.index + " (duplicated)";
            }
            // 来源：from 必填、type.type ∈ {registry, command}；from.index 仅作**溯源**
            // （指向 config 的 ModbusRegistry#index / ModbusCommand#index）—— 可选、给了必须 >=1，
            // 但**不**强制唯一（它不是方法身份，允许同服务的 command/registry 溯源到同一 config 序号）
            ModbusPollFrom type = poll.from;
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
            if (poll.name == null || poll.name.isBlank()) {
                return base + ".name is required";
            }
            String requestErr = validateRequest(poll.request, base, registries);
            if (requestErr != null) {
                return requestErr;
            }
            // 每个方法的 request.slaveId 必须等于服务级从站地址（service.slaveId）：
            // 服务级 slaveId 是本服务「读哪台从站」的顶层事实，方法里走私站的地址若不一致，
            // 会静默读到隔壁设备——这里硬校验。
            Integer slaveId = poll.request.slaveId;
            if (!serviceSlaveId.equals(slaveId)) {
                return "invalid " + base + ".request.slaveId: " + slaveId
                        + " (must equal service slaveId " + serviceSlaveId + ")";
            }
            String pollingErr = validatePolling(poll, base);
            if (pollingErr != null) {
                return pollingErr;
            }
            String intervalErr = validateInterval(poll, base);
            if (intervalErr != null) {
                return intervalErr;
            }
            boolean read = isReadPoll(poll);
            // registry 方法由点表寄存器折出，读写都允许：可读寄存器折读块、可写寄存器折 fc 05/06 写方法
            // （写任意值在 invoke 时进，见前端 writePollingOf）。方向上的限制不在这里给 ——
            // 「写方法不许轮询」「写方法不许有应答」分别由 validatePolling / validateInterval、
            // validateRequestResponse 拦下，各自给更贴身的文案。
            // registry 的应答解析规则不能没有（仅读方法）：读方法缺 response 是静默失效（返回 {}），
            // 而 registry 的 request/response 都是前端从点表展开的，空应答只能是端点表时漏了；
            // 写方法的应答是请求回显、天然没有 response，不走这条。
            if (read && registry && ModbusPoll.responseFields(poll).isEmpty()) {
                return base + ".response is required for a registry poll (the register-derived read block must declare fields)";
            }
            String responseErr = validateFields(ModbusPoll.responseFields(poll), registries, base);
            if (responseErr != null) {
                return responseErr;
            }
            // 对账放在字段校验之后：它要拿已经验过一遍的寄存器去求和
            String reconcileErr = validateRequestResponse(poll, base, read, registries);
            if (reconcileErr != null) {
                return reconcileErr;
            }
        }
        return null;
    }

    /**
     * 请求定义（{@link ModbusRequest}）：公共字段 → 按方向分叉。
     *
     * <p>读写方向由 {@code fc} 推出（与点表同一口径），且**决定另外两个字段谁有效**：
     * 读只有 {@code quantity}，写只有 {@code fields}，两者都给或都不给都是定义错误。</p>
     */
    private static String validateRequest(ModbusRequest request, String base,
                                          Map<Integer, ModbusRegistry> registries) {
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
                : validateWriteRequest(request, rbase, fc, registries);
    }

    /**
     * 读请求：数量必填并受 Modbus 上限约束；{@code fields} 是写请求的东西，出现在读请求里必须报错。
     */
    private static String validateReadRequest(ModbusRequest request, String rbase, String fc) {
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
     * 写请求：{@code fields} 必填、{@code quantity} 必须不填（帧里的数量由寄存器推导），
     * 逐字段校验「引用的寄存器存在 + 数据类型与该功能码匹配 + 取值范围」，再核对地址排列首尾相接。
     */
    private static String validateWriteRequest(ModbusRequest request, String rbase, String fc,
                                               Map<Integer, ModbusRegistry> registries) {
        if (request.quantity != null) {
            return "invalid " + rbase + ".quantity: " + request.quantity
                    + " (not allowed on a write function (fc " + fc + "), the frame's count comes from registers)";
        }
        List<ModbusRequestField> fields = request.fields;
        if (fields == null || fields.isEmpty()) {
            return rbase + ".fields is required (a write function needs the registers it writes)";
        }

        boolean bits = "05".equals(fc) || "0F".equals(fc);
        boolean single = "05".equals(fc) || "06".equals(fc);
        if (single && fields.size() != 1) {
            return "invalid " + rbase + ".fields: fc " + fc + " writes exactly one field, got " + fields.size();
        }
        Set<String> allowedTypes = bits
                ? BIT_WRITE_TYPES
                : ("06".equals(fc) ? SINGLE_REGISTER_WRITE_TYPES : MULTI_REGISTER_WRITE_TYPES);

        Set<Integer> seenRegistry = new HashSet<>();
        List<ModbusRequestField> ordered = new ArrayList<>(fields);
        for (int i = 0; i < fields.size(); i++) {
            ModbusRequestField field = fields.get(i);
            String fbase = rbase + ".fields[" + i + "]";
            String regErr = checkRequestRegistry(field, fbase, seenRegistry, registries);
            if (regErr != null) {
                return regErr;
            }
            ModbusRegistry reg = registries.get(field.registryIndex);
            String format = ModbusRegistries.format(reg);
            if (!allowedTypes.contains(format)) {
                // 白名单逐字列出用 TreeSet 保证顺序稳定（Set.of 迭代顺序不保证，文案会随机）
                return "invalid " + fbase + ".registryIndex: " + field.registryIndex
                        + " (fc " + fc + " writes "
                        + String.join(" ", new java.util.TreeSet<>(allowedTypes))
                        + ", registry has " + format + ")";
            }
            String valueErr = validateWriteValue(field.value, format, bits, fbase + ".value");
            if (valueErr != null) {
                return valueErr;
            }
        }

        // 位区（0F）与寄存器区（10）的帧只有「起始地址 + 个数 + 紧凑数据区」，没有空洞——
        // 位置由寄存器地址相对 start 推出，必须是 0 起连续。
        return validateWriteOffsets(ordered, request.start, registries, rbase, bits);
    }

    /** 一个写字段引用的寄存器必须存在且不重复。 */
    private static String checkRequestRegistry(ModbusRequestField field, String fbase,
                                               Set<Integer> seenRegistry,
                                               Map<Integer, ModbusRegistry> registries) {
        if (field == null) {
            return fbase + " is null";
        }
        if (field.registryIndex == null) {
            return fbase + ".registryIndex is required";
        }
        if (field.registryIndex < 1) {
            return "invalid " + fbase + ".registryIndex: " + field.registryIndex
                    + " (index must be a natural number, 1-based)";
        }
        if (!seenRegistry.add(field.registryIndex)) {
            return "invalid " + fbase + ".registryIndex: " + field.registryIndex + " (duplicated)";
        }
        if (!registries.containsKey(field.registryIndex)) {
            return "invalid " + fbase + ".registryIndex: " + field.registryIndex
                    + " (not in configSnapshot.registries)";
        }
        return null;
    }

    /**
     * 写字段的地址必须按升序**首尾相接**（0F 是位、10 是寄存器）：帧从 {@code start} 起紧凑排列
     * 被写寄存器，位置 = 寄存器地址 − start，必须 0 起连续。数据宽度由被引用寄存器推出。
     */
    private static String validateWriteOffsets(List<ModbusRequestField> fields, Integer start,
                                               Map<Integer, ModbusRegistry> registries,
                                               String rbase, boolean bits) {
        if (start == null) {
            return null; // start 已在 validateRequest 校验过
        }
        fields.sort(Comparator.comparingInt(field -> {
            ModbusRegistry reg = registries.get(field.registryIndex);
            return reg.address == null ? 0 : reg.address;
        }));
        int position = 0;
        for (ModbusRequestField field : fields) {
            ModbusRegistry reg = registries.get(field.registryIndex);
            int offset = reg.address - start;
            if (offset != position) {
                return "invalid " + rbase + ".fields: " + (bits ? "bit" : "register") + " offset of registry "
                        + field.registryIndex + " is " + offset + " (must be " + position
                        + ", registers are ordered by address without gaps)";
            }
            position += bits ? 1 : ModbusRegistries.registerCount(reg);
        }
        return null;
    }

    /**
     * 请求 ↔ 应答对账：
     *
     * <ul>
     *   <li>读：{@code Σ 各寄存器字节宽} 必须等于这一读实际取回的字节数
     *       （01/02 = {@code ceil(quantity / 8)}、03/04 = {@code quantity * 2}）。</li>
     *   <li>读方法必须有 {@code response}：没有解析规则的读方法返回 {@code {}}，是静默失效。</li>
     *   <li>写方法必须没有 {@code response}：写应答是请求回显，解析规则无从谈起。</li>
     * </ul>
     */
    private static String validateRequestResponse(ModbusPoll poll, String base, boolean read,
                                                  Map<Integer, ModbusRegistry> registries) {
        List<ModbusResponseField> fields = ModbusPoll.responseFields(poll);
        if (!read) {
            if (!fields.isEmpty()) {
                return "invalid " + base + ".response: not allowed on a write function (the reply echoes the request)";
            }
            return null;
        }
        if (fields.isEmpty()) {
            return base + ".response is required for a read function (a method with no field returns {})";
        }

        ModbusRequest request = poll.request;
        String fc = normalizeFc(request.fc);
        boolean bit = "01".equals(fc) || "02".equals(fc);
        int declared;
        int expected;
        if (bit) {
            // 位区按位打包：每个引用寄存器是一位，字节数由请求量推出（与应用区对账同一口径）
            expected = (request.quantity + 7) / 8;
            declared = (fields.size() + 7) / 8;
        } else {
            expected = request.quantity * 2;
            declared = 0;
            for (ModbusResponseField field : fields) {
                ModbusRegistry reg = registries.get(field.registryIndex);
                if (reg != null) {
                    declared += ModbusRegistries.byteWidth(reg);
                }
            }
        }
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

    /**
     * 功能码归一到大写：{@code "0f"} 与 {@code "0F"} 是同一个东西（词表都是大写）。
     */
    private static String normalizeFc(String fc) {
        return fc == null ? "" : fc.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 是不是读方法（fc 01/02/03/04）。周期与告警共用这一处判断 ——
     * 两条规则都只允许配在读方法上（读方法才有「读到的值」）。
     */
    private static boolean isReadPoll(ModbusPoll poll) {
        return poll.request != null && ModbusFrameCodec.isRead(poll.request.fc);
    }

    /**
     * {@code state} 是否逐字命中枚举映射里的某个显示名 —— 判定时就按这个字符串相等去比。
     */
    private static boolean hasDescription(ModbusRegistry reg, String state) {
        if (reg == null || reg.enumMap == null) {
            return false;
        }
        return reg.enumMap.containsValue(state);
    }

    /**
     * 自动轮询开关（{@link ModbusPoll#polling}）：缺省 / false 都不必校验 ——
     * 没开轮询时 {@code interval} 只是一份没生效的配置（false 的用法就是「留着周期、先不跑」）。
     *
     * <p>{@code true} 有两条要求，都在这里说清楚：<b>只允许读方法</b>（写方法开轮询 = 让定时器
     * 周期性往寄存器里写值，是危险动作），以及开了轮询必须有周期。</p>
     */
    private static String validatePolling(ModbusPoll poll, String base) {
        if (!Boolean.TRUE.equals(poll.polling)) {
            return null;
        }
        if (!isReadPoll(poll)) {
            return "invalid " + base + ".polling: " + poll.polling
                    + " (only read functions (fc 01/02/03/04) can be polled)";
        }
        if (poll.interval == null) {
            return "invalid " + base + ".polling: " + poll.polling + " (polling requires an interval)";
        }
        return null;
    }

    /**
     * 自动调用周期（{@link ModbusPoll#interval}，秒）：缺省 = 没配周期。
     * 配了周期不等于会跑 —— 跑不跑看 {@link ModbusPoll#polling}。
     *
     * <p><b>只允许读方法</b>（fc 01/02/03/04）：写方法的应答是请求回显，周期调用等于让服务端
     * 周期性地往寄存器里写值，是危险动作，不能由定时器发起。</p>
     */
    private static String validateInterval(ModbusPoll poll, String base) {
        Integer interval = poll.interval;
        if (interval == null) {
            return null;
        }
        if (!isReadPoll(poll)) {
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
     * invoke 入参（{@link PollOnce#values}）与写方法的字段定义对账。
     *
     * <p><b>读方法给了 values 要报错</b>：静默忽略一个用户以为生效了的载荷，比报错糟得多。</p>
     *
     * <p>写方法逐字段查：没有缺省值的字段必须给值、给了未声明的寄存器序号报错、类型不符 / 越界报错
     * （取值判定与定义期的缺省值共用 {@link #validateWriteValue}）。values 的 key 是被写寄存器的
     * {@code registryIndex}。</p>
     *
     * <p>这是**调用方的请求错误**，与「function not found」同类，由调用链在 {@code execute} 之前
     * 拦下，因此不进 {@code service-failures}（那是设备故障记录）。</p>
     */
    public static String validateInvoke(ModbusPoll poll, Map<String, Object> values,
                                        Map<Integer, ModbusRegistry> registries) {
        if (poll == null || poll.request == null) {
            return "request is not set";
        }
        ModbusRequest request = poll.request;
        if (isReadPoll(poll)) {
            if (values != null && !values.isEmpty()) {
                return "invalid values: a read function (fc " + request.fc + ") takes no values";
            }
            return null;
        }

        List<ModbusRequestField> fields = request.fields;
        if (fields == null || fields.isEmpty()) {
            return "request.fields is not set for a write function";
        }
        String fc = normalizeFc(request.fc);
        boolean bits = "05".equals(fc) || "0F".equals(fc);

        Set<String> declared = new HashSet<>();
        for (ModbusRequestField field : fields) {
            String key = String.valueOf(field.registryIndex);
            declared.add(key);
            Object value = values == null ? null : values.get(key);
            if (value == null) {
                if (field.value == null) {
                    return "values." + key + " is required (this register has no default)";
                }
                continue;
            }
            ModbusRegistry reg = registries.get(field.registryIndex);
            String format = reg == null ? null : ModbusRegistries.format(reg);
            String err = validateWriteValue(value, format, bits, "values." + key);
            if (err != null) {
                return err;
            }
        }
        if (values != null) {
            for (String name : values.keySet()) {
                if (!declared.contains(name)) {
                    return "invalid values." + name + ": not a register of this function";
                }
            }
        }
        return null;
    }

    /**
     * 应答字段（{@link ModbusResponseField}）：逐个校验引用的寄存器存在、且该寄存器能被读
     * （在 READ_TYPES 内）、不重复。
     */
    private static String validateFields(List<ModbusResponseField> fields,
                                         Map<Integer, ModbusRegistry> registries,
                                         String base) {
        if (fields == null) {
            return null;
        }
        Set<Integer> seenRegistry = new HashSet<>();
        for (int j = 0; j < fields.size(); j++) {
            ModbusResponseField field = fields.get(j);
            String ibase = base + ".response.fields[" + j + "]";
            if (field == null) {
                return ibase + " is null";
            }
            if (field.registryIndex == null) {
                return ibase + ".registryIndex is required";
            }
            if (field.registryIndex < 1) {
                return "invalid " + ibase + ".registryIndex: " + field.registryIndex
                        + " (index must be a natural number, 1-based)";
            }
            if (!seenRegistry.add(field.registryIndex)) {
                return "invalid " + ibase + ".registryIndex: " + field.registryIndex + " (duplicated)";
            }
            ModbusRegistry reg = registries.get(field.registryIndex);
            if (reg == null) {
                return "invalid " + ibase + ".registryIndex: " + field.registryIndex
                        + " (not in configSnapshot.registries)";
            }
            if (ModbusRegistries.isBit(reg)) {
                continue; // 位点位数据形状由其区决定，无需查 dataType
            }
            String type = ModbusRegistries.dataType(reg);
            if (!READ_TYPES.contains(type)) {
                return "invalid " + ibase + ".registryIndex: " + field.registryIndex
                        + " (data type " + type + " is not readable)";
            }
        }
        return null;
    }

    // =========================================================================
    // 寄存器阈值告警（不变：告警属寄存器，见 ModbusAlarmPolicy）
    // =========================================================================

    /**
     * 寄存器的**一组**阈值告警规则（{@link ModbusRegistry#alarms}）：逐条校验，
     * 外加只有「一组」才有意义的要求 —— 条数上限、启用规则 {@code id} 非空且唯一。
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
     * <p><b>只在 {@code enabled == true} 时校验</b> —— 半配置合法。判定口径
     * （{@link ModbusAlarmPolicy}）共用 {@code OPERATORS} / {@code LEVELS} 两张白名单。</p>
     */
    private static String validateAlarm(
            ModbusRegistryAlarm alarm,
            ModbusRegistry reg,
            boolean isBit,
            boolean readable,
            String abase
    ) {
        if (!ModbusAlarmPolicy.isEnabled(alarm)) {
            return null;
        }
        if (!readable) {
            return "invalid " + abase + ": only read functions (fc 01/02/03/04) can have alarms";
        }
        if (alarm.compare == null || !ModbusAlarmPolicy.OPERATORS.contains(alarm.compare)) {
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
        return null;
    }
}