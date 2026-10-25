package cc.openxiot.matrix.api.modbus.device.validator;

import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import cc.openxiot.matrix.db.modbus.config.ModbusSlave;
import cc.openxiot.matrix.db.modbus.device.poll.ModbusPollFrom;
import cc.openxiot.matrix.db.modbus.device.ModbusDevice;
import cc.openxiot.matrix.db.modbus.device.poll.response.field.ModbusResponseField;
import cc.openxiot.matrix.db.modbus.device.poll.response.field.ModbusResponseFieldBit;
import cc.openxiot.matrix.db.modbus.device.poll.response.field.ModbusResponseFieldValue;
import cc.openxiot.matrix.db.modbus.device.poll.request.ModbusRequest;
import cc.openxiot.matrix.db.modbus.device.poll.request.ModbusRequests;
import cc.openxiot.matrix.db.modbus.device.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.device.poll.response.ModbusResponses;
import cc.openxiot.matrix.db.modbus.device.DeviceOn;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ModbusDeviceValidator} 的规则校验（MODBUS.md 第七节）。
 *
 * <p>纯函数，不需要数据库：这是这套校验唯一能跑起来的地方（{@code @QuarkusTest} 要连 Mongo）。</p>
 */
class ModbusDeviceValidatorTest {

    // ===== 合法基线 =====

    @Test
    void acceptsACompleteDefinition() {
        assertNull(ModbusDeviceValidator.validate(service()));
    }

    // ===== 顶层 =====

    @Test
    void rejectsMissingScalars() {
        ModbusDevice noName = service();
        noName.name = null;
        assertEquals("name is required", ModbusDeviceValidator.validate(noName));

        ModbusDevice noVersion = service();
        noVersion.version = null;
        assertEquals("version is required", ModbusDeviceValidator.validate(noVersion));

        ModbusDevice noSnapshot = service();
        noSnapshot.configSnapshot = null;
        assertEquals("configSnapshot is required", ModbusDeviceValidator.validate(noSnapshot));

        ModbusDevice noSnapshotId = service();
        noSnapshotId.configSnapshot.id = null;
        assertEquals("configSnapshot.id is required", ModbusDeviceValidator.validate(noSnapshotId));
    }

    @Test
    void rejectsNullBody() {
        assertEquals("body is required", ModbusDeviceValidator.validate(null));
    }

    @Test
    void rejectsBadDevice() {
        ModbusDevice noDevice = service();
        noDevice.device = null;
        assertEquals("device is required", ModbusDeviceValidator.validate(noDevice));

        ModbusDevice noDid = service();
        noDid.device.did = " ";
        assertEquals("device.did is required", ModbusDeviceValidator.validate(noDid));

        ModbusDevice zeroSiid = service();
        zeroSiid.device.siid = 0;
        assertEquals("device.siid is required", ModbusDeviceValidator.validate(zeroSiid));

        ModbusDevice zeroArgument = service();
        zeroArgument.device.argument = 0;
        assertEquals("device.argument is required", ModbusDeviceValidator.validate(zeroArgument));
    }

    // ===== functions =====

    @Test
    void rejectsMissingOrDuplicatedFunctionIndex() {
        ModbusDevice noFunctions = service();
        noFunctions.polls = null;
        assertEquals("polls is required", ModbusDeviceValidator.validate(noFunctions));

        ModbusDevice noIndex = service();
        noIndex.polls.get(0).from = null;
        assertEquals("polls[0].from is required", ModbusDeviceValidator.validate(noIndex));

        ModbusDevice duplicated = service();
        duplicated.polls.get(1).index = 1;
        assertEquals("invalid polls[1].index: 1 (duplicated)", ModbusDeviceValidator.validate(duplicated));
    }

    @Test
    void rejectsOldVersion() {
        // version=1 的定义里 request 是一整串帧字符串：读成结构化对象只会得到一堆 null，
        // 然后报一句和病因无关的「request.slaveId is required」。旧口径必须响亮地失败。
        ModbusDevice old = service();
        old.version = 1;
        assertEquals("invalid version: 1 (this server serves version " + ModbusDevice.VERSION + ")",
                ModbusDeviceValidator.validate(old));
    }

    // ===== request：公共字段 =====

    @Test
    void rejectsMissingRequestScalars() {
        ModbusDevice noRequest = service();
        noRequest.polls.get(0).request = null;
        assertEquals("polls[0].request is required", ModbusDeviceValidator.validate(noRequest));

        ModbusDevice noSlave = service();
        noSlave.polls.get(0).request.slaveId = null;
        assertEquals("polls[0].request.slaveId is required", ModbusDeviceValidator.validate(noSlave));

        ModbusDevice noFc = service();
        noFc.polls.get(0).request.fc = " ";
        assertEquals("polls[0].request.fc is required", ModbusDeviceValidator.validate(noFc));

        ModbusDevice noStart = service();
        noStart.polls.get(0).request.start = null;
        assertEquals("polls[0].request.start is required", ModbusDeviceValidator.validate(noStart));
    }

    @Test
    void rejectsOutOfRangeRequestScalars() {
        ModbusDevice badSlave = service();
        badSlave.polls.get(0).request.slaveId = 248;
        assertEquals("invalid polls[0].request.slaveId: 248 (must be 1..247)",
                ModbusDeviceValidator.validate(badSlave));

        ModbusDevice zeroSlave = service();
        zeroSlave.polls.get(0).request.slaveId = 0;
        assertEquals("invalid polls[0].request.slaveId: 0 (must be 1..247)",
                ModbusDeviceValidator.validate(zeroSlave));

        ModbusDevice badStart = service();
        badStart.polls.get(0).request.start = 65536;
        assertEquals("invalid polls[0].request.start: 65536 (must be 0..65535)",
                ModbusDeviceValidator.validate(badStart));
    }

    @Test
    void rejectsUnknownFunctionCode() {
        // 结构化的第一个好处：fc 以前完全不校验（整帧只查「是合法 16 进制且够长」）
        ModbusDevice badFc = service();
        badFc.polls.get(0).request.fc = "07";
        assertEquals("invalid polls[0].request.fc: 07 (must be one of 01 02 03 04 05 06 0F 10)",
                ModbusDeviceValidator.validate(badFc));
    }

    @Test
    void acceptsMixedCaseFunctionCode() {
        ModbusDevice lower = service();
        lower.polls.get(0).request.fc = "0f";
        lower.polls.get(0).request.quantity = null;
        lower.polls.get(0).request.fields = new ArrayList<>(List.of(
                ModbusRequests.field(1, "开机", 0, "bit", true)));
        lower.polls.get(0).response = null;
        // 第二个方法还是读方法，从站一致，故此处的 fc 大小写不敏感是唯一被考的东西
        assertNull(ModbusDeviceValidator.validate(lower));
    }

    // ===== request：读 =====

    @Test
    void rejectsBadReadQuantity() {
        ModbusDevice noQuantity = service();
        noQuantity.polls.get(0).request.quantity = null;
        assertEquals("polls[0].request.quantity is required", ModbusDeviceValidator.validate(noQuantity));

        ModbusDevice zero = service();
        zero.polls.get(0).request.quantity = 0;
        assertEquals("invalid polls[0].request.quantity: 0 (must be 1..125 registers)",
                ModbusDeviceValidator.validate(zero));

        // 126 是寄存器那条线的边界外一格：上限本身的用例在下面（要连应答一起给）
        ModbusDevice tooMany = service();
        tooMany.polls.get(0).request.quantity = 126;
        assertEquals("invalid polls[0].request.quantity: 126 (must be 1..125 registers)",
                ModbusDeviceValidator.validate(tooMany));
    }

    @Test
    void readBitsUseTheBitsBound() {
        // 01/02 的 quantity 是位数，上限 2000 而不是 125
        ModbusDevice tooManyBits = service();
        tooManyBits.polls.get(0).request = ModbusRequests.read("02", 0, 2001);
        assertEquals("invalid polls[0].request.quantity: 2001 (must be 1..2000 bits)",
                ModbusDeviceValidator.validate(tooManyBits));
    }

    @Test
    void acceptsBitRead() {
        // 读 16 个位 = 2 字节，正好是一个 uint16 字段
        ModbusDevice bits = service();
        bits.polls.get(0).request = ModbusRequests.read("02", 0, 16);
        assertNull(ModbusDeviceValidator.validate(bits));
    }

    @Test
    void rejectsFieldsOnReadFunctions() {
        ModbusDevice readWithFields = service();
        readWithFields.polls.get(0).request.fields = new ArrayList<>(List.of(
                ModbusRequests.field(1, "设定温度", "uint16", 70)));
        assertEquals("invalid polls[0].request.fields: not allowed on a read function (fc 03)",
                ModbusDeviceValidator.validate(readWithFields));
    }

    // ===== request：写 =====

    @Test
    void rejectsQuantityOnWriteFunctions() {
        // 写帧里的数量由字段推出，不给配置的人再写一遍的机会（两处不一致时以谁为准是个坑）
        ModbusDevice written = writeService(singleRegister());
        written.polls.get(0).request.quantity = 1;
        assertEquals("invalid polls[0].request.quantity: 1 (not allowed on a write function (fc 06), "
                        + "the frame's count comes from fields)",
                ModbusDeviceValidator.validate(written));
    }

    @Test
    void rejectsWriteWithoutFields() {
        ModbusDevice noFields = writeService(ModbusRequests.write("06", 0));
        assertEquals("polls[0].request.fields is required (a write function needs the fields it writes)",
                ModbusDeviceValidator.validate(noFields));

        ModbusDevice empty = writeService(ModbusRequests.write("10", 0));
        empty.polls.get(0).request.fields = new ArrayList<>();
        assertEquals("polls[0].request.fields is required (a write function needs the fields it writes)",
                ModbusDeviceValidator.validate(empty));
    }

    @Test
    void rejectsManyFieldsOnSingleValueFunctions() {
        // fc 06 的帧里只有一个值，「第几个」无从谈起
        ModbusDevice twoFields = writeService(ModbusRequests.write("06", 0,
                ModbusRequests.field(1, "设定温度", "uint16", 70),
                ModbusRequests.field(2, "运行模式", "uint16", 1)));
        assertEquals("invalid polls[0].request.fields: fc 06 writes exactly one field, got 2",
                ModbusDeviceValidator.validate(twoFields));
    }

    @Test
    void rejectsOffsetOnSingleValueFunctions() {
        ModbusDevice offset = writeService(ModbusRequests.write("05", 0,
                ModbusRequests.field(1, "开机", 0, "bit", true)));
        assertEquals("invalid polls[0].request.fields[0].offset: 0 (fc 05 writes one value)",
                ModbusDeviceValidator.validate(offset));
    }

    @Test
    void rejectsWriteFormatOutsideTheFunctionCode() {
        // 05/0F 是位区
        ModbusDevice register = writeService(ModbusRequests.write("05", 0,
                ModbusRequests.field(1, "开机", "uint16", 1)));
        assertEquals("invalid polls[0].request.fields[0].format: uint16 (fc 05 writes bit)",
                ModbusDeviceValidator.validate(register));

        // 06 只写一个寄存器：32 位格式会被静默截掉一半
        ModbusDevice wide = writeService(ModbusRequests.write("06", 0,
                ModbusRequests.field(1, "设定温度", "float32", 70.0)));
        assertEquals("invalid polls[0].request.fields[0].format: float32 (fc 06 writes int16 uint16)",
                ModbusDeviceValidator.validate(wide));

        // 10 是寄存器区：位格式没有「写哪个位」可言（那些走 0F）
        ModbusDevice bit = writeService(ModbusRequests.write("10", 0,
                ModbusRequests.field(1, "开机", 0, "bit", true)));
        assertEquals("invalid polls[0].request.fields[0].format: bit (fc 10 writes "
                        + "float32 int16 int32 uint16 uint32)",
                ModbusDeviceValidator.validate(bit));
    }

    @Test
    void rejectsDuplicatedWriteIndexAndName() {
        ModbusDevice duplicatedIndex = writeService(ModbusRequests.write("10", 0,
                ModbusRequests.field(1, "设定温度", 0, "uint16", 70),
                ModbusRequests.field(1, "运行模式", 1, "uint16", 1)));
        assertEquals("invalid polls[0].request.fields[1].index: 1 (duplicated)",
                ModbusDeviceValidator.validate(duplicatedIndex));

        // 重名比重复序号更隐蔽：invoke 的 values 拿名字取值，两个字段会抢同一个 key
        ModbusDevice duplicatedName = writeService(ModbusRequests.write("10", 0,
                ModbusRequests.field(1, "设定温度", 0, "uint16", 70),
                ModbusRequests.field(2, "设定温度", 1, "uint16", 1)));
        assertEquals("invalid polls[0].request.fields[1].field: 设定温度 (duplicated)",
                ModbusDeviceValidator.validate(duplicatedName));
    }

    @Test
    void rejectsByteOrderOnWideWriteField() {
        ModbusDevice noOrder = writeService(ModbusRequests.write("10", 0,
                ModbusRequests.field(1, "设定温度", 0, "int32", 700)));
        assertEquals("polls[0].request.fields[0].byteOrder is required",
                ModbusDeviceValidator.validate(noOrder));

        ModbusDevice badOrder = writeService(ModbusRequests.write("10", 0,
                ModbusRequests.field(1, "设定温度", 0, "int32", "ABCDEFGH", 700)));
        assertEquals("invalid polls[0].request.fields[0].byteOrder: ABCDEFGH",
                ModbusDeviceValidator.validate(badOrder));
    }

    @Test
    void acceptsContiguousWriteFields() {
        // 寄存器区：一个 uint16（1 个寄存器）+ 一个 int32（2 个），偏移 0、1
        ModbusDevice registers = writeService(ModbusRequests.write("10", 0,
                ModbusRequests.field(1, "运行模式", 0, "uint16", 1),
                ModbusRequests.field(2, "设定温度", 1, "int32", "ABCD", 700)));
        assertNull(ModbusDeviceValidator.validate(registers));

        // 位区：偏移恰好是 0..n-1
        ModbusDevice bits = writeService(ModbusRequests.write("0F", 0,
                ModbusRequests.field(1, "开机", 0, "bit", true),
                ModbusRequests.field(2, "水泵", 1, "bit", false)));
        assertNull(ModbusDeviceValidator.validate(bits));
    }

    @Test
    void rejectsWriteFieldsWithGaps() {
        // fc 0F/10 的帧只有「起始地址 + 个数 + 紧凑数据区」，没有空洞可言
        ModbusDevice gap = writeService(ModbusRequests.write("10", 0,
                ModbusRequests.field(1, "运行模式", 0, "uint16", 1),
                ModbusRequests.field(2, "设定温度", 2, "uint16", 70)));
        assertEquals("invalid polls[0].request.fields: register offset of field 设定温度 is 2 "
                        + "(must be 1, fields are ordered by index without gaps)",
                ModbusDeviceValidator.validate(gap));

        ModbusDevice bitGap = writeService(ModbusRequests.write("0F", 0,
                ModbusRequests.field(1, "开机", 0, "bit", true),
                ModbusRequests.field(2, "水泵", 2, "bit", false)));
        assertEquals("invalid polls[0].request.fields: bit offset of field 水泵 is 2 "
                        + "(must be 1, fields are ordered by index without gaps)",
                ModbusDeviceValidator.validate(bitGap));
    }

    @Test
    void rejectsBadDefaultWriteValue() {
        // 位只有 true/false：写 1 的人多半以为位就是 0/1
        ModbusDevice notBoolean = writeService(ModbusRequests.write("05", 0,
                ModbusRequests.field(1, "开机", "bit", 1)));
        assertEquals("invalid polls[0].request.fields[0].value: 1 (a bit is true or false)",
                ModbusDeviceValidator.validate(notBoolean));

        // 整数格式不能带小数
        ModbusDevice fractional = writeService(ModbusRequests.write("06", 0,
                ModbusRequests.field(1, "设定温度", "int16", 1.5)));
        assertEquals("invalid polls[0].request.fields[0].value: 1.5 (format int16 needs an integer)",
                ModbusDeviceValidator.validate(fractional));

        ModbusDevice overflow = writeService(ModbusRequests.write("06", 0,
                ModbusRequests.field(1, "设定温度", "uint16", 70000)));
        assertEquals("invalid polls[0].request.fields[0].value: 70000 (out of range for uint16)",
                ModbusDeviceValidator.validate(overflow));
    }

    @Test
    void acceptsNumericDefaultOnFloatField() {
        // float32 出的是任意有限数值，1.5 在这里是合法的（与 int16 那条正好相对）
        ModbusDevice decimal = writeService(ModbusRequests.write("10", 0,
                ModbusRequests.field(1, "设定温度", 0, "float32", "ABCD", 7.5)));
        assertNull(ModbusDeviceValidator.validate(decimal));
    }

    // ===== request ↔ response 对账 =====

    @Test
    void rejectsResponseNotMatchingTheReadQuantity() {
        // 读 1 个寄存器 = 2 字节
        ModbusDevice wide = service();
        wide.polls.get(0).response.fields.get(0).format = "uint32";
        wide.polls.get(0).response.fields.get(0).bytes = 4;
        assertEquals("invalid polls[0].response: declares 4 bytes, but fc 03 with quantity 1 reads 2",
                ModbusDeviceValidator.validate(wide));

        ModbusDevice narrow = service();
        narrow.polls.get(0).request = ModbusRequests.read("03", 0, 2);
        assertEquals("invalid polls[0].response: declares 2 bytes, but fc 03 with quantity 2 reads 4",
                ModbusDeviceValidator.validate(narrow));
    }

    @Test
    void rejectsMissingResponseOnReadFunction() {
        // 没有解析规则的读方法返回 {}：调用方看到的是「成功但没有值」，是静默失效
        ModbusDevice noResponse = service();
        noResponse.polls.get(0).response = null;
        assertEquals("polls[0].response is required for a read function (a method with no field returns {})",
                ModbusDeviceValidator.validate(noResponse));

        ModbusDevice emptyResponse = service();
        emptyResponse.polls.get(0).response = ModbusResponses.of();
        assertEquals("polls[0].response is required for a read function (a method with no field returns {})",
                ModbusDeviceValidator.validate(emptyResponse));
    }

    @Test
    void rejectsResponseOnWriteFunction() {
        // 写应答是请求回显，没有「读到的值」可解析
        ModbusDevice echoed = writeService(singleRegister());
        echoed.polls.get(0).response = ModbusResponses.of(field("进水温度", "uint16", 2));
        assertEquals("invalid polls[0].response: not allowed on a write function (the reply echoes the request)",
                ModbusDeviceValidator.validate(echoed));
    }

    @Test
    void rejectsInconsistentSlaveId() {
        // 一个服务由一条点表映射而来，一条点表只有一个 slave：写错不会报错，只会静默读到隔壁设备
        ModbusDevice mixed = service();
        mixed.polls.get(1).request.slaveId = 2;
        assertEquals("invalid polls[1].request.slaveId: 2 (must equal service slaveId 1)",
                ModbusDeviceValidator.validate(mixed));
    }

    // ===== interval =====

    @Test
    void acceptsIntervalOnReadFunctions() {
        ModbusDevice polled = service();
        polled.polls.get(0).interval = 5;
        assertNull(ModbusDeviceValidator.validate(polled));
    }

    @Test
    void acceptsIntervalBounds() {
        // 上下限本身是合法的（越界才拒）
        ModbusDevice fastest = service();
        fastest.polls.get(0).interval = 5;
        assertNull(ModbusDeviceValidator.validate(fastest));

        ModbusDevice slowest = service();
        slowest.polls.get(0).interval = 3600;
        assertNull(ModbusDeviceValidator.validate(slowest));
    }

    @Test
    void rejectsIntervalOnWriteFunctions() {
        // 写方法的应答只是请求回显，周期调用等于让服务端周期性往寄存器里写值
        ModbusDevice written = writeService(singleRegister());
        written.polls.get(0).interval = 5;
        assertEquals("invalid polls[0].interval: 5 (only read functions (fc 01/02/03/04) can be polled)",
                ModbusDeviceValidator.validate(written));
    }

    @Test
    void rejectsIntervalOutOfRange() {
        // 4 秒是贴近新下限的那个负例：改下限时最容易漏的就是「边界内侧一格」
        ModbusDevice belowMin = service();
        belowMin.polls.get(0).interval = 4;
        assertEquals("invalid polls[0].interval: 4 (interval must be between 5 and 3600 seconds)",
                ModbusDeviceValidator.validate(belowMin));

        ModbusDevice tooFrequent = service();
        tooFrequent.polls.get(0).interval = 2;
        assertEquals("invalid polls[0].interval: 2 (interval must be between 5 and 3600 seconds)",
                ModbusDeviceValidator.validate(tooFrequent));

        ModbusDevice tooRare = service();
        tooRare.polls.get(0).interval = 3601;
        assertEquals("invalid polls[0].interval: 3601 (interval must be between 5 and 3600 seconds)",
                ModbusDeviceValidator.validate(tooRare));
    }

    // ===== polling =====

    @Test
    void acceptsPollingOnReadFunctions() {
        // 显式开轮询：有周期，读方法
        ModbusDevice polling = service();
        polling.polls.get(0).interval = 5;
        polling.polls.get(0).polling = true;
        assertNull(ModbusDeviceValidator.validate(polling));

        // 显式关轮询：周期留着不跑（暂停），一样合法
        ModbusDevice paused = service();
        paused.polls.get(0).interval = 5;
        paused.polls.get(0).polling = false;
        assertNull(ModbusDeviceValidator.validate(paused));

        // 缺省 = 按 interval 判定：老数据（只有周期、没这个字段）照旧算「启用」
        ModbusDevice legacy = service();
        legacy.polls.get(0).interval = 5;
        assertNull(ModbusDeviceValidator.validate(legacy));
    }

    @Test
    void rejectsPollingWithoutInterval() {
        // 开了轮询却没有周期：服务端无从知道多久调一次，自相矛盾
        ModbusDevice noInterval = service();
        noInterval.polls.get(0).polling = true;
        assertEquals("invalid polls[0].polling: true (polling requires an interval)",
                ModbusDeviceValidator.validate(noInterval));
    }

    @Test
    void rejectsPollingOnWriteFunctions() {
        // 写方法开轮询：显式拒。以前是间接成立的（polling=true 要求 interval，而 interval 不允许
        // 配在写方法上），绕了一层，读代码的人看不出「写方法一律不许轮询」这条规矩。
        ModbusDevice written = writeService(singleRegister());
        written.polls.get(0).polling = true;
        assertEquals("invalid polls[0].polling: true (only read functions (fc 01/02/03/04) can be polled)",
                ModbusDeviceValidator.validate(written));

        // 写方法上带周期又开轮询：先撞的还是轮询这条（它在周期之前）
        written.polls.get(0).interval = 5;
        assertEquals("invalid polls[0].polling: true (only read functions (fc 01/02/03/04) can be polled)",
                ModbusDeviceValidator.validate(written));

        // 光有周期、没开轮询：一样不许（周期本身在写方法上就无效）
        ModbusDevice intervalOnly = writeService(singleRegister());
        intervalOnly.polls.get(0).interval = 5;
        assertEquals("invalid polls[0].interval: 5 (only read functions (fc 01/02/03/04) can be polled)",
                ModbusDeviceValidator.validate(intervalOnly));
    }

    // ===== response =====

    @Test
    void rejectsFormatWidthMismatch() {
        ModbusDevice mismatched = service();
        ModbusResponseField field = mismatched.polls.get(0).response.fields.get(0);
        field.format = "int8";     // int8 只占 1 字节
        assertEquals("invalid polls[0].response.fields[0].bytes: 2 (format int8 needs 1)",
                ModbusDeviceValidator.validate(mismatched));
    }

    @Test
    void rejectsUnknownFormatAndByteOrder() {
        ModbusDevice badFormat = service();
        badFormat.polls.get(0).response.fields.get(0).format = "uint64";
        assertEquals("invalid polls[0].response.fields[0].format: uint64",
                ModbusDeviceValidator.validate(badFormat));

        ModbusDevice badOrder = service();
        badOrder.polls.get(0).response.fields.get(0).byteOrder = "ABCDEFGH";
        assertEquals("invalid polls[0].response.fields[0].byteOrder: ABCDEFGH",
                ModbusDeviceValidator.validate(badOrder));

        ModbusDevice noOrder = service();
        noOrder.polls.get(0).response.fields.get(0).byteOrder = null;
        assertEquals("polls[0].response.fields[0].byteOrder is required",
                ModbusDeviceValidator.validate(noOrder));
    }

    @Test
    void stringFormatTakesAnyWidth() {
        ModbusDevice text = service();
        text.polls.get(0).request = ModbusRequests.read("03", 0, 4);   // 8 字节 = 4 个寄存器
        ModbusResponseField field = text.polls.get(0).response.fields.get(0);
        field.format = "string";
        field.bytes = 8;           // string 宽度不固定，由 bytes 决定，不做宽度比对
        assertNull(ModbusDeviceValidator.validate(text));
    }

    @Test
    void singleByteNeedsNoByteOrder() {
        ModbusDevice oneByte = service();
        oneByte.polls.get(0).request = ModbusRequests.read("02", 0, 8);   // 1 字节 = 8 个位
        ModbusResponseField flag = oneByte.polls.get(0).response.fields.get(0);
        flag.format = "uint8";
        flag.bytes = 1;
        flag.byteOrder = null;     // 单字节没有字节序可言
        assertNull(ModbusDeviceValidator.validate(oneByte));
    }

    @Test
    void byteOrderIsRequiredForEveryMultiByteField() {
        // 现行规则是「bytes > 1 就必填」，string 也不例外（虽然 string 的取值并不用 byteOrder）
        ModbusDevice text = service();
        ModbusResponseField field = text.polls.get(0).response.fields.get(0);
        field.format = "string";
        field.bytes = 8;
        field.byteOrder = null;
        assertEquals("polls[0].response.fields[0].byteOrder is required",
                ModbusDeviceValidator.validate(text));
    }

    @Test
    void rejectsEmptyOrDuplicatedValueList() {
        ModbusDevice noDescription = service();
        noDescription.polls.get(0).response.fields.get(0).valueList = List.of(value(0L, " "));
        assertEquals("polls[0].response.fields[0].value-list[0].description is required",
                ModbusDeviceValidator.validate(noDescription));

        ModbusDevice duplicated = service();
        duplicated.polls.get(0).response.fields.get(0).valueList = List.of(value(0L, "ON"), value(0L, "OFF"));
        assertEquals("invalid polls[0].response.fields[0].value-list[1].value: 0 (duplicated)",
                ModbusDeviceValidator.validate(duplicated));
    }

    /**
     * 同一应答内所有出值名必须唯一：parser 把它们放进同一个结果对象，重名会互相覆盖。
     */
    @Test
    void rejectsDuplicatedFieldNames() {
        ModbusDevice duplicated = service();
        ModbusResponseField second = poll(1).response.fields.getFirst(); // 序号不同、名字相同的第二个字段
        second.index = 2;
        duplicated.polls.getFirst().response.fields.add(second);
        assertEquals("invalid polls[0].response.fields[1].field: 进水温度 (duplicated)",
                ModbusDeviceValidator.validate(duplicated));
    }

    @Test
    void rejectsBitListOffsetBeyondField() {
        ModbusDevice beyond = service();
        ModbusResponseField field = beyond.polls.get(0).response.fields.get(0);
        field.bitList = new ArrayList<>(List.of(bit(16, "第17位"))); // bytes 2 → 只有 0..15
        assertEquals("invalid polls[0].response.fields[0].bit-list[0].offset: 16 (field covers 16 bits)",
                ModbusDeviceValidator.validate(beyond));
    }

    @Test
    void rejectsDuplicatedBitAndFieldNames() {
        ModbusDevice duplicatedOffset = service();
        ModbusResponseField field = duplicatedOffset.polls.get(0).response.fields.get(0);
        field.bitList = new ArrayList<>(List.of(bit(0, "第1位"), bit(0, "第2位")));
        assertEquals("invalid polls[0].response.fields[0].bit-list[1].offset: 0 (duplicated)",
                ModbusDeviceValidator.validate(duplicatedOffset));

        // 位名与所在字段重名同样会互相覆盖
        ModbusDevice shadowed = service();
        ModbusResponseField owner = shadowed.polls.get(0).response.fields.get(0);
        owner.bitList = new ArrayList<>(List.of(bit(0, "进水温度")));
        assertEquals("invalid polls[0].response.fields[0].bit-list[0].field: 进水温度 (duplicated)",
                ModbusDeviceValidator.validate(shadowed));
    }

    @Test
    void acceptsBitListWithinField() {
        ModbusDevice bits = service();
        ModbusResponseField field = bits.polls.get(0).response.fields.get(0);
        field.bitList = new ArrayList<>(List.of(bit(0, "进水阀"), bit(15, "报警")));

        assertNull(ModbusDeviceValidator.validate(bits));
    }

    // ===== 告警配置 =====

    @Test
    void acceptsAlarms() {
        // 数值寄存器：超过 80 报「温度过高」
        assertNull(validateAlarm(alarm(">", 80.0, null), holding("进水温度", "uint16")));
        // 枚举寄存器：等于「ON」报「水泵异常」（比的是显示名，不是原始值）
        ModbusRegistry named = holding("水泵状态", "uint16");
        named.enumMap = Map.of("0", "OFF", "1", "ON");
        assertNull(validateAlarm(alarm("=", null, "ON"), named));
        // 数值寄存器用「等于」比数
        assertNull(validateAlarm(alarm("=", 1.0, null), holding("进水温度", "uint16")));
    }

    @Test
    void acceptsARuleGroupOnOneField() {
        // 一个出值一组规则：阈值各异、甚至方向相反（低侧与高侧永远不可能同时命中）都合法
        ModbusRegistryAlarm low = alarm("r1", "<", 20.0, null);
        low.level = "WARN";
        ModbusRegistryAlarm high = alarm("r2", ">", 26.0, null);
        high.level = "INFO";
        ModbusRegistryAlarm worst = alarm("r3", ">", 30.0, null);
        worst.level = "CRITICAL";
        assertNull(validateAlarms(List.of(low, high, worst), holding("进水温度", "uint16")));

        // 停用的规则不查 id（半配置合法），也不占用组内的 id 名额
        ModbusRegistryAlarm disabled = alarm(null, ">", 40.0, null);
        disabled.enabled = false;
        assertNull(validateAlarms(List.of(low, disabled), holding("进水温度", "uint16")));
    }

    @Test
    void rejectsBadAlarmIds() {
        String base = "configSnapshot.registries[0].alarms";
        ModbusRegistry temperature = holding("进水温度", "uint16");

        // id 是「这条开着的告警是哪条规则开的」的唯一依据：启用了就必须有
        assertEquals(base + "[0].id is required",
                validateAlarms(List.of(alarm(null, ">", 80.0, null)), temperature));
        assertEquals(base + "[0].id is required",
                validateAlarms(List.of(alarm("  ", ">", 80.0, null)), temperature));

        ModbusRegistryAlarm longId = alarm("x".repeat(65), ">", 80.0, null);
        assertEquals("invalid " + base + "[0].id: too long (64 characters max)",
                validateAlarms(List.of(longId), temperature));

        // 同一出值内重复：两条规则会争同一个 ruleId，开行的归属就说不清了
        assertEquals("invalid " + base + "[1].id: r1 (duplicated)",
                validateAlarms(List.of(alarm("r1", ">", 80.0, null), alarm("r1", ">", 90.0, null)),
                        temperature));
    }

    @Test
    void rejectsTooManyRulesOnOneField() {
        // 防呆不是安全边界（多几条只是多几次纯内存比较），是给「界面上误加了一堆规则」一个上限
        String base = "configSnapshot.registries[0].alarms";
        ModbusRegistry temperature = holding("进水温度", "uint16");

        List<ModbusRegistryAlarm> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(alarm("r" + i, ">", 80.0 + i, null));
        }
        assertNull(validateAlarms(eight, temperature));

        List<ModbusRegistryAlarm> nine = new ArrayList<>(eight);
        nine.add(alarm("r8", ">", 90.0, null));
        assertEquals("invalid " + base + ": too many rules (8 max)",
                validateAlarms(nine, temperature));
    }

    @Test
    void acceptsBitAlarm() {
        // 线圈寄存器只有 0/1，只能用「等于」比 0 或 1：进水阀 = 1 告警
        assertNull(validateAlarm(alarm("=", 1.0, null), coil("进水阀")));
    }

    @Test
    void ignoresDisabledAlarm() {
        // enabled != true：半配置合法，与 polling 同一条规矩 —— 留着配置、先不报
        ModbusRegistryAlarm disabled = alarm("??", null, null);
        disabled.enabled = false;
        disabled.text = null;
        disabled.level = null;
        assertNull(validateAlarm(disabled, holding("进水温度", "uint16")));

        ModbusRegistryAlarm absent = alarm("??", null, null);
        absent.enabled = null;
        assertNull(validateAlarm(absent, holding("进水温度", "uint16")));
    }

    @Test
    void rejectsBadAlarmScalars() {
        String base = "configSnapshot.registries[0].alarms[0]";

        ModbusRegistryAlarm badCompare = alarm("!=", 80.0, null);
        assertEquals("invalid " + base + ".compare: != (must be one of > >= < <= =)",
                validateAlarm(badCompare, holding("进水温度", "uint16")));

        ModbusRegistryAlarm noCompare = alarm(null, 80.0, null);
        assertEquals("invalid " + base + ".compare: null (must be one of > >= < <= =)",
                validateAlarm(noCompare, holding("进水温度", "uint16")));

        ModbusRegistryAlarm badLevel = alarm(">", 80.0, null);
        badLevel.level = "FATAL";
        assertEquals("invalid " + base + ".level: FATAL (must be one of INFO WARN CRITICAL)",
                validateAlarm(badLevel, holding("进水温度", "uint16")));

        ModbusRegistryAlarm blankText = alarm(">", 80.0, null);
        blankText.text = "  ";
        assertEquals(base + ".text is required",
                validateAlarm(blankText, holding("进水温度", "uint16")));

        ModbusRegistryAlarm longText = alarm(">", 80.0, null);
        longText.text = "超".repeat(65);
        assertEquals("invalid " + base + ".text: too long (64 characters max)",
                validateAlarm(longText, holding("进水温度", "uint16")));

        ModbusRegistryAlarm noThreshold = alarm(">", null, null);
        assertEquals(base + ".threshold is required (compare >)",
                validateAlarm(noThreshold, holding("进水温度", "uint16")));

        ModbusRegistryAlarm nan = alarm(">", Double.NaN, null);
        assertEquals("invalid " + base + ".threshold: NaN",
                validateAlarm(nan, holding("进水温度", "uint16")));
    }

    @Test
    void rejectsNullRuleInAGroup() {
        // 数组里的空元素：要么是前端拼错了，要么是手工改库改出来的，收下来判定时会 NPE
        assertEquals("configSnapshot.registries[0].alarms[1] is null",
                validateAlarms(Arrays.asList(alarm(">", 80.0, null), null),
                        holding("进水温度", "uint16")));
    }

    @Test
    void rejectsAlarmOnWriteOnlyRegister() {
        // 写方法没有「读到的值」可言 —— 与 interval 同一条规则。
        // 寄存器中心里等价于一条 access=W（只写不可读）的寄存器：读不到值，不收告警。
        ModbusRegistry writeReg = holding("进水温度", "uint16");
        writeReg.access = "W";

        assertEquals("invalid configSnapshot.registries[0].alarms[0]: only read functions (fc 01/02/03/04) can have alarms",
                validateAlarm(alarm(">", 80.0, null), writeReg));
    }

    @Test
    void rejectsAlarmOnStringRegister() {
        // string 寄存器出的是任意文本，没有可比的量
        ModbusRegistryAlarm a = alarm(">", 80.0, null);
        assertEquals("invalid configSnapshot.registries[0].alarms[0]: a string field has no comparable value",
                validateAlarm(a, holding("设备型号", "string")));
    }

    @Test
    void rejectsNumericCompareOnEnumRegister() {
        // 带 enumMap 的寄存器出的是显示名：配 > 会随原始值是否命中枚举映射而时灵时不灵
        ModbusRegistry named = holding("水泵状态", "uint16");
        named.enumMap = Map.of("0", "OFF", "1", "ON");

        assertEquals("invalid configSnapshot.registries[0].alarms[0].compare: > (a value-list field yields names, use =)",
                validateAlarm(alarm(">", 80.0, null), named));
    }

    @Test
    void rejectsBadEqualsOnEnumRegister() {
        String base = "configSnapshot.registries[0].alarms[0]";
        ModbusRegistry named = holding("水泵状态", "uint16");
        named.enumMap = Map.of("0", "OFF", "1", "ON");

        assertEquals(base + ".state is required (compare = on a value-list field)",
                validateAlarm(alarm("=", null, null), named));

        // 不在枚举映射里的显示名：判定时永远比不中，是静默失效，直接拒
        assertEquals("invalid " + base + ".state: RUNAWAY (not in this field's value-list)",
                validateAlarm(alarm("=", null, "RUNAWAY"), named));

        // 枚举寄存器比数也不对：那种寄存器的值有时是字符串
        assertEquals("invalid " + base + ".threshold: not allowed on a value-list field (use state)",
                validateAlarm(alarm("=", 1.0, "ON"), named));
    }

    @Test
    void rejectsBothThresholdAndState() {
        assertEquals("invalid configSnapshot.registries[0].alarms[0].state: only allowed on a value-list field (use threshold)",
                validateAlarm(alarm("=", 1.0, "ON"), holding("进水温度", "uint16")));
        assertEquals("invalid configSnapshot.registries[0].alarms[0].state: only allowed when compare is =",
                validateAlarm(alarm(">", 80.0, "ON"), holding("进水温度", "uint16")));
        assertEquals("configSnapshot.registries[0].alarms[0].threshold is required (compare =)",
                validateAlarm(alarm("=", null, null), holding("进水温度", "uint16")));
    }

    @Test
    void rejectsBadBitAlarm() {
        String base = "configSnapshot.registries[0].alarms[0]";

        // 线圈寄存器只有 0/1：数值比较与 0.5 都是定义错误
        assertEquals("invalid " + base + ".compare: > (a bit is 0 or 1, use =)",
                validateAlarm(alarm(">", 1.0, null), coil("进水阀")));
        assertEquals("invalid " + base + ".threshold: 0.5 (a bit is 0 or 1, use =)",
                validateAlarm(alarm("=", 0.5, null), coil("进水阀")));
    }

    @Test
    void bitRuleGroupHasItsOwnIdSpace() {
        // id 的唯一性只在「同一位（同一寄存器）」内要求：位与父字段各是一组、各开各的行，同名不冲突
        assertNull(validateAlarms(List.of(alarm("r1", "=", 1.0, null)), coil("进水阀")));

        // 位组内自己重复，照拒
        assertEquals("invalid configSnapshot.registries[0].alarms[1].id: r1 (duplicated)",
                validateAlarms(List.of(alarm("r1", "=", 1.0, null), alarm("r1", "=", 0.0, null)),
                        coil("进水阀")));
    }

    // ===== invoke 入参 =====

    @Test
    void acceptsInvokeWithEveryFieldDefaulted() {
        ModbusPoll write = writeFunction(singleRegister());
        assertNull(ModbusDeviceValidator.validateInvoke(write, null));
        assertNull(ModbusDeviceValidator.validateInvoke(write, Map.of()));
    }

    @Test
    void invokeRejectsValuesOnReadFunctions() {
        // 静默忽略一个用户以为生效了的载荷，比报错糟得多
        assertEquals("invalid values: a read function (fc 03) takes no values",
                ModbusDeviceValidator.validateInvoke(poll(1), Map.of("进水温度", 1)));
        // 没给 values 的读方法照旧
        assertNull(ModbusDeviceValidator.validateInvoke(poll(1), null));
    }

    @Test
    void invokeRequiresFieldsWithoutDefault() {
        ModbusPoll write = writeFunction(ModbusRequests.write("10", 0,
                ModbusRequests.field(1, "设定温度", 0, "uint16", null)));
        assertEquals("values.设定温度 is required (this field has no default)",
                ModbusDeviceValidator.validateInvoke(write, null));
        assertNull(ModbusDeviceValidator.validateInvoke(write, Map.of("设定温度", 70)));
    }

    @Test
    void invokeRejectsUnknownFieldName() {
        ModbusPoll write = writeFunction(singleRegister());
        assertEquals("invalid values.运行模式: not a field of this function",
                ModbusDeviceValidator.validateInvoke(write, Map.of("运行模式", 1)));
    }

    @Test
    void invokeChecksValueTypeAndRange() {
        ModbusPoll write = writeFunction(singleRegister());
        assertEquals("invalid values.设定温度: 70000 (out of range for uint16)",
                ModbusDeviceValidator.validateInvoke(write, Map.of("设定温度", 70000)));
        assertEquals("invalid values.设定温度: 70.5 (format uint16 needs an integer)",
                ModbusDeviceValidator.validateInvoke(write, Map.of("设定温度", 70.5)));
        assertEquals("invalid values.设定温度: abc (a register value is a finite number)",
                ModbusDeviceValidator.validateInvoke(write, Map.of("设定温度", "abc")));

        // 请求体过 Jackson 之后 70 可能是 Double：按值判整数，不按 Java 类型判
        assertNull(ModbusDeviceValidator.validateInvoke(write, Map.of("设定温度", 70.0)));
    }

    @Test
    void invokeChecksBitValues() {
        ModbusPoll write = writeFunction(ModbusRequests.write("05", 0,
                ModbusRequests.field(1, "开机", "bit", true)));
        assertEquals("invalid values.开机: 1 (a bit is true or false)",
                ModbusDeviceValidator.validateInvoke(write, Map.of("开机", 1)));
        assertNull(ModbusDeviceValidator.validateInvoke(write, Map.of("开机", false)));
    }

    // ===== 夹具 =====

    /**
     * 一份各处都合法的定义：各用例只改自己关心的那一处。
     */
    private static ModbusDevice service() {
        ModbusDevice service = new ModbusDevice();
        service.name = "1 号冷水机组";
        service.version = ModbusDevice.VERSION;
        service.configSnapshot = configSnapshot();
        service.slaveId = 1;
        service.device = device();
        service.polls = new ArrayList<>(List.of(poll(1), poll(2)));
        return service;
    }

    /**
     * 源点表快照的最小形状：快照 id 是校验要求的（溯源与统计分组键）。
     */
    private static ModbusConfig configSnapshot() {
        ModbusConfig config = new ModbusConfig();
        config.id = new ObjectId("65f0c0ffee0123456789abcd");
        config.slave = new ModbusSlave();
        config.slave.manufacturer = "特灵";
        config.slave.model = "19XRV";
        return config;
    }

    /**
     * 一份只含给定写方法的定义（写方法没有 response，也没有 interval / polling）。
     */
    private static ModbusDevice writeService(ModbusRequest request) {
        ModbusDevice service = new ModbusDevice();
        service.name = "1 号冷水机组";
        service.version = ModbusDevice.VERSION;
        service.configSnapshot = configSnapshot();
        service.slaveId = 1;
        service.device = device();

        ModbusPoll write = new ModbusPoll();
        write.index = 1;      // 方法身份（type.index 只是溯源）
        write.from = new ModbusPollFrom();
        write.from.type = "command";
        write.from.index = 1;
        write.name = "写设定温度";
        write.request = request;
        service.polls = new ArrayList<>(List.of(write));
        return service;
    }

    private static ModbusPoll poll(int index) {
        ModbusPoll poll = new ModbusPoll();
        poll.index = index;   // 方法身份（type.index 只是溯源）
        poll.from = new ModbusPollFrom();
        poll.from.type = "command";
        poll.from.index = index;
        poll.name = "读进水温度";
        poll.request = ModbusRequests.holding();

        ModbusResponseField field = new ModbusResponseField();
        field.index = 1;
        field.field = "进水温度";
        field.bytes = 2;
        field.format = "uint16";
        field.byteOrder = "ABCD";
        poll.response = ModbusResponses.of(field);
        return poll;
    }

    /**
     * 依赖设备的调用坐标：各用例只关心自己那一处，这里给一份各处都合法的。
     */
    private static DeviceOn device() {
        DeviceOn device = new DeviceOn();
        device.did = "did-1";
        device.siid = 1;
        device.aiid = 1;
        device.argument = 1;
        return device;
    }

    /**
     * 最常用的那条写请求：fc 06 写一个寄存器，带缺省值。
     */
    private static ModbusRequest singleRegister() {
        return ModbusRequests.write("06", 0,
                ModbusRequests.field(1, "设定温度", "uint16", 70));
    }

    private static ModbusPoll writeFunction(ModbusRequest request) {
        return writeService(request).polls.get(0);
    }

    private static ModbusResponseFieldValue value(Long value, String description) {
        ModbusResponseFieldValue entry = new ModbusResponseFieldValue();
        entry.value = value;
        entry.description = description;
        return entry;
    }

    private static ModbusResponseFieldBit bit(int offset, String field) {
        ModbusResponseFieldBit entry = new ModbusResponseFieldBit();
        entry.offset = offset;
        entry.field = field;
        return entry;
    }

    /**
     * 一份「启用之后应当自洽」的规则：各用例只改自己关心的那一处。{@code id} 默认 {@code r1}。
     */
    private static ModbusRegistryAlarm alarm(String compare, Double threshold, String state) {
        return alarm("r1", compare, threshold, state);
    }

    /**
     * 同上，但规则身份自己给（{@code null} 用来测「启用了却没有 id」）。
     */
    private static ModbusRegistryAlarm alarm(String id, String compare, Double threshold, String state) {
        ModbusRegistryAlarm alarm = new ModbusRegistryAlarm();
        alarm.id = id;
        alarm.enabled = true;
        alarm.compare = compare;
        alarm.threshold = threshold;
        alarm.state = state;
        alarm.level = "WARN";
        alarm.text = "温度过高";
        return alarm;
    }

    private static ModbusResponseField field(String name, String format, Integer bytes) {
        ModbusResponseField field = new ModbusResponseField();
        field.index = 1;
        field.field = name;
        field.bytes = bytes;
        field.format = format;
        if (bytes != null && bytes > 1) {
            field.byteOrder = "ABCD";
        }
        return field;
    }

    // ===== 告警规则：属寄存器（寄存器中心），不属 function 应答字段 =====

    /**
     * 一个可读数值寄存器（holding，access R）。
     */
    private static ModbusRegistry holding(String name, String dataType) {
        ModbusRegistry reg = new ModbusRegistry();
        reg.name = name;
        reg.area = "holding";
        reg.dataType = dataType;
        reg.access = "R";
        return reg;
    }

    /**
     * 一个可读线圈寄存器（coil，值恒 0/1）。
     */
    private static ModbusRegistry coil(String name) {
        ModbusRegistry reg = new ModbusRegistry();
        reg.name = name;
        reg.area = "coil";
        reg.access = "R";
        return reg;
    }

    /**
     * 校验一条挂在某寄存器上的规则（返回错误文案或 null）。
     */
    private static String validateAlarm(ModbusRegistryAlarm alarm, ModbusRegistry reg) {
        return validateAlarmOn(alarm, reg, new ArrayList<>(List.of(alarm)));
    }

    /**
     * 校验挂在同一寄存器上的一组规则（返回错误文案或 null）。
     */
    private static String validateAlarms(List<ModbusRegistryAlarm> alarms, ModbusRegistry reg) {
        return validateAlarmOn(null, reg, alarms);
    }

    private static String validateAlarmOn(ModbusRegistryAlarm alarm, ModbusRegistry reg,
                                          List<ModbusRegistryAlarm> alarms) {
        return ModbusDeviceValidator.validateAlarms(alarms == null ? List.of(alarm) : alarms, reg,
                "configSnapshot.registries[0].alarms");
    }
}
