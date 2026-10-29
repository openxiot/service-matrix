package cc.openxiot.matrix.api.modbus.fd.validator;

import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistries;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import cc.openxiot.matrix.db.modbus.config.ModbusSlave;
import cc.openxiot.matrix.db.modbus.fd.poll.ModbusPollFrom;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.modbus.fd.poll.response.field.ModbusResponseField;
import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequest;
import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequests;
import cc.openxiot.matrix.db.modbus.fd.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.fd.poll.response.ModbusResponses;
import cc.openxiot.matrix.db.modbus.fd.DeviceOn;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link FieldDeviceValidator} 的规则校验（MODBUS.md 第七节）。
 *
 * <p>纯函数，不需要数据库：这是这套校验唯一能跑起来的地方（{@code @QuarkusTest} 要连 Mongo）。</p>
 *
 * <p>寄存器中心：字段只引用 {@code registryIndex}，解码元数据从 {@code configSnapshot.registries}
 * 取（见 {@link ModbusRegistries}）。</p>
 */
class FieldDeviceValidatorTest {

    // ===== 合法基线 =====

    @Test
    void acceptsACompleteDefinition() {
        assertNull(FieldDeviceValidator.validate(fd()));
    }

    // ===== 顶层 =====

    @Test
    void rejectsMissingScalars() {
        FieldDevice noName = fd();
        noName.name = null;
        assertEquals("name is required", FieldDeviceValidator.validate(noName));

        FieldDevice noVersion = fd();
        noVersion.version = null;
        assertEquals("version is required", FieldDeviceValidator.validate(noVersion));

        FieldDevice noSnapshot = fd();
        noSnapshot.configSnapshot = null;
        assertEquals("configSnapshot is required", FieldDeviceValidator.validate(noSnapshot));

        FieldDevice noSnapshotId = fd();
        noSnapshotId.configSnapshot.id = null;
        assertEquals("configSnapshot.id is required", FieldDeviceValidator.validate(noSnapshotId));
    }

    @Test
    void rejectsNullBody() {
        assertEquals("body is required", FieldDeviceValidator.validate(null));
    }

    @Test
    void rejectsBadDevice() {
        FieldDevice noDevice = fd();
        noDevice.device = null;
        assertEquals("device is required", FieldDeviceValidator.validate(noDevice));

        FieldDevice noDid = fd();
        noDid.device.did = " ";
        assertEquals("device.did is required", FieldDeviceValidator.validate(noDid));
    }

    // ===== functions =====

    @Test
    void rejectsMissingOrDuplicatedFunctionIndex() {
        FieldDevice noFunctions = fd();
        noFunctions.polls = null;
        assertEquals("polls is required", FieldDeviceValidator.validate(noFunctions));

        FieldDevice noIndex = fd();
        noIndex.polls.get(0).from = null;
        assertEquals("polls[0].from is required", FieldDeviceValidator.validate(noIndex));

        FieldDevice duplicated = fd();
        duplicated.polls.get(1).index = 1;
        assertEquals("invalid polls[1].index: 1 (duplicated)", FieldDeviceValidator.validate(duplicated));
    }

    @Test
    void rejectsOldVersion() {
        FieldDevice old = fd();
        old.version = 1;
        assertEquals("invalid version: 1 (this server serves version " + FieldDevice.VERSION + ")",
                FieldDeviceValidator.validate(old));
    }

    // ===== request：公共字段 =====

    @Test
    void rejectsMissingRequestScalars() {
        FieldDevice noSlave = fd();
        noSlave.polls.get(0).request.slaveId = null;
        assertEquals("polls[0].request.slaveId is required", FieldDeviceValidator.validate(noSlave));

        FieldDevice noFc = fd();
        noFc.polls.get(0).request.fc = " ";
        assertEquals("polls[0].request.fc is required", FieldDeviceValidator.validate(noFc));

        FieldDevice noStart = fd();
        noStart.polls.get(0).request.start = null;
        assertEquals("polls[0].request.start is required", FieldDeviceValidator.validate(noStart));
    }

    @Test
    void rejectsOutOfRangeRequestScalars() {
        FieldDevice badSlave = fd();
        badSlave.polls.get(0).request.slaveId = 248;
        assertEquals("invalid polls[0].request.slaveId: 248 (must be 1..247)",
                FieldDeviceValidator.validate(badSlave));

        FieldDevice badStart = fd();
        badStart.polls.get(0).request.start = 65536;
        assertEquals("invalid polls[0].request.start: 65536 (must be 0..65535)",
                FieldDeviceValidator.validate(badStart));
    }

    @Test
    void rejectsUnknownFunctionCode() {
        FieldDevice badFc = fd();
        badFc.polls.get(0).request.fc = "07";
        assertEquals("invalid polls[0].request.fc: 07 (must be one of 01 02 03 04 05 06 0F 10)",
                FieldDeviceValidator.validate(badFc));
    }

    // ===== request：读 =====

    @Test
    void rejectsBadReadQuantity() {
        FieldDevice zero = fd();
        zero.polls.get(0).request.quantity = 0;
        assertEquals("invalid polls[0].request.quantity: 0 (must be 1..125 registers)",
                FieldDeviceValidator.validate(zero));

        FieldDevice tooMany = fd();
        tooMany.polls.get(0).request.quantity = 126;
        assertEquals("invalid polls[0].request.quantity: 126 (must be 1..125 registers)",
                FieldDeviceValidator.validate(tooMany));
    }

    @Test
    void readBitsUseTheBitsBound() {
        FieldDevice tooManyBits = fd();
        tooManyBits.polls.get(0).request = ModbusRequests.read("02", 0, 2001);
        assertEquals("invalid polls[0].request.quantity: 2001 (must be 1..2000 bits)",
                FieldDeviceValidator.validate(tooManyBits));
    }

    @Test
    void rejectsFieldsOnReadFunctions() {
        FieldDevice readWithFields = fd();
        readWithFields.polls.get(0).request.fields = new ArrayList<>(List.of(ModbusRequests.field(41, 70)));
        assertEquals("invalid polls[0].request.fields: not allowed on a read function (fc 03)",
                FieldDeviceValidator.validate(readWithFields));
    }

    // ===== request：写 =====

    @Test
    void rejectsQuantityOnWriteFunctions() {
        FieldDevice written = writeService(singleRegister());
        written.polls.get(0).request.quantity = 1;
        assertEquals("invalid polls[0].request.quantity: 1 (not allowed on a write function (fc 06), "
                        + "the frame's count comes from registers)",
                FieldDeviceValidator.validate(written));
    }

    @Test
    void rejectsWriteWithoutFields() {
        FieldDevice noFields = writeService(ModbusRequests.write("06", 0));
        assertEquals("polls[0].request.fields is required (a write function needs the registers it writes)",
                FieldDeviceValidator.validate(noFields));
    }

    @Test
    void rejectsManyFieldsOnSingleValueFunctions() {
        FieldDevice twoFields = writeService(ModbusRequests.write("06", 100,
                ModbusRequests.field(41, 70), ModbusRequests.field(43, 1)));
        assertEquals("invalid polls[0].request.fields: fc 06 writes exactly one field, got 2",
                FieldDeviceValidator.validate(twoFields));
    }

    @Test
    void rejectsRegistryMissingOrDuplicated() {
        FieldDevice bogus = writeService(ModbusRequests.write("06", 100, ModbusRequests.field(999, 1)));
        assertEquals("invalid polls[0].request.fields[0].registryIndex: 999 (not in configSnapshot.registries)",
                FieldDeviceValidator.validate(bogus));

        FieldDevice duplicated = writeService(ModbusRequests.write("10", 100,
                ModbusRequests.field(41, 1), ModbusRequests.field(41, 2)));
        assertEquals("invalid polls[0].request.fields[1].registryIndex: 41 (duplicated)",
                FieldDeviceValidator.validate(duplicated));
    }

    @Test
    void rejectsWriteTypeOutsideTheFunctionCode() {
        // 06 只写一个 16 位寄存器：float32 会在帧里被截掉一半
        FieldDevice wide = writeService(ModbusRequests.write("06", 33, ModbusRequests.field(22, 70.0)));
        assertEquals("invalid polls[0].request.fields[0].registryIndex: 22 (fc 06 writes int16 uint16, registry has float32)",
                FieldDeviceValidator.validate(wide));

        // 10 是寄存器区：位格式没有「写哪个位」可言（那些走 0F）
        FieldDevice bit = writeService(ModbusRequests.write("10", 64, ModbusRequests.field(31, true)));
        assertEquals("invalid polls[0].request.fields[0].registryIndex: 31 (fc 10 writes "
                        + "float32 int16 int32 uint16 uint32, registry has bit)",
                FieldDeviceValidator.validate(bit));
    }

    @Test
    void acceptsContiguousWriteRegisters() {
        // 寄存器区：uint16（addr 100）+ int32（addr 101，占 2 个寄存器），紧凑排列
        FieldDevice registers = writeService(ModbusRequests.write("10", 100,
                ModbusRequests.field(41, 1), ModbusRequests.field(42, 700)));
        assertNull(FieldDeviceValidator.validate(registers));

        // 位区：两个相邻线圈，偏移 0、1
        FieldDevice bits = writeService(ModbusRequests.write("0F", 64,
                ModbusRequests.field(31, true), ModbusRequests.field(32, false)));
        assertNull(FieldDeviceValidator.validate(bits));
    }

    /** string 只读：写请求引用它，在功能码类型白名单就被挡下（06 与 10 同一口径）。 */
    @Test
    void rejectsWriteToStringRegister() {
        FieldDevice single = writeService(ModbusRequests.write("06", 16, ModbusRequests.field(4, 100)));
        assertEquals("invalid polls[0].request.fields[0].registryIndex: 4 "
                        + "(fc 06 writes int16 uint16, registry has string)",
                FieldDeviceValidator.validate(single));

        FieldDevice multiple = writeService(ModbusRequests.write("10", 16, ModbusRequests.field(4, 100)));
        assertEquals("invalid polls[0].request.fields[0].registryIndex: 4 "
                        + "(fc 10 writes float32 int16 int32 uint16 uint32, registry has string)",
                FieldDeviceValidator.validate(multiple));
    }

    /** string 读：请求 quantity 是寄存器数（length/2），应答一个字段声明同样的字节数。 */
    @Test
    void acceptsStringReadReconciliation() {
        FieldDevice stringRead = fd();
        ModbusRegistry model = ModbusRegistries.byIndex(stringRead.configSnapshot.registries).get(4);
        model.length = 8;   // 8 字节 = 4 个寄存器
        stringRead.polls.get(0).request = ModbusRequests.read("03", 16, 4);
        stringRead.polls.get(0).response = ModbusResponses.of(responseField(4));
        assertNull(FieldDeviceValidator.validate(stringRead));
    }

    /** string 读的字节数对不上（quantity 按寄存器数、字段按 length）：拒绝。 */
    @Test
    void rejectsStringReadWithWrongQuantity() {
        FieldDevice stringRead = fd();
        ModbusRegistry model = ModbusRegistries.byIndex(stringRead.configSnapshot.registries).get(4);
        model.length = 8;
        stringRead.polls.get(0).request = ModbusRequests.read("03", 16, 2);   // 只读 4 字节
        stringRead.polls.get(0).response = ModbusResponses.of(responseField(4));
        assertEquals("invalid polls[0].response: declares 8 bytes, but fc 03 with quantity 2 reads 4",
                FieldDeviceValidator.validate(stringRead));
    }

    @Test
    void rejectsWriteRegistersWithGaps() {
        // fc 10 的帧只有「起始地址 + 个数 + 紧凑数据区」，没有空洞：reg43(addr 103) 应从 102 起
        FieldDevice gap = writeService(ModbusRequests.write("10", 100,
                ModbusRequests.field(41, 1), ModbusRequests.field(43, 70)));
        assertEquals("invalid polls[0].request.fields: register offset of registry 43 is 3 "
                        + "(must be 1, registers are ordered by address without gaps)",
                FieldDeviceValidator.validate(gap));

        // fc 0F：reg32(addr 65) 应从 65 起
        FieldDevice bitGap = writeService(ModbusRequests.write("0F", 64,
                ModbusRequests.field(31, true), ModbusRequests.field(32, false)));
        assertNull(FieldDeviceValidator.validate(bitGap));
    }

    @Test
    void rejectsBadDefaultWriteValue() {
        // 线圈只有 true/false：写 1 的人多半以为位就是 0/1
        FieldDevice notBoolean = writeService(ModbusRequests.write("05", 64, ModbusRequests.field(31, 1)));
        assertEquals("invalid polls[0].request.fields[0].value: 1 (a bit is true or false)",
                FieldDeviceValidator.validate(notBoolean));

        // 整数格式不能带小数
        FieldDevice fractional = writeService(ModbusRequests.write("06", 100, ModbusRequests.field(41, 1.5)));
        assertEquals("invalid polls[0].request.fields[0].value: 1.5 (format uint16 needs an integer)",
                FieldDeviceValidator.validate(fractional));

        FieldDevice overflow = writeService(ModbusRequests.write("06", 100, ModbusRequests.field(41, 70000)));
        assertEquals("invalid polls[0].request.fields[0].value: 70000 (out of range for uint16)",
                FieldDeviceValidator.validate(overflow));
    }

    @Test
    void acceptsNumericDefaultOnFloatField() {
        // float32 出的是任意有限数值，7.5 在这里是合法的（与 int16 那条正好相对）
        FieldDevice decimal = writeService(ModbusRequests.write("10", 33, ModbusRequests.field(22, 7.5)));
        assertNull(FieldDeviceValidator.validate(decimal));
    }

    @Test
    void acceptsRegistryWriteFunction() {
        // 寄存器中心：可写寄存器折一条 registry access:"write" 的 fc 06 方法写任意值（无 response）
        FieldDevice regWrite = writeService(singleRegister());
        regWrite.polls.get(0).from.type = "registry";
        regWrite.polls.get(0).from.access = "write";
        assertNull(FieldDeviceValidator.validate(regWrite));
    }

    @Test
    void stillRequiresResponseOnRegistryReadFunction() {
        FieldDevice regRead = fd();
        regRead.polls.get(0).from.type = "registry";
        regRead.polls.get(0).response = null;
        assertEquals("polls[0].response is required for a registry poll (the register-derived read block must declare fields)",
                FieldDeviceValidator.validate(regRead));
    }

    // ===== response：注册表引用 =====

    @Test
    void rejectsUndefinedResponseRegistry() {
        FieldDevice bogus = fd();
        bogus.polls.get(0).response.fields.get(0).registryIndex = 999;
        assertEquals("invalid polls[0].response.fields[0].registryIndex: 999 (not in configSnapshot.registries)",
                FieldDeviceValidator.validate(bogus));

        FieldDevice noIndex = fd();
        noIndex.polls.get(0).response.fields.get(0).registryIndex = null;
        assertEquals("polls[0].response.fields[0].registryIndex is required",
                FieldDeviceValidator.validate(noIndex));
    }

    @Test
    void rejectsDuplicatedResponseRegistries() {
        FieldDevice duplicated = fd();
        duplicated.polls.get(0).response.fields.add(responseField(1));
        assertEquals("invalid polls[0].response.fields[1].registryIndex: 1 (duplicated)",
                FieldDeviceValidator.validate(duplicated));
    }

    @Test
    void rejectsUnreadableRegistryType() {
        FieldDevice strange = fd();
        strange.polls.get(0).response.fields.get(0).registryIndex = 4;   // string 可读
        assertNull(FieldDeviceValidator.validate(strange));

        // 64 位数值不在读词表（parser 只解 ≤32 位 + string）：引用它读不了
        FieldDevice wide = fd();
        wide.polls.get(0).response.fields.get(0).registryIndex = 90;
        assertEquals("invalid polls[0].response.fields[0].registryIndex: 90 (data type uint64 is not readable)",
                FieldDeviceValidator.validate(wide));
    }

    @Test
    void rejectsResponseNotMatchingTheReadQuantity() {
        // 换成 uint32（4 字节）引用，而 quantity 1 只读 2 字节
        FieldDevice wide = fd();
        wide.polls.get(0).response.fields.get(0).registryIndex = 2;
        assertEquals("invalid polls[0].response: declares 4 bytes, but fc 03 with quantity 1 reads 2",
                FieldDeviceValidator.validate(wide));

        // 请求改成读 2 个寄存器（4 字节），但应答只声明了 1 只 uint16（2 字节）
        FieldDevice narrow = fd();
        narrow.polls.get(0).request = ModbusRequests.read("03", 0, 2);
        assertEquals("invalid polls[0].response: declares 2 bytes, but fc 03 with quantity 2 reads 4",
                FieldDeviceValidator.validate(narrow));
    }

    @Test
    void rejectsMissingResponseOnReadFunction() {
        FieldDevice noResponse = fd();
        noResponse.polls.get(0).response = null;
        assertEquals("polls[0].response is required for a read function (a method with no field returns {})",
                FieldDeviceValidator.validate(noResponse));

        FieldDevice emptyResponse = fd();
        emptyResponse.polls.get(0).response = ModbusResponses.of();
        assertEquals("polls[0].response is required for a read function (a method with no field returns {})",
                FieldDeviceValidator.validate(emptyResponse));
    }

    @Test
    void rejectsResponseOnWriteFunction() {
        FieldDevice echoed = writeService(singleRegister());
        echoed.polls.get(0).response = ModbusResponses.of(responseField(1));
        assertEquals("invalid polls[0].response: not allowed on a write function (the reply echoes the request)",
                FieldDeviceValidator.validate(echoed));
    }

    @Test
    void rejectsInconsistentSlaveId() {
        FieldDevice mixed = fd();
        mixed.polls.get(1).request.slaveId = 2;
        assertEquals("invalid polls[1].request.slaveId: 2 (must equal service slaveId 1)",
                FieldDeviceValidator.validate(mixed));
    }

    // ===== interval / polling =====

    @Test
    void acceptsIntervalOnReadFunctions() {
        FieldDevice polled = fd();
        polled.polls.get(0).interval = 5;
        assertNull(FieldDeviceValidator.validate(polled));
    }

    @Test
    void acceptsIntervalBounds() {
        FieldDevice fastest = fd();
        fastest.polls.get(0).interval = 5;
        assertNull(FieldDeviceValidator.validate(fastest));

        FieldDevice slowest = fd();
        slowest.polls.get(0).interval = 3600;
        assertNull(FieldDeviceValidator.validate(slowest));
    }

    @Test
    void rejectsIntervalOnWriteFunctions() {
        FieldDevice written = writeService(singleRegister());
        written.polls.get(0).interval = 5;
        assertEquals("invalid polls[0].interval: 5 (only read functions (fc 01/02/03/04) can be polled)",
                FieldDeviceValidator.validate(written));
    }

    @Test
    void rejectsIntervalOutOfRange() {
        FieldDevice belowMin = fd();
        belowMin.polls.get(0).interval = 4;
        assertEquals("invalid polls[0].interval: 4 (interval must be between 5 and 3600 seconds)",
                FieldDeviceValidator.validate(belowMin));

        FieldDevice tooRare = fd();
        tooRare.polls.get(0).interval = 3601;
        assertEquals("invalid polls[0].interval: 3601 (interval must be between 5 and 3600 seconds)",
                FieldDeviceValidator.validate(tooRare));
    }

    @Test
    void acceptsPollingOnReadFunctions() {
        FieldDevice polling = fd();
        polling.polls.get(0).interval = 5;
        polling.polls.get(0).polling = true;
        assertNull(FieldDeviceValidator.validate(polling));

        FieldDevice paused = fd();
        paused.polls.get(0).interval = 5;
        paused.polls.get(0).polling = false;
        assertNull(FieldDeviceValidator.validate(paused));
    }

    @Test
    void rejectsPollingWithoutInterval() {
        FieldDevice noInterval = fd();
        noInterval.polls.get(0).polling = true;
        assertEquals("invalid polls[0].polling: true (polling requires an interval)",
                FieldDeviceValidator.validate(noInterval));
    }

    @Test
    void rejectsPollingOnWriteFunctions() {
        FieldDevice written = writeService(singleRegister());
        written.polls.get(0).polling = true;
        assertEquals("invalid polls[0].polling: true (only read functions (fc 01/02/03/04) can be polled)",
                FieldDeviceValidator.validate(written));

        FieldDevice intervalOnly = writeService(singleRegister());
        intervalOnly.polls.get(0).interval = 5;
        assertEquals("invalid polls[0].interval: 5 (only read functions (fc 01/02/03/04) can be polled)",
                FieldDeviceValidator.validate(intervalOnly));
    }

    // ===== 告警配置（寄存器中心，未受本次改动影响） =====

    @Test
    void acceptsAlarms() {
        assertNull(validateAlarm(alarm(">", 80.0, null), holding("进水温度", "uint16")));
        ModbusRegistry named = holding("水泵状态", "uint16");
        named.enumMap = Map.of("0", "OFF", "1", "ON");
        assertNull(validateAlarm(alarm("=", null, "ON"), named));
        assertNull(validateAlarm(alarm("=", 1.0, null), holding("进水温度", "uint16")));
    }

    @Test
    void acceptsARuleGroupOnOneField() {
        ModbusRegistryAlarm low = alarm("r1", "<", 20.0, null);
        low.level = "WARN";
        ModbusRegistryAlarm high = alarm("r2", ">", 26.0, null);
        high.level = "INFO";
        ModbusRegistryAlarm worst = alarm("r3", ">", 30.0, null);
        worst.level = "CRITICAL";
        assertNull(validateAlarms(List.of(low, high, worst), holding("进水温度", "uint16")));
    }

    @Test
    void rejectsBadAlarmIds() {
        String base = "configSnapshot.registries[0].alarms";
        ModbusRegistry temperature = holding("进水温度", "uint16");
        assertEquals(base + "[0].id is required",
                validateAlarms(List.of(alarm(null, ">", 80.0, null)), temperature));
        ModbusRegistryAlarm longId = alarm("x".repeat(65), ">", 80.0, null);
        assertEquals("invalid " + base + "[0].id: too long (64 characters max)",
                validateAlarms(List.of(longId), temperature));
    }

    @Test
    void rejectsTooManyRulesOnOneField() {
        String base = "configSnapshot.registries[0].alarms";
        ModbusRegistry temperature = holding("进水温度", "uint16");
        List<ModbusRegistryAlarm> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(alarm("r" + i, ">", 80.0 + i, null));
        }
        assertEquals("invalid " + base + ": too many rules (8 max)",
                validateAlarms(nine, temperature));
    }

    @Test
    void acceptsBitAlarm() {
        assertNull(validateAlarm(alarm("=", 1.0, null), coil("进水阀")));
    }

    @Test
    void rejectsBadAlarmScalars() {
        String base = "configSnapshot.registries[0].alarms[0]";
        ModbusRegistryAlarm badCompare = alarm("!=", 80.0, null);
        assertEquals("invalid " + base + ".compare: != (must be one of > >= < <= =)",
                validateAlarm(badCompare, holding("进水温度", "uint16")));
        ModbusRegistryAlarm noThreshold = alarm(">", null, null);
        assertEquals(base + ".threshold is required (compare >)",
                validateAlarm(noThreshold, holding("进水温度", "uint16")));
    }

    @Test
    void rejectsAlarmOnWriteOnlyRegister() {
        ModbusRegistry writeReg = holding("进水温度", "uint16");
        writeReg.access = "W";
        assertEquals("invalid configSnapshot.registries[0].alarms[0]: only read functions (fc 01/02/03/04) can have alarms",
                validateAlarm(alarm(">", 80.0, null), writeReg));
    }

    @Test
    void rejectsAlarmOnStringRegister() {
        assertEquals("invalid configSnapshot.registries[0].alarms[0]: a string field has no comparable value",
                validateAlarm(alarm(">", 80.0, null), holding("设备型号", "string")));
    }

    @Test
    void rejectsNumericCompareOnEnumRegister() {
        ModbusRegistry named = holding("水泵状态", "uint16");
        named.enumMap = Map.of("0", "OFF", "1", "ON");
        assertEquals("invalid configSnapshot.registries[0].alarms[0].compare: > (a value-list field yields names, use =)",
                validateAlarm(alarm(">", 80.0, null), named));
    }

    // ===== invoke 入参（values 以 registryIndex 为 key） =====

    @Test
    void acceptsInvokeWithEveryFieldDefaulted() {
        ModbusPoll write = writeFunction(singleRegister());
        assertNull(FieldDeviceValidator.validateInvoke(write, null, byIndex()));
        assertNull(FieldDeviceValidator.validateInvoke(write, Map.of(), byIndex()));
    }

    @Test
    void invokeRejectsValuesOnReadFunctions() {
        assertEquals("invalid values: a read function (fc 03) takes no values",
                FieldDeviceValidator.validateInvoke(poll(1), Map.of("1", 1), byIndex()));
        assertNull(FieldDeviceValidator.validateInvoke(poll(1), null, byIndex()));
    }

    @Test
    void invokeRequiresFieldsWithoutDefault() {
        ModbusPoll write = writeFunction(ModbusRequests.write("06", 100, ModbusRequests.field(41)));
        assertEquals("values.41 is required (this register has no default)",
                FieldDeviceValidator.validateInvoke(write, null, byIndex()));
        assertNull(FieldDeviceValidator.validateInvoke(write, Map.of("41", 70), byIndex()));
    }

    @Test
    void invokeRejectsUnknownRegister() {
        ModbusPoll write = writeFunction(singleRegister());
        assertEquals("invalid values.43: not a register of this function",
                FieldDeviceValidator.validateInvoke(write, Map.of("43", 1), byIndex()));
    }

    @Test
    void invokeChecksValueTypeAndRange() {
        ModbusPoll write = writeFunction(singleRegister());
        assertEquals("invalid values.41: 70000 (out of range for uint16)",
                FieldDeviceValidator.validateInvoke(write, Map.of("41", 70000), byIndex()));
        assertEquals("invalid values.41: 70.5 (format uint16 needs an integer)",
                FieldDeviceValidator.validateInvoke(write, Map.of("41", 70.5), byIndex()));
        assertEquals("invalid values.41: abc (a register value is a finite number)",
                FieldDeviceValidator.validateInvoke(write, Map.of("41", "abc"), byIndex()));
        assertNull(FieldDeviceValidator.validateInvoke(write, Map.of("41", 70.0), byIndex()));
    }

    @Test
    void invokeChecksBitValues() {
        ModbusPoll write = writeFunction(ModbusRequests.write("05", 64, ModbusRequests.field(31, true)));
        assertEquals("invalid values.31: 1 (a bit is true or false)",
                FieldDeviceValidator.validateInvoke(write, Map.of("31", 1), byIndex()));
        assertNull(FieldDeviceValidator.validateInvoke(write, Map.of("31", false), byIndex()));
    }

    // ===== 夹具 =====

    /** 寄存器集合：字段以 registryIndex 引用它们。 */
    private static void registries(ModbusConfig config) {
        List<ModbusRegistry> regs = config.registries == null ? new ArrayList<>() : config.registries;
        regs.add(reg(1, 0, "holding", "uint16", "ABCD", "R"));    // 进水温度
        regs.add(reg(2, 2, "holding", "uint32", "ABCD", "R"));    // 累计流量
        regs.add(reg(3, 8, "coil", "coil", null, "RW"));          // 进水阀
        regs.add(reg(4, 16, "holding", "string", null, "R"));     // 型号
        regs.add(reg(21, 32, "holding", "uint16", "ABCD", "RW")); // 背光时间
        regs.add(reg(22, 33, "holding", "float32", "ABCD", "RW"));// 设定温度float
        regs.add(reg(31, 64, "coil", "coil", null, "RW"));        // coil1
        regs.add(reg(32, 65, "coil", "coil", null, "RW"));        // coil2
        regs.add(reg(41, 100, "holding", "uint16", "ABCD", "RW"));// 设定温度
        regs.add(reg(42, 101, "holding", "int32", "ABCD", "RW")); // 温度2
        regs.add(reg(43, 103, "holding", "uint16", "ABCD", "RW"));// 运行模式
        regs.add(reg(90, 200, "holding", "uint64", "ABCD", "R")); // 64 位：不可读
        config.registries = regs;
    }

    private static ModbusRegistry reg(int index, int address, String area, String dataType,
                                      String byteOrder, String access) {
        ModbusRegistry r = new ModbusRegistry();
        r.index = index;
        r.address = address;
        r.area = area;
        r.dataType = dataType;
        r.byteOrder = byteOrder;
        r.access = access;
        return r;
    }

    private static Map<Integer, ModbusRegistry> byIndex() {
        ModbusConfig c = new ModbusConfig();
        registries(c);
        return ModbusRegistries.byIndex(c.registries);
    }

    private static FieldDevice fd() {
        FieldDevice fd = new FieldDevice();
        fd.name = "1 号冷水机组";
        fd.version = FieldDevice.VERSION;
        fd.configSnapshot = configSnapshot();
        fd.slaveId = 1;
        fd.device = device();
        fd.polls = new ArrayList<>(List.of(poll(1), poll(2)));
        return fd;
    }

    private static ModbusConfig configSnapshot() {
        ModbusConfig config = new ModbusConfig();
        config.id = new ObjectId("65f0c0ffee0123456789abcd");
        config.slave = new ModbusSlave();
        config.slave.manufacturer = "特灵";
        config.slave.model = "19XRV";
        registries(config);
        return config;
    }

    private static FieldDevice writeService(ModbusRequest request) {
        FieldDevice fd = new FieldDevice();
        fd.name = "1 号冷水机组";
        fd.version = FieldDevice.VERSION;
        fd.configSnapshot = configSnapshot();
        fd.slaveId = 1;
        fd.device = device();

        ModbusPoll write = new ModbusPoll();
        write.index = 1;
        write.from = new ModbusPollFrom();
        write.from.type = "command";
        write.from.index = 1;
        write.name = "写设定温度";
        write.request = request;
        fd.polls = new ArrayList<>(List.of(write));
        return fd;
    }

    private static ModbusPoll poll(int index) {
        ModbusPoll poll = new ModbusPoll();
        poll.index = index;
        poll.from = new ModbusPollFrom();
        poll.from.type = "command";
        poll.from.index = index;
        poll.name = "读进水温度";
        poll.request = index == 1 ? ModbusRequests.holding() : ModbusRequests.read("03", 0, 2);
        poll.response = ModbusResponses.of(responseField(index));
        return poll;
    }

    private static ModbusResponseField responseField(int registryIndex) {
        ModbusResponseField field = new ModbusResponseField();
        field.registryIndex = registryIndex;
        return field;
    }

    private static DeviceOn device() {
        DeviceOn device = new DeviceOn();
        device.did = "did-1";
        device.siid = 1;
        device.aiid = 1;
        device.argument = 1;
        return device;
    }

    /** fc 06 写一个 16 位寄存器（reg 41 @ 100）：最常用的那条写请求。 */
    private static ModbusRequest singleRegister() {
        return ModbusRequests.write("06", 100, ModbusRequests.field(41, 70));
    }

    private static ModbusPoll writeFunction(ModbusRequest request) {
        return writeService(request).polls.get(0);
    }

    // ===== 告警夹具（未变） =====

    private static ModbusRegistryAlarm alarm(String compare, Double threshold, String state) {
        return alarm("r1", compare, threshold, state);
    }

    private static ModbusRegistryAlarm alarm(String id, String compare, Double threshold, String state) {
        ModbusRegistryAlarm alarm = new ModbusRegistryAlarm();
        alarm.id = id;
        alarm.compare = compare;
        alarm.threshold = threshold;
        alarm.state = state;
        alarm.level = "CRITICAL";
        alarm.text = "告警";
        alarm.enabled = true;
        return alarm;
    }

    private static ModbusRegistry holding(String name, String dataType) {
        ModbusRegistry reg = new ModbusRegistry();
        reg.name = name;
        reg.index = 1;
        reg.address = 0;
        reg.area = "holding";
        reg.dataType = dataType;
        reg.access = "R";
        return reg;
    }

    private static ModbusRegistry coil(String name) {
        ModbusRegistry reg = new ModbusRegistry();
        reg.name = name;
        reg.index = 1;
        reg.address = 0;
        reg.area = "coil";
        reg.access = "RW";
        return reg;
    }

    private static String validateAlarm(ModbusRegistryAlarm alarm, ModbusRegistry reg) {
        return FieldDeviceValidator.validateAlarms(List.of(alarm), reg, "configSnapshot.registries[0].alarms");
    }

    private static String validateAlarms(List<ModbusRegistryAlarm> alarms, ModbusRegistry reg) {
        return FieldDeviceValidator.validateAlarms(alarms, reg, "configSnapshot.registries[0].alarms");
    }
}