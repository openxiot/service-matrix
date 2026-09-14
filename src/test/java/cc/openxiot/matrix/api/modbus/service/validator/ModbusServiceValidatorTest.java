package cc.openxiot.matrix.api.modbus.service.validator;

import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldValue;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import cc.openxiot.matrix.db.modbus.service.DeviceOn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
}
