package cc.openxiot.matrix.api.modbus.service.validator;

import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldValue;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import cc.openxiot.matrix.db.modbus.service.DeviceOn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ModbusServiceValidator} 的规则校验（MODBUS.md 第七节）。
 *
 * <p>纯函数，不需要数据库：这是这套校验唯一能跑起来的地方（{@code @QuarkusTest} 要连 Mongo）。</p>
 */
class ModbusServiceValidatorTest {

    // ===== 合法基线 =====

    @Test
    void acceptsACompleteDefinition() {
        assertNull(ModbusServiceValidator.validate(service()));
    }

    // ===== 顶层 =====

    @Test
    void rejectsMissingScalars() {
        ModbusService noName = service();
        noName.name = null;
        assertEquals("name is required", ModbusServiceValidator.validate(noName));

        ModbusService noVersion = service();
        noVersion.version = null;
        assertEquals("version is required", ModbusServiceValidator.validate(noVersion));
    }

    @Test
    void rejectsNullBody() {
        assertEquals("body is required", ModbusServiceValidator.validate(null));
    }

    @Test
    void rejectsBadDevice() {
        ModbusService noDevice = service();
        noDevice.device = null;
        assertEquals("device is required", ModbusServiceValidator.validate(noDevice));

        ModbusService noDid = service();
        noDid.device.did = " ";
        assertEquals("device.did is required", ModbusServiceValidator.validate(noDid));

        ModbusService zeroSiid = service();
        zeroSiid.device.siid = 0;
        assertEquals("device.siid is required", ModbusServiceValidator.validate(zeroSiid));

        ModbusService zeroArgument = service();
        zeroArgument.device.argument = 0;
        assertEquals("device.argument is required", ModbusServiceValidator.validate(zeroArgument));
    }

    // ===== functions =====

    @Test
    void rejectsMissingOrDuplicatedFunctionIndex() {
        ModbusService noFunctions = service();
        noFunctions.functions = null;
        assertEquals("functions is required", ModbusServiceValidator.validate(noFunctions));

        ModbusService noIndex = service();
        noIndex.functions.get(0).index = null;
        assertEquals("functions[0].index is required", ModbusServiceValidator.validate(noIndex));

        ModbusService duplicated = service();
        duplicated.functions.get(1).index = 1;
        assertEquals("invalid functions[1].index: 1 (duplicated)", ModbusServiceValidator.validate(duplicated));
    }

    @Test
    void rejectsBadRequestFrame() {
        ModbusService notHex = service();
        notHex.functions.get(0).request = "0103ZZ";
        assertEquals("invalid functions[0].request: 0103ZZ", ModbusServiceValidator.validate(notHex));

        ModbusService tooShort = service();
        tooShort.functions.get(0).request = "0103";
        assertEquals("invalid functions[0].request: too short", ModbusServiceValidator.validate(tooShort));

        ModbusService blank = service();
        blank.functions.get(0).request = " ";
        assertEquals("functions[0].request is required", ModbusServiceValidator.validate(blank));
    }

    // ===== interval =====

    @Test
    void acceptsIntervalOnReadFunctions() {
        ModbusService polled = service();
        polled.functions.get(0).interval = 5;
        assertNull(ModbusServiceValidator.validate(polled));
    }

    @Test
    void acceptsIntervalBounds() {
        // 上下限本身是合法的（越界才拒）
        ModbusService fastest = service();
        fastest.functions.get(0).interval = 5;
        assertNull(ModbusServiceValidator.validate(fastest));

        ModbusService slowest = service();
        slowest.functions.get(0).interval = 3600;
        assertNull(ModbusServiceValidator.validate(slowest));
    }

    @Test
    void rejectsIntervalOnWriteFunctions() {
        // 写方法的应答只是请求回显，周期调用等于让服务端周期性往寄存器里写值
        ModbusService written = service();
        written.functions.get(0).request = "010600010064D9E1";   // fc 06 写单寄存器
        written.functions.get(0).interval = 5;
        assertEquals("invalid functions[0].interval: 5 (only read functions (fc 01/02/03/04) can be polled)",
                ModbusServiceValidator.validate(written));
    }

    @Test
    void rejectsIntervalOutOfRange() {
        // 4 秒是贴近新下限的那个负例：改下限时最容易漏的就是「边界内侧一格」
        ModbusService belowMin = service();
        belowMin.functions.get(0).interval = 4;
        assertEquals("invalid functions[0].interval: 4 (interval must be between 5 and 3600 seconds)",
                ModbusServiceValidator.validate(belowMin));

        ModbusService tooFrequent = service();
        tooFrequent.functions.get(0).interval = 2;
        assertEquals("invalid functions[0].interval: 2 (interval must be between 5 and 3600 seconds)",
                ModbusServiceValidator.validate(tooFrequent));

        ModbusService tooRare = service();
        tooRare.functions.get(0).interval = 3601;
        assertEquals("invalid functions[0].interval: 3601 (interval must be between 5 and 3600 seconds)",
                ModbusServiceValidator.validate(tooRare));
    }

    // ===== polling =====

    @Test
    void acceptsPollingOnReadFunctions() {
        // 显式开轮询：有周期，读方法
        ModbusService polling = service();
        polling.functions.get(0).interval = 5;
        polling.functions.get(0).polling = true;
        assertNull(ModbusServiceValidator.validate(polling));

        // 显式关轮询：周期留着不跑（暂停），一样合法
        ModbusService paused = service();
        paused.functions.get(0).interval = 5;
        paused.functions.get(0).polling = false;
        assertNull(ModbusServiceValidator.validate(paused));

        // 缺省 = 按 interval 判定：老数据（只有周期、没这个字段）照旧算「启用」
        ModbusService legacy = service();
        legacy.functions.get(0).interval = 5;
        assertNull(ModbusServiceValidator.validate(legacy));
    }

    @Test
    void rejectsPollingWithoutInterval() {
        // 开了轮询却没有周期：服务端无从知道多久调一次，自相矛盾
        ModbusService noInterval = service();
        noInterval.functions.get(0).polling = true;
        assertEquals("invalid functions[0].polling: true (polling requires an interval)",
                ModbusServiceValidator.validate(noInterval));
    }

    @Test
    void rejectsPollingOnWriteFunctions() {
        // 写方法开轮询：先被「必须有周期」拦下（周期本身在写方法上也过不了 validateInterval）
        ModbusService written = service();
        written.functions.get(0).request = "010600010064D9E1";   // fc 06 写单寄存器
        written.functions.get(0).polling = true;
        assertEquals("invalid functions[0].polling: true (polling requires an interval)",
                ModbusServiceValidator.validate(written));

        // 写方法上带周期又开轮询：由周期那条规则拒（写方法不能周期调用）
        ModbusService writtenPolled = service();
        writtenPolled.functions.get(0).request = "010600010064D9E1";
        writtenPolled.functions.get(0).interval = 5;
        writtenPolled.functions.get(0).polling = true;
        assertEquals("invalid functions[0].interval: 5 (only read functions (fc 01/02/03/04) can be polled)",
                ModbusServiceValidator.validate(writtenPolled));
    }

    // ===== response =====

    @Test
    void rejectsFormatWidthMismatch() {
        ModbusService mismatched = service();
        ModbusServiceField field = mismatched.functions.get(0).response.get(0);
        field.format = "int8";     // int8 只占 1 字节
        assertEquals("invalid functions[0].response[0].bytes: 2 (format int8 needs 1)",
                ModbusServiceValidator.validate(mismatched));
    }

    @Test
    void rejectsUnknownFormatAndByteOrder() {
        ModbusService badFormat = service();
        badFormat.functions.get(0).response.get(0).format = "uint64";
        assertEquals("invalid functions[0].response[0].format: uint64",
                ModbusServiceValidator.validate(badFormat));

        ModbusService badOrder = service();
        badOrder.functions.get(0).response.get(0).byteOrder = "ABCDEFGH";
        assertEquals("invalid functions[0].response[0].byteOrder: ABCDEFGH",
                ModbusServiceValidator.validate(badOrder));

        ModbusService noOrder = service();
        noOrder.functions.get(0).response.get(0).byteOrder = null;
        assertEquals("functions[0].response[0].byteOrder is required",
                ModbusServiceValidator.validate(noOrder));
    }

    @Test
    void stringFormatTakesAnyWidth() {
        ModbusService text = service();
        ModbusServiceField field = text.functions.get(0).response.get(0);
        field.format = "string";
        field.bytes = 8;           // string 宽度不固定，由 bytes 决定，不做宽度比对
        assertNull(ModbusServiceValidator.validate(text));
    }

    @Test
    void singleByteNeedsNoByteOrder() {
        ModbusService oneByte = service();
        ModbusServiceField flag = oneByte.functions.get(0).response.get(0);
        flag.format = "uint8";
        flag.bytes = 1;
        flag.byteOrder = null;     // 单字节没有字节序可言
        assertNull(ModbusServiceValidator.validate(oneByte));
    }

    @Test
    void byteOrderIsRequiredForEveryMultiByteField() {
        // 现行规则是「bytes > 1 就必填」，string 也不例外（虽然 string 的取值并不用 byteOrder）
        ModbusService text = service();
        ModbusServiceField field = text.functions.get(0).response.get(0);
        field.format = "string";
        field.bytes = 8;
        field.byteOrder = null;
        assertEquals("functions[0].response[0].byteOrder is required",
                ModbusServiceValidator.validate(text));
    }

    @Test
    void rejectsEmptyOrDuplicatedValueList() {
        ModbusService noDescription = service();
        noDescription.functions.get(0).response.get(0).valueList = List.of(value(0L, " "));
        assertEquals("functions[0].response[0].value-list[0].description is required",
                ModbusServiceValidator.validate(noDescription));

        ModbusService duplicated = service();
        duplicated.functions.get(0).response.get(0).valueList = List.of(value(0L, "ON"), value(0L, "OFF"));
        assertEquals("invalid functions[0].response[0].value-list[1].value: 0 (duplicated)",
                ModbusServiceValidator.validate(duplicated));
    }

    /** 同一应答内所有出值名必须唯一：parser 把它们放进同一个结果对象，重名会互相覆盖。 */
    @Test
    void rejectsDuplicatedFieldNames() {
        ModbusService duplicated = service();
        ModbusServiceField second = function(1).response.get(0); // 序号不同、名字相同的第二个字段
        second.index = 2;
        duplicated.functions.get(0).response.add(second);
        assertEquals("invalid functions[0].response[1].field: 进水温度 (duplicated)",
                ModbusServiceValidator.validate(duplicated));
    }

    @Test
    void rejectsBitListOffsetBeyondField() {
        ModbusService beyond = service();
        ModbusServiceField field = beyond.functions.get(0).response.get(0);
        field.bitList = new ArrayList<>(List.of(bit(16, "第17位"))); // bytes 2 → 只有 0..15
        assertEquals("invalid functions[0].response[0].bit-list[0].offset: 16 (field covers 16 bits)",
                ModbusServiceValidator.validate(beyond));
    }

    @Test
    void rejectsDuplicatedBitAndFieldNames() {
        ModbusService duplicatedOffset = service();
        ModbusServiceField field = duplicatedOffset.functions.get(0).response.get(0);
        field.bitList = new ArrayList<>(List.of(bit(0, "第1位"), bit(0, "第2位")));
        assertEquals("invalid functions[0].response[0].bit-list[1].offset: 0 (duplicated)",
                ModbusServiceValidator.validate(duplicatedOffset));

        // 位名与所在字段重名同样会互相覆盖
        ModbusService shadowed = service();
        ModbusServiceField owner = shadowed.functions.get(0).response.get(0);
        owner.bitList = new ArrayList<>(List.of(bit(0, "进水温度")));
        assertEquals("invalid functions[0].response[0].bit-list[0].field: 进水温度 (duplicated)",
                ModbusServiceValidator.validate(shadowed));
    }

    @Test
    void acceptsBitListWithinField() {
        ModbusService bits = service();
        ModbusServiceField field = bits.functions.get(0).response.get(0);
        field.bitList = new ArrayList<>(List.of(bit(0, "进水阀"), bit(15, "报警")));

        assertNull(ModbusServiceValidator.validate(bits));
    }

    // ===== 告警配置 =====

    @Test
    void acceptsAlarms() {
        // 数值字段：超过 80 报「温度过高」
        assertNull(validateFieldAlarm(alarm(">", 80.0, null), field("进水温度", "uint16", 2)));
        // 取值表字段：等于「ON」报「水泵异常」（比的是描述字符串，不是原始值）
        ModbusServiceField named = field("水泵状态", "uint16", 2);
        named.valueList = new ArrayList<>(List.of(value(0L, "OFF"), value(1L, "ON")));
        assertNull(validateFieldAlarm(alarm("=", null, "ON"), named));
        // 数值字段用「等于」比数
        assertNull(validateFieldAlarm(alarm("=", 1.0, null), field("进水温度", "uint16", 2)));
    }

    @Test
    void acceptsARuleGroupOnOneField() {
        // 一个出值一组规则：阈值各异、甚至方向相反（低侧与高侧永远不可能同时命中）都合法
        ModbusServiceFieldAlarm low = alarm("r1", "<", 20.0, null);
        low.level = "WARN";
        ModbusServiceFieldAlarm high = alarm("r2", ">", 26.0, null);
        high.level = "INFO";
        ModbusServiceFieldAlarm worst = alarm("r3", ">", 30.0, null);
        worst.level = "CRITICAL";
        assertNull(validateFieldAlarms(List.of(low, high, worst), field("进水温度", "uint16", 2)));

        // 停用的规则不查 id（半配置合法），也不占用组内的 id 名额
        ModbusServiceFieldAlarm disabled = alarm(null, ">", 40.0, null);
        disabled.enabled = false;
        assertNull(validateFieldAlarms(List.of(low, disabled), field("进水温度", "uint16", 2)));
    }

    @Test
    void rejectsBadAlarmIds() {
        String base = "functions[0].response[0].alarms";
        ModbusServiceField temperature = field("进水温度", "uint16", 2);

        // id 是「这条开着的告警是哪条规则开的」的唯一依据：启用了就必须有
        assertEquals(base + "[0].id is required",
                validateFieldAlarms(List.of(alarm(null, ">", 80.0, null)), temperature));
        assertEquals(base + "[0].id is required",
                validateFieldAlarms(List.of(alarm("  ", ">", 80.0, null)), temperature));

        ModbusServiceFieldAlarm longId = alarm("x".repeat(65), ">", 80.0, null);
        assertEquals("invalid " + base + "[0].id: too long (64 characters max)",
                validateFieldAlarms(List.of(longId), temperature));

        // 同一出值内重复：两条规则会争同一个 ruleId，开行的归属就说不清了
        assertEquals("invalid " + base + "[1].id: r1 (duplicated)",
                validateFieldAlarms(List.of(alarm("r1", ">", 80.0, null), alarm("r1", ">", 90.0, null)),
                        temperature));
    }

    @Test
    void rejectsTooManyRulesOnOneField() {
        // 防呆不是安全边界（多几条只是多几次纯内存比较），是给「界面上误加了一堆规则」一个上限
        String base = "functions[0].response[0].alarms";
        ModbusServiceField temperature = field("进水温度", "uint16", 2);

        List<ModbusServiceFieldAlarm> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(alarm("r" + i, ">", 80.0 + i, null));
        }
        assertNull(validateFieldAlarms(eight, temperature));

        List<ModbusServiceFieldAlarm> nine = new ArrayList<>(eight);
        nine.add(alarm("r8", ">", 90.0, null));
        assertEquals("invalid " + base + ": too many rules (8 max)",
                validateFieldAlarms(nine, temperature));
    }

    @Test
    void acceptsBitAlarm() {
        // 位只有 0/1，只能用「等于」比 0 或 1：进水阀 = 1 告警
        ModbusServiceField bits = field("进水阀位", "uint8", 1);
        bits.bitList = new ArrayList<>(List.of(bit(0, "进水阀")));
        bits.bitList.get(0).alarms = new ArrayList<>(List.of(alarm("=", 1.0, null)));

        ModbusService service = service();
        service.functions.get(0).response = new ArrayList<>(List.of(bits));
        assertNull(ModbusServiceValidator.validate(service));
    }

    @Test
    void ignoresDisabledAlarm() {
        // enabled != true：半配置合法，与 polling 同一条规矩 —— 留着配置、先不报
        ModbusServiceFieldAlarm disabled = alarm("??", null, null);
        disabled.enabled = false;
        disabled.text = null;
        disabled.level = null;
        assertNull(validateFieldAlarm(disabled, field("进水温度", "uint16", 2)));

        ModbusServiceFieldAlarm absent = alarm("??", null, null);
        absent.enabled = null;
        assertNull(validateFieldAlarm(absent, field("进水温度", "uint16", 2)));
    }

    @Test
    void rejectsBadAlarmScalars() {
        String base = "functions[0].response[0].alarms[0]";

        ModbusServiceFieldAlarm badCompare = alarm("!=", 80.0, null);
        assertEquals("invalid " + base + ".compare: != (must be one of > >= < <= =)",
                validateFieldAlarm(badCompare, field("进水温度", "uint16", 2)));

        ModbusServiceFieldAlarm noCompare = alarm(null, 80.0, null);
        assertEquals("invalid " + base + ".compare: null (must be one of > >= < <= =)",
                validateFieldAlarm(noCompare, field("进水温度", "uint16", 2)));

        ModbusServiceFieldAlarm badLevel = alarm(">", 80.0, null);
        badLevel.level = "FATAL";
        assertEquals("invalid " + base + ".level: FATAL (must be one of INFO WARN CRITICAL)",
                validateFieldAlarm(badLevel, field("进水温度", "uint16", 2)));

        ModbusServiceFieldAlarm blankText = alarm(">", 80.0, null);
        blankText.text = "  ";
        assertEquals(base + ".text is required",
                validateFieldAlarm(blankText, field("进水温度", "uint16", 2)));

        ModbusServiceFieldAlarm longText = alarm(">", 80.0, null);
        longText.text = "超".repeat(65);
        assertEquals("invalid " + base + ".text: too long (64 characters max)",
                validateFieldAlarm(longText, field("进水温度", "uint16", 2)));

        ModbusServiceFieldAlarm noThreshold = alarm(">", null, null);
        assertEquals(base + ".threshold is required (compare >)",
                validateFieldAlarm(noThreshold, field("进水温度", "uint16", 2)));

        ModbusServiceFieldAlarm nan = alarm(">", Double.NaN, null);
        assertEquals("invalid " + base + ".threshold: NaN",
                validateFieldAlarm(nan, field("进水温度", "uint16", 2)));
    }

    @Test
    void rejectsNullRuleInAGroup() {
        // 数组里的空元素：要么是前端拼错了，要么是手工改库改出来的，收下来判定时会 NPE
        assertEquals("functions[0].response[0].alarms[1] is null",
                validateFieldAlarms(Arrays.asList(alarm(">", 80.0, null), null),
                        field("进水温度", "uint16", 2)));
    }

    @Test
    void rejectsAlarmOnWriteFunction() {
        // 写方法的应答是请求回显，没有「读到的值」可言 —— 与 interval 同一条规则
        ModbusService service = service();
        ModbusServiceFunction write = service.functions.get(0);
        write.request = "0105000000010A0A";
        write.response.get(0).alarms = new ArrayList<>(List.of(alarm(">", 80.0, null)));

        assertEquals("invalid functions[0].response[0].alarms[0]: only read functions (fc 01/02/03/04) can have alarms",
                ModbusServiceValidator.validate(service));
    }

    @Test
    void rejectsAlarmOnStringField() {
        // string 字段出的是任意文本，没有可比的量
        ModbusServiceFieldAlarm a = alarm(">", 80.0, null);
        assertEquals("invalid functions[0].response[0].alarms[0]: a string field has no comparable value",
                validateFieldAlarm(a, field("设备型号", "string", 4)));
    }

    @Test
    void rejectsNumericCompareOnValueListField() {
        // 带 value-list 的字段出的是描述字符串：配 > 会随原始值是否命中取值表而时灵时不灵
        ModbusServiceField named = field("水泵状态", "uint16", 2);
        named.valueList = new ArrayList<>(List.of(value(0L, "OFF"), value(1L, "ON")));

        assertEquals("invalid functions[0].response[0].alarms[0].compare: > (a value-list field yields names, use =)",
                validateFieldAlarm(alarm(">", 80.0, null), named));
    }

    @Test
    void rejectsBadEqualsOnValueListField() {
        String base = "functions[0].response[0].alarms[0]";
        ModbusServiceField named = field("水泵状态", "uint16", 2);
        named.valueList = new ArrayList<>(List.of(value(0L, "OFF"), value(1L, "ON")));

        assertEquals(base + ".state is required (compare = on a value-list field)",
                validateFieldAlarm(alarm("=", null, null), named));

        // 不在取值表里的描述：判定时永远比不中，是静默失效，直接拒
        assertEquals("invalid " + base + ".state: RUNAWAY (not in this field's value-list)",
                validateFieldAlarm(alarm("=", null, "RUNAWAY"), named));

        // 取值表字段比数也不对：那种字段的值有时是字符串
        assertEquals("invalid " + base + ".threshold: not allowed on a value-list field (use state)",
                validateFieldAlarm(alarm("=", 1.0, "ON"), named));
    }

    @Test
    void rejectsBothThresholdAndState() {
        assertEquals("invalid functions[0].response[0].alarms[0].state: only allowed on a value-list field (use threshold)",
                validateFieldAlarm(alarm("=", 1.0, "ON"), field("进水温度", "uint16", 2)));
        assertEquals("invalid functions[0].response[0].alarms[0].state: only allowed when compare is =",
                validateFieldAlarm(alarm(">", 80.0, "ON"), field("进水温度", "uint16", 2)));
        assertEquals("functions[0].response[0].alarms[0].threshold is required (compare =)",
                validateFieldAlarm(alarm("=", null, null), field("进水温度", "uint16", 2)));
    }

    @Test
    void rejectsBadBitAlarm() {
        String base = "functions[0].response[0].bit-list[0].alarms[0]";
        ModbusServiceField bits = field("进水阀位", "uint8", 1);
        bits.bitList = new ArrayList<>(List.of(bit(0, "进水阀")));

        // 位只有 0/1：数值比较与 0.5 都是定义错误
        bits.bitList.get(0).alarms = new ArrayList<>(List.of(alarm(">", 1.0, null)));
        ModbusService service = service();
        service.functions.get(0).response = new ArrayList<>(List.of(bits));
        assertEquals("invalid " + base + ".compare: > (a bit is 0 or 1, use =)",
                ModbusServiceValidator.validate(service));

        bits.bitList.get(0).alarms = new ArrayList<>(List.of(alarm("=", 0.5, null)));
        assertEquals("invalid " + base + ".threshold: 0.5 (a bit is 0 or 1, use =)",
                ModbusServiceValidator.validate(service));
    }

    @Test
    void bitRuleGroupHasItsOwnIdSpace() {
        // id 的唯一性只在「同一位」内要求：位与父字段各是一组、各开各的行，同名不冲突
        String base = "functions[0].response[0].bit-list[0].alarms";
        ModbusServiceField bits = field("进水阀位", "uint8", 1);
        bits.bitList = new ArrayList<>(List.of(bit(0, "进水阀")));
        bits.bitList.get(0).alarms = new ArrayList<>(List.of(alarm("r1", "=", 1.0, null)));

        ModbusService service = service();
        service.functions.get(0).response = new ArrayList<>(List.of(bits));
        assertNull(ModbusServiceValidator.validate(service));

        // 位组内自己重复，照拒
        bits.bitList.get(0).alarms = new ArrayList<>(
                List.of(alarm("r1", "=", 1.0, null), alarm("r1", "=", 0.0, null)));
        assertEquals("invalid " + base + "[1].id: r1 (duplicated)",
                ModbusServiceValidator.validate(service));
    }

    // ===== 夹具 =====

    /** 一份各处都合法的定义：各用例只改自己关心的那一处。 */
    private static ModbusService service() {
        ModbusService service = new ModbusService();
        service.name = "1 号冷水机组";
        service.version = 1;

        DeviceOn device = new DeviceOn();
        device.did = "did-1";
        device.siid = 1;
        device.aiid = 1;
        device.argument = 1;
        service.device = device;

        service.functions = new ArrayList<>(List.of(function(1), function(2)));
        return service;
    }

    private static ModbusServiceFunction function(int index) {
        ModbusServiceFunction function = new ModbusServiceFunction();
        function.index = index;
        function.name = "读进水温度";
        function.request = "010300000001840A";

        ModbusServiceField field = new ModbusServiceField();
        field.index = 1;
        field.field = "进水温度";
        field.bytes = 2;
        field.format = "uint16";
        field.byteOrder = "ABCD";
        function.response = new ArrayList<>(List.of(field));
        return function;
    }

    private static ModbusServiceFieldValue value(Long value, String description) {
        ModbusServiceFieldValue entry = new ModbusServiceFieldValue();
        entry.value = value;
        entry.description = description;
        return entry;
    }

    private static ModbusServiceFieldBit bit(int offset, String field) {
        ModbusServiceFieldBit entry = new ModbusServiceFieldBit();
        entry.offset = offset;
        entry.field = field;
        return entry;
    }

    /** 一份「启用之后应当自洽」的规则：各用例只改自己关心的那一处。{@code id} 默认 {@code r1}。 */
    private static ModbusServiceFieldAlarm alarm(String compare, Double threshold, String state) {
        return alarm("r1", compare, threshold, state);
    }

    /** 同上，但规则身份自己给（{@code null} 用来测「启用了却没有 id」）。 */
    private static ModbusServiceFieldAlarm alarm(String id, String compare, Double threshold, String state) {
        ModbusServiceFieldAlarm alarm = new ModbusServiceFieldAlarm();
        alarm.id = id;
        alarm.enabled = true;
        alarm.compare = compare;
        alarm.threshold = threshold;
        alarm.state = state;
        alarm.level = "WARN";
        alarm.text = "温度过高";
        return alarm;
    }

    private static ModbusServiceField field(String name, String format, Integer bytes) {
        ModbusServiceField field = new ModbusServiceField();
        field.index = 1;
        field.field = name;
        field.bytes = bytes;
        field.format = format;
        if (bytes != null && bytes > 1) {
            field.byteOrder = "ABCD";
        }
        return field;
    }

    /** 把这条规则挂到服务唯一那个方法的唯一那个字段上，返回校验结果。 */
    private static String validateFieldAlarm(ModbusServiceFieldAlarm alarm, ModbusServiceField field) {
        return validateFieldAlarms(new ArrayList<>(List.of(alarm)), field);
    }

    /** 同上，但一次挂一组规则。 */
    private static String validateFieldAlarms(List<ModbusServiceFieldAlarm> alarms, ModbusServiceField field) {
        ModbusService service = service();
        field.alarms = new ArrayList<>(alarms);
        service.functions.get(0).response = new ArrayList<>(List.of(field));
        return ModbusServiceValidator.validate(service);
    }
}
