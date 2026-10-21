package cc.openxiot.matrix.api.modbus.service.validator;

import cc.openxiot.matrix.db.modbus.service.polling.ModbusPollingFrom;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseField;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseFieldAlarm;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseFieldBit;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseFieldValue;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequest;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequests;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.service.polling.response.ModbusPollingResponses;
import cc.openxiot.matrix.db.modbus.service.DeviceOn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
        noIndex.functions.get(0).from = null;
        assertEquals("functions[0].from is required", ModbusServiceValidator.validate(noIndex));

        ModbusService duplicated = service();
        duplicated.functions.get(1).index = 1;
        assertEquals("invalid functions[1].index: 1 (duplicated)", ModbusServiceValidator.validate(duplicated));
    }

    @Test
    void rejectsOldVersion() {
        // version=1 的定义里 request 是一整串帧字符串：读成结构化对象只会得到一堆 null，
        // 然后报一句和病因无关的「request.slaveId is required」。旧口径必须响亮地失败。
        ModbusService old = service();
        old.version = 1;
        assertEquals("invalid version: 1 (this server serves version " + ModbusService.VERSION + ")",
                ModbusServiceValidator.validate(old));
    }

    // ===== request：公共字段 =====

    @Test
    void rejectsMissingRequestScalars() {
        ModbusService noRequest = service();
        noRequest.functions.get(0).request = null;
        assertEquals("functions[0].request is required", ModbusServiceValidator.validate(noRequest));

        ModbusService noSlave = service();
        noSlave.functions.get(0).request.slaveId = null;
        assertEquals("functions[0].request.slaveId is required", ModbusServiceValidator.validate(noSlave));

        ModbusService noFc = service();
        noFc.functions.get(0).request.fc = " ";
        assertEquals("functions[0].request.fc is required", ModbusServiceValidator.validate(noFc));

        ModbusService noStart = service();
        noStart.functions.get(0).request.start = null;
        assertEquals("functions[0].request.start is required", ModbusServiceValidator.validate(noStart));
    }

    @Test
    void rejectsOutOfRangeRequestScalars() {
        ModbusService badSlave = service();
        badSlave.functions.get(0).request.slaveId = 248;
        assertEquals("invalid functions[0].request.slaveId: 248 (must be 1..247)",
                ModbusServiceValidator.validate(badSlave));

        ModbusService zeroSlave = service();
        zeroSlave.functions.get(0).request.slaveId = 0;
        assertEquals("invalid functions[0].request.slaveId: 0 (must be 1..247)",
                ModbusServiceValidator.validate(zeroSlave));

        ModbusService badStart = service();
        badStart.functions.get(0).request.start = 65536;
        assertEquals("invalid functions[0].request.start: 65536 (must be 0..65535)",
                ModbusServiceValidator.validate(badStart));
    }

    @Test
    void rejectsUnknownFunctionCode() {
        // 结构化的第一个好处：fc 以前完全不校验（整帧只查「是合法 16 进制且够长」）
        ModbusService badFc = service();
        badFc.functions.get(0).request.fc = "07";
        assertEquals("invalid functions[0].request.fc: 07 (must be one of 01 02 03 04 05 06 0F 10)",
                ModbusServiceValidator.validate(badFc));
    }

    @Test
    void acceptsMixedCaseFunctionCode() {
        ModbusService lower = service();
        lower.functions.get(0).request.fc = "0f";
        lower.functions.get(0).request.quantity = null;
        lower.functions.get(0).request.fields = new ArrayList<>(List.of(
                ModbusPollingRequests.field(1, "开机", 0, "bit", true)));
        lower.functions.get(0).response = null;
        // 第二个方法还是读方法，从站一致，故此处的 fc 大小写不敏感是唯一被考的东西
        assertNull(ModbusServiceValidator.validate(lower));
    }

    // ===== request：读 =====

    @Test
    void rejectsBadReadQuantity() {
        ModbusService noQuantity = service();
        noQuantity.functions.get(0).request.quantity = null;
        assertEquals("functions[0].request.quantity is required", ModbusServiceValidator.validate(noQuantity));

        ModbusService zero = service();
        zero.functions.get(0).request.quantity = 0;
        assertEquals("invalid functions[0].request.quantity: 0 (must be 1..125 registers)",
                ModbusServiceValidator.validate(zero));

        // 126 是寄存器那条线的边界外一格：上限本身的用例在下面（要连应答一起给）
        ModbusService tooMany = service();
        tooMany.functions.get(0).request.quantity = 126;
        assertEquals("invalid functions[0].request.quantity: 126 (must be 1..125 registers)",
                ModbusServiceValidator.validate(tooMany));
    }

    @Test
    void readBitsUseTheBitsBound() {
        // 01/02 的 quantity 是位数，上限 2000 而不是 125
        ModbusService tooManyBits = service();
        tooManyBits.functions.get(0).request = ModbusPollingRequests.read("02", 0, 2001);
        assertEquals("invalid functions[0].request.quantity: 2001 (must be 1..2000 bits)",
                ModbusServiceValidator.validate(tooManyBits));
    }

    @Test
    void acceptsBitRead() {
        // 读 16 个位 = 2 字节，正好是一个 uint16 字段
        ModbusService bits = service();
        bits.functions.get(0).request = ModbusPollingRequests.read("02", 0, 16);
        assertNull(ModbusServiceValidator.validate(bits));
    }

    @Test
    void rejectsFieldsOnReadFunctions() {
        ModbusService readWithFields = service();
        readWithFields.functions.get(0).request.fields = new ArrayList<>(List.of(
                ModbusPollingRequests.field(1, "设定温度", "uint16", 70)));
        assertEquals("invalid functions[0].request.fields: not allowed on a read function (fc 03)",
                ModbusServiceValidator.validate(readWithFields));
    }

    // ===== request：写 =====

    @Test
    void rejectsQuantityOnWriteFunctions() {
        // 写帧里的数量由字段推出，不给配置的人再写一遍的机会（两处不一致时以谁为准是个坑）
        ModbusService written = writeService(singleRegister());
        written.functions.get(0).request.quantity = 1;
        assertEquals("invalid functions[0].request.quantity: 1 (not allowed on a write function (fc 06), "
                        + "the frame's count comes from fields)",
                ModbusServiceValidator.validate(written));
    }

    @Test
    void rejectsWriteWithoutFields() {
        ModbusService noFields = writeService(ModbusPollingRequests.write("06", 0));
        assertEquals("functions[0].request.fields is required (a write function needs the fields it writes)",
                ModbusServiceValidator.validate(noFields));

        ModbusService empty = writeService(ModbusPollingRequests.write("10", 0));
        empty.functions.get(0).request.fields = new ArrayList<>();
        assertEquals("functions[0].request.fields is required (a write function needs the fields it writes)",
                ModbusServiceValidator.validate(empty));
    }

    @Test
    void rejectsManyFieldsOnSingleValueFunctions() {
        // fc 06 的帧里只有一个值，「第几个」无从谈起
        ModbusService twoFields = writeService(ModbusPollingRequests.write("06", 0,
                ModbusPollingRequests.field(1, "设定温度", "uint16", 70),
                ModbusPollingRequests.field(2, "运行模式", "uint16", 1)));
        assertEquals("invalid functions[0].request.fields: fc 06 writes exactly one field, got 2",
                ModbusServiceValidator.validate(twoFields));
    }

    @Test
    void rejectsOffsetOnSingleValueFunctions() {
        ModbusService offset = writeService(ModbusPollingRequests.write("05", 0,
                ModbusPollingRequests.field(1, "开机", 0, "bit", true)));
        assertEquals("invalid functions[0].request.fields[0].offset: 0 (fc 05 writes one value)",
                ModbusServiceValidator.validate(offset));
    }

    @Test
    void rejectsWriteFormatOutsideTheFunctionCode() {
        // 05/0F 是位区
        ModbusService register = writeService(ModbusPollingRequests.write("05", 0,
                ModbusPollingRequests.field(1, "开机", "uint16", 1)));
        assertEquals("invalid functions[0].request.fields[0].format: uint16 (fc 05 writes bit)",
                ModbusServiceValidator.validate(register));

        // 06 只写一个寄存器：32 位格式会被静默截掉一半
        ModbusService wide = writeService(ModbusPollingRequests.write("06", 0,
                ModbusPollingRequests.field(1, "设定温度", "float32", 70.0)));
        assertEquals("invalid functions[0].request.fields[0].format: float32 (fc 06 writes int16 uint16)",
                ModbusServiceValidator.validate(wide));

        // 10 是寄存器区：位格式没有「写哪个位」可言（那些走 0F）
        ModbusService bit = writeService(ModbusPollingRequests.write("10", 0,
                ModbusPollingRequests.field(1, "开机", 0, "bit", true)));
        assertEquals("invalid functions[0].request.fields[0].format: bit (fc 10 writes "
                        + "float32 int16 int32 uint16 uint32)",
                ModbusServiceValidator.validate(bit));
    }

    @Test
    void rejectsDuplicatedWriteIndexAndName() {
        ModbusService duplicatedIndex = writeService(ModbusPollingRequests.write("10", 0,
                ModbusPollingRequests.field(1, "设定温度", 0, "uint16", 70),
                ModbusPollingRequests.field(1, "运行模式", 1, "uint16", 1)));
        assertEquals("invalid functions[0].request.fields[1].index: 1 (duplicated)",
                ModbusServiceValidator.validate(duplicatedIndex));

        // 重名比重复序号更隐蔽：invoke 的 values 拿名字取值，两个字段会抢同一个 key
        ModbusService duplicatedName = writeService(ModbusPollingRequests.write("10", 0,
                ModbusPollingRequests.field(1, "设定温度", 0, "uint16", 70),
                ModbusPollingRequests.field(2, "设定温度", 1, "uint16", 1)));
        assertEquals("invalid functions[0].request.fields[1].field: 设定温度 (duplicated)",
                ModbusServiceValidator.validate(duplicatedName));
    }

    @Test
    void rejectsByteOrderOnWideWriteField() {
        ModbusService noOrder = writeService(ModbusPollingRequests.write("10", 0,
                ModbusPollingRequests.field(1, "设定温度", 0, "int32", 700)));
        assertEquals("functions[0].request.fields[0].byteOrder is required",
                ModbusServiceValidator.validate(noOrder));

        ModbusService badOrder = writeService(ModbusPollingRequests.write("10", 0,
                ModbusPollingRequests.field(1, "设定温度", 0, "int32", "ABCDEFGH", 700)));
        assertEquals("invalid functions[0].request.fields[0].byteOrder: ABCDEFGH",
                ModbusServiceValidator.validate(badOrder));
    }

    @Test
    void acceptsContiguousWriteFields() {
        // 寄存器区：一个 uint16（1 个寄存器）+ 一个 int32（2 个），偏移 0、1
        ModbusService registers = writeService(ModbusPollingRequests.write("10", 0,
                ModbusPollingRequests.field(1, "运行模式", 0, "uint16", 1),
                ModbusPollingRequests.field(2, "设定温度", 1, "int32", "ABCD", 700)));
        assertNull(ModbusServiceValidator.validate(registers));

        // 位区：偏移恰好是 0..n-1
        ModbusService bits = writeService(ModbusPollingRequests.write("0F", 0,
                ModbusPollingRequests.field(1, "开机", 0, "bit", true),
                ModbusPollingRequests.field(2, "水泵", 1, "bit", false)));
        assertNull(ModbusServiceValidator.validate(bits));
    }

    @Test
    void rejectsWriteFieldsWithGaps() {
        // fc 0F/10 的帧只有「起始地址 + 个数 + 紧凑数据区」，没有空洞可言
        ModbusService gap = writeService(ModbusPollingRequests.write("10", 0,
                ModbusPollingRequests.field(1, "运行模式", 0, "uint16", 1),
                ModbusPollingRequests.field(2, "设定温度", 2, "uint16", 70)));
        assertEquals("invalid functions[0].request.fields: register offset of field 设定温度 is 2 "
                        + "(must be 1, fields are ordered by index without gaps)",
                ModbusServiceValidator.validate(gap));

        ModbusService bitGap = writeService(ModbusPollingRequests.write("0F", 0,
                ModbusPollingRequests.field(1, "开机", 0, "bit", true),
                ModbusPollingRequests.field(2, "水泵", 2, "bit", false)));
        assertEquals("invalid functions[0].request.fields: bit offset of field 水泵 is 2 "
                        + "(must be 1, fields are ordered by index without gaps)",
                ModbusServiceValidator.validate(bitGap));
    }

    @Test
    void rejectsBadDefaultWriteValue() {
        // 位只有 true/false：写 1 的人多半以为位就是 0/1
        ModbusService notBoolean = writeService(ModbusPollingRequests.write("05", 0,
                ModbusPollingRequests.field(1, "开机", "bit", 1)));
        assertEquals("invalid functions[0].request.fields[0].value: 1 (a bit is true or false)",
                ModbusServiceValidator.validate(notBoolean));

        // 整数格式不能带小数
        ModbusService fractional = writeService(ModbusPollingRequests.write("06", 0,
                ModbusPollingRequests.field(1, "设定温度", "int16", 1.5)));
        assertEquals("invalid functions[0].request.fields[0].value: 1.5 (format int16 needs an integer)",
                ModbusServiceValidator.validate(fractional));

        ModbusService overflow = writeService(ModbusPollingRequests.write("06", 0,
                ModbusPollingRequests.field(1, "设定温度", "uint16", 70000)));
        assertEquals("invalid functions[0].request.fields[0].value: 70000 (out of range for uint16)",
                ModbusServiceValidator.validate(overflow));
    }

    @Test
    void acceptsNumericDefaultOnFloatField() {
        // float32 出的是任意有限数值，1.5 在这里是合法的（与 int16 那条正好相对）
        ModbusService decimal = writeService(ModbusPollingRequests.write("10", 0,
                ModbusPollingRequests.field(1, "设定温度", 0, "float32", "ABCD", 7.5)));
        assertNull(ModbusServiceValidator.validate(decimal));
    }

    // ===== request ↔ response 对账 =====

    @Test
    void rejectsResponseNotMatchingTheReadQuantity() {
        // 读 1 个寄存器 = 2 字节
        ModbusService wide = service();
        wide.functions.get(0).response.fields.get(0).format = "uint32";
        wide.functions.get(0).response.fields.get(0).bytes = 4;
        assertEquals("invalid functions[0].response: declares 4 bytes, but fc 03 with quantity 1 reads 2",
                ModbusServiceValidator.validate(wide));

        ModbusService narrow = service();
        narrow.functions.get(0).request = ModbusPollingRequests.read("03", 0, 2);
        assertEquals("invalid functions[0].response: declares 2 bytes, but fc 03 with quantity 2 reads 4",
                ModbusServiceValidator.validate(narrow));
    }

    @Test
    void rejectsMissingResponseOnReadFunction() {
        // 没有解析规则的读方法返回 {}：调用方看到的是「成功但没有值」，是静默失效
        ModbusService noResponse = service();
        noResponse.functions.get(0).response = null;
        assertEquals("functions[0].response is required for a read function (a method with no field returns {})",
                ModbusServiceValidator.validate(noResponse));

        ModbusService emptyResponse = service();
        emptyResponse.functions.get(0).response = ModbusPollingResponses.of();
        assertEquals("functions[0].response is required for a read function (a method with no field returns {})",
                ModbusServiceValidator.validate(emptyResponse));
    }

    @Test
    void rejectsResponseOnWriteFunction() {
        // 写应答是请求回显，没有「读到的值」可解析
        ModbusService echoed = writeService(singleRegister());
        echoed.functions.get(0).response = ModbusPollingResponses.of(field("进水温度", "uint16", 2));
        assertEquals("invalid functions[0].response: not allowed on a write function (the reply echoes the request)",
                ModbusServiceValidator.validate(echoed));
    }

    @Test
    void rejectsInconsistentSlaveId() {
        // 一个服务由一条点表映射而来，一条点表只有一个 slave：写错不会报错，只会静默读到隔壁设备
        ModbusService mixed = service();
        mixed.functions.get(1).request.slaveId = 2;
        assertEquals("invalid functions[1].request.slaveId: 2 (must equal service slaveId 1)",
                ModbusServiceValidator.validate(mixed));
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
        ModbusService written = writeService(singleRegister());
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
        // 写方法开轮询：显式拒。以前是间接成立的（polling=true 要求 interval，而 interval 不允许
        // 配在写方法上），绕了一层，读代码的人看不出「写方法一律不许轮询」这条规矩。
        ModbusService written = writeService(singleRegister());
        written.functions.get(0).polling = true;
        assertEquals("invalid functions[0].polling: true (only read functions (fc 01/02/03/04) can be polled)",
                ModbusServiceValidator.validate(written));

        // 写方法上带周期又开轮询：先撞的还是轮询这条（它在周期之前）
        written.functions.get(0).interval = 5;
        assertEquals("invalid functions[0].polling: true (only read functions (fc 01/02/03/04) can be polled)",
                ModbusServiceValidator.validate(written));

        // 光有周期、没开轮询：一样不许（周期本身在写方法上就无效）
        ModbusService intervalOnly = writeService(singleRegister());
        intervalOnly.functions.get(0).interval = 5;
        assertEquals("invalid functions[0].interval: 5 (only read functions (fc 01/02/03/04) can be polled)",
                ModbusServiceValidator.validate(intervalOnly));
    }

    // ===== response =====

    @Test
    void rejectsFormatWidthMismatch() {
        ModbusService mismatched = service();
        ModbusPollingResponseField field = mismatched.functions.get(0).response.fields.get(0);
        field.format = "int8";     // int8 只占 1 字节
        assertEquals("invalid functions[0].response.fields[0].bytes: 2 (format int8 needs 1)",
                ModbusServiceValidator.validate(mismatched));
    }

    @Test
    void rejectsUnknownFormatAndByteOrder() {
        ModbusService badFormat = service();
        badFormat.functions.get(0).response.fields.get(0).format = "uint64";
        assertEquals("invalid functions[0].response.fields[0].format: uint64",
                ModbusServiceValidator.validate(badFormat));

        ModbusService badOrder = service();
        badOrder.functions.get(0).response.fields.get(0).byteOrder = "ABCDEFGH";
        assertEquals("invalid functions[0].response.fields[0].byteOrder: ABCDEFGH",
                ModbusServiceValidator.validate(badOrder));

        ModbusService noOrder = service();
        noOrder.functions.get(0).response.fields.get(0).byteOrder = null;
        assertEquals("functions[0].response.fields[0].byteOrder is required",
                ModbusServiceValidator.validate(noOrder));
    }

    @Test
    void stringFormatTakesAnyWidth() {
        ModbusService text = service();
        text.functions.get(0).request = ModbusPollingRequests.read("03", 0, 4);   // 8 字节 = 4 个寄存器
        ModbusPollingResponseField field = text.functions.get(0).response.fields.get(0);
        field.format = "string";
        field.bytes = 8;           // string 宽度不固定，由 bytes 决定，不做宽度比对
        assertNull(ModbusServiceValidator.validate(text));
    }

    @Test
    void singleByteNeedsNoByteOrder() {
        ModbusService oneByte = service();
        oneByte.functions.get(0).request = ModbusPollingRequests.read("02", 0, 8);   // 1 字节 = 8 个位
        ModbusPollingResponseField flag = oneByte.functions.get(0).response.fields.get(0);
        flag.format = "uint8";
        flag.bytes = 1;
        flag.byteOrder = null;     // 单字节没有字节序可言
        assertNull(ModbusServiceValidator.validate(oneByte));
    }

    @Test
    void byteOrderIsRequiredForEveryMultiByteField() {
        // 现行规则是「bytes > 1 就必填」，string 也不例外（虽然 string 的取值并不用 byteOrder）
        ModbusService text = service();
        ModbusPollingResponseField field = text.functions.get(0).response.fields.get(0);
        field.format = "string";
        field.bytes = 8;
        field.byteOrder = null;
        assertEquals("functions[0].response.fields[0].byteOrder is required",
                ModbusServiceValidator.validate(text));
    }

    @Test
    void rejectsEmptyOrDuplicatedValueList() {
        ModbusService noDescription = service();
        noDescription.functions.get(0).response.fields.get(0).valueList = List.of(value(0L, " "));
        assertEquals("functions[0].response.fields[0].value-list[0].description is required",
                ModbusServiceValidator.validate(noDescription));

        ModbusService duplicated = service();
        duplicated.functions.get(0).response.fields.get(0).valueList = List.of(value(0L, "ON"), value(0L, "OFF"));
        assertEquals("invalid functions[0].response.fields[0].value-list[1].value: 0 (duplicated)",
                ModbusServiceValidator.validate(duplicated));
    }

    /** 同一应答内所有出值名必须唯一：parser 把它们放进同一个结果对象，重名会互相覆盖。 */
    @Test
    void rejectsDuplicatedFieldNames() {
        ModbusService duplicated = service();
        ModbusPollingResponseField second = function(1).response.fields.get(0); // 序号不同、名字相同的第二个字段
        second.index = 2;
        duplicated.functions.get(0).response.fields.add(second);
        assertEquals("invalid functions[0].response.fields[1].field: 进水温度 (duplicated)",
                ModbusServiceValidator.validate(duplicated));
    }

    @Test
    void rejectsBitListOffsetBeyondField() {
        ModbusService beyond = service();
        ModbusPollingResponseField field = beyond.functions.get(0).response.fields.get(0);
        field.bitList = new ArrayList<>(List.of(bit(16, "第17位"))); // bytes 2 → 只有 0..15
        assertEquals("invalid functions[0].response.fields[0].bit-list[0].offset: 16 (field covers 16 bits)",
                ModbusServiceValidator.validate(beyond));
    }

    @Test
    void rejectsDuplicatedBitAndFieldNames() {
        ModbusService duplicatedOffset = service();
        ModbusPollingResponseField field = duplicatedOffset.functions.get(0).response.fields.get(0);
        field.bitList = new ArrayList<>(List.of(bit(0, "第1位"), bit(0, "第2位")));
        assertEquals("invalid functions[0].response.fields[0].bit-list[1].offset: 0 (duplicated)",
                ModbusServiceValidator.validate(duplicatedOffset));

        // 位名与所在字段重名同样会互相覆盖
        ModbusService shadowed = service();
        ModbusPollingResponseField owner = shadowed.functions.get(0).response.fields.get(0);
        owner.bitList = new ArrayList<>(List.of(bit(0, "进水温度")));
        assertEquals("invalid functions[0].response.fields[0].bit-list[0].field: 进水温度 (duplicated)",
                ModbusServiceValidator.validate(shadowed));
    }

    @Test
    void acceptsBitListWithinField() {
        ModbusService bits = service();
        ModbusPollingResponseField field = bits.functions.get(0).response.fields.get(0);
        field.bitList = new ArrayList<>(List.of(bit(0, "进水阀"), bit(15, "报警")));

        assertNull(ModbusServiceValidator.validate(bits));
    }

    // ===== 告警配置 =====

    @Test
    void acceptsAlarms() {
        // 数值字段：超过 80 报「温度过高」
        assertNull(validateFieldAlarm(alarm(">", 80.0, null), field("进水温度", "uint16", 2)));
        // 取值表字段：等于「ON」报「水泵异常」（比的是描述字符串，不是原始值）
        ModbusPollingResponseField named = field("水泵状态", "uint16", 2);
        named.valueList = new ArrayList<>(List.of(value(0L, "OFF"), value(1L, "ON")));
        assertNull(validateFieldAlarm(alarm("=", null, "ON"), named));
        // 数值字段用「等于」比数
        assertNull(validateFieldAlarm(alarm("=", 1.0, null), field("进水温度", "uint16", 2)));
    }

    @Test
    void acceptsARuleGroupOnOneField() {
        // 一个出值一组规则：阈值各异、甚至方向相反（低侧与高侧永远不可能同时命中）都合法
        ModbusPollingResponseFieldAlarm low = alarm("r1", "<", 20.0, null);
        low.level = "WARN";
        ModbusPollingResponseFieldAlarm high = alarm("r2", ">", 26.0, null);
        high.level = "INFO";
        ModbusPollingResponseFieldAlarm worst = alarm("r3", ">", 30.0, null);
        worst.level = "CRITICAL";
        assertNull(validateFieldAlarms(List.of(low, high, worst), field("进水温度", "uint16", 2)));

        // 停用的规则不查 id（半配置合法），也不占用组内的 id 名额
        ModbusPollingResponseFieldAlarm disabled = alarm(null, ">", 40.0, null);
        disabled.enabled = false;
        assertNull(validateFieldAlarms(List.of(low, disabled), field("进水温度", "uint16", 2)));
    }

    @Test
    void rejectsBadAlarmIds() {
        String base = "functions[0].response.fields[0].alarms";
        ModbusPollingResponseField temperature = field("进水温度", "uint16", 2);

        // id 是「这条开着的告警是哪条规则开的」的唯一依据：启用了就必须有
        assertEquals(base + "[0].id is required",
                validateFieldAlarms(List.of(alarm(null, ">", 80.0, null)), temperature));
        assertEquals(base + "[0].id is required",
                validateFieldAlarms(List.of(alarm("  ", ">", 80.0, null)), temperature));

        ModbusPollingResponseFieldAlarm longId = alarm("x".repeat(65), ">", 80.0, null);
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
        String base = "functions[0].response.fields[0].alarms";
        ModbusPollingResponseField temperature = field("进水温度", "uint16", 2);

        List<ModbusPollingResponseFieldAlarm> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(alarm("r" + i, ">", 80.0 + i, null));
        }
        assertNull(validateFieldAlarms(eight, temperature));

        List<ModbusPollingResponseFieldAlarm> nine = new ArrayList<>(eight);
        nine.add(alarm("r8", ">", 90.0, null));
        assertEquals("invalid " + base + ": too many rules (8 max)",
                validateFieldAlarms(nine, temperature));
    }

    @Test
    void acceptsBitAlarm() {
        // 位只有 0/1，只能用「等于」比 0 或 1：进水阀 = 1 告警
        ModbusPollingResponseField bits = field("进水阀位", "uint8", 1);
        bits.bitList = new ArrayList<>(List.of(bit(0, "进水阀")));
        bits.bitList.get(0).alarms = new ArrayList<>(List.of(alarm("=", 1.0, null)));

        ModbusService service = service();
        // 1 个字节的位字段只能来自位区读（01/02）：8 个位 = 1 字节
        service.functions.get(0).request = ModbusPollingRequests.read("02", 0, 8);
        service.functions.get(0).response = ModbusPollingResponses.of(bits);
        assertNull(ModbusServiceValidator.validate(service));
    }

    @Test
    void ignoresDisabledAlarm() {
        // enabled != true：半配置合法，与 polling 同一条规矩 —— 留着配置、先不报
        ModbusPollingResponseFieldAlarm disabled = alarm("??", null, null);
        disabled.enabled = false;
        disabled.text = null;
        disabled.level = null;
        assertNull(validateFieldAlarm(disabled, field("进水温度", "uint16", 2)));

        ModbusPollingResponseFieldAlarm absent = alarm("??", null, null);
        absent.enabled = null;
        assertNull(validateFieldAlarm(absent, field("进水温度", "uint16", 2)));
    }

    @Test
    void rejectsBadAlarmScalars() {
        String base = "functions[0].response.fields[0].alarms[0]";

        ModbusPollingResponseFieldAlarm badCompare = alarm("!=", 80.0, null);
        assertEquals("invalid " + base + ".compare: != (must be one of > >= < <= =)",
                validateFieldAlarm(badCompare, field("进水温度", "uint16", 2)));

        ModbusPollingResponseFieldAlarm noCompare = alarm(null, 80.0, null);
        assertEquals("invalid " + base + ".compare: null (must be one of > >= < <= =)",
                validateFieldAlarm(noCompare, field("进水温度", "uint16", 2)));

        ModbusPollingResponseFieldAlarm badLevel = alarm(">", 80.0, null);
        badLevel.level = "FATAL";
        assertEquals("invalid " + base + ".level: FATAL (must be one of INFO WARN CRITICAL)",
                validateFieldAlarm(badLevel, field("进水温度", "uint16", 2)));

        ModbusPollingResponseFieldAlarm blankText = alarm(">", 80.0, null);
        blankText.text = "  ";
        assertEquals(base + ".text is required",
                validateFieldAlarm(blankText, field("进水温度", "uint16", 2)));

        ModbusPollingResponseFieldAlarm longText = alarm(">", 80.0, null);
        longText.text = "超".repeat(65);
        assertEquals("invalid " + base + ".text: too long (64 characters max)",
                validateFieldAlarm(longText, field("进水温度", "uint16", 2)));

        ModbusPollingResponseFieldAlarm noThreshold = alarm(">", null, null);
        assertEquals(base + ".threshold is required (compare >)",
                validateFieldAlarm(noThreshold, field("进水温度", "uint16", 2)));

        ModbusPollingResponseFieldAlarm nan = alarm(">", Double.NaN, null);
        assertEquals("invalid " + base + ".threshold: NaN",
                validateFieldAlarm(nan, field("进水温度", "uint16", 2)));
    }

    @Test
    void rejectsNullRuleInAGroup() {
        // 数组里的空元素：要么是前端拼错了，要么是手工改库改出来的，收下来判定时会 NPE
        assertEquals("functions[0].response.fields[0].alarms[1] is null",
                validateFieldAlarms(Arrays.asList(alarm(">", 80.0, null), null),
                        field("进水温度", "uint16", 2)));
    }

    @Test
    void rejectsAlarmOnWriteFunction() {
        // 写方法的应答是请求回显，没有「读到的值」可言 —— 与 interval 同一条规则。
        // 字段本身校验得过（告警规则排在「写方法不许有 response」之前），所以这里报的就是告警那条。
        ModbusService service = writeService(ModbusPollingRequests.write("05", 0,
                ModbusPollingRequests.field(1, "开机", "bit", true)));
        ModbusPollingResponseField echoed = field("进水温度", "uint16", 2);
        echoed.alarms = new ArrayList<>(List.of(alarm(">", 80.0, null)));
        service.functions.get(0).response = ModbusPollingResponses.of(echoed);

        assertEquals("invalid functions[0].response.fields[0].alarms[0]: only read functions (fc 01/02/03/04) can have alarms",
                ModbusServiceValidator.validate(service));
    }

    @Test
    void rejectsAlarmOnStringField() {
        // string 字段出的是任意文本，没有可比的量
        ModbusPollingResponseFieldAlarm a = alarm(">", 80.0, null);
        assertEquals("invalid functions[0].response.fields[0].alarms[0]: a string field has no comparable value",
                validateFieldAlarm(a, field("设备型号", "string", 4)));
    }

    @Test
    void rejectsNumericCompareOnValueListField() {
        // 带 value-list 的字段出的是描述字符串：配 > 会随原始值是否命中取值表而时灵时不灵
        ModbusPollingResponseField named = field("水泵状态", "uint16", 2);
        named.valueList = new ArrayList<>(List.of(value(0L, "OFF"), value(1L, "ON")));

        assertEquals("invalid functions[0].response.fields[0].alarms[0].compare: > (a value-list field yields names, use =)",
                validateFieldAlarm(alarm(">", 80.0, null), named));
    }

    @Test
    void rejectsBadEqualsOnValueListField() {
        String base = "functions[0].response.fields[0].alarms[0]";
        ModbusPollingResponseField named = field("水泵状态", "uint16", 2);
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
        assertEquals("invalid functions[0].response.fields[0].alarms[0].state: only allowed on a value-list field (use threshold)",
                validateFieldAlarm(alarm("=", 1.0, "ON"), field("进水温度", "uint16", 2)));
        assertEquals("invalid functions[0].response.fields[0].alarms[0].state: only allowed when compare is =",
                validateFieldAlarm(alarm(">", 80.0, "ON"), field("进水温度", "uint16", 2)));
        assertEquals("functions[0].response.fields[0].alarms[0].threshold is required (compare =)",
                validateFieldAlarm(alarm("=", null, null), field("进水温度", "uint16", 2)));
    }

    @Test
    void rejectsBadBitAlarm() {
        String base = "functions[0].response.fields[0].bit-list[0].alarms[0]";
        ModbusPollingResponseField bits = field("进水阀位", "uint8", 1);
        bits.bitList = new ArrayList<>(List.of(bit(0, "进水阀")));

        // 位只有 0/1：数值比较与 0.5 都是定义错误
        bits.bitList.get(0).alarms = new ArrayList<>(List.of(alarm(">", 1.0, null)));
        ModbusService service = service();
        service.functions.get(0).response = ModbusPollingResponses.of(bits);
        assertEquals("invalid " + base + ".compare: > (a bit is 0 or 1, use =)",
                ModbusServiceValidator.validate(service));

        bits.bitList.get(0).alarms = new ArrayList<>(List.of(alarm("=", 0.5, null)));
        assertEquals("invalid " + base + ".threshold: 0.5 (a bit is 0 or 1, use =)",
                ModbusServiceValidator.validate(service));
    }

    @Test
    void bitRuleGroupHasItsOwnIdSpace() {
        // id 的唯一性只在「同一位」内要求：位与父字段各是一组、各开各的行，同名不冲突
        String base = "functions[0].response.fields[0].bit-list[0].alarms";
        ModbusPollingResponseField bits = field("进水阀位", "uint8", 1);
        bits.bitList = new ArrayList<>(List.of(bit(0, "进水阀")));
        bits.bitList.get(0).alarms = new ArrayList<>(List.of(alarm("r1", "=", 1.0, null)));

        ModbusService service = service();
        // 同 acceptsBitAlarm：1 字节的位字段配 8 位的位区读
        service.functions.get(0).request = ModbusPollingRequests.read("01", 0, 8);
        service.functions.get(0).response = ModbusPollingResponses.of(bits);
        assertNull(ModbusServiceValidator.validate(service));

        // 位组内自己重复，照拒
        bits.bitList.get(0).alarms = new ArrayList<>(
                List.of(alarm("r1", "=", 1.0, null), alarm("r1", "=", 0.0, null)));
        assertEquals("invalid " + base + "[1].id: r1 (duplicated)",
                ModbusServiceValidator.validate(service));
    }

    // ===== invoke 入参 =====

    @Test
    void acceptsInvokeWithEveryFieldDefaulted() {
        ModbusPolling write = writeFunction(singleRegister());
        assertNull(ModbusServiceValidator.validateInvoke(write, null));
        assertNull(ModbusServiceValidator.validateInvoke(write, Map.of()));
    }

    @Test
    void invokeRejectsValuesOnReadFunctions() {
        // 静默忽略一个用户以为生效了的载荷，比报错糟得多
        assertEquals("invalid values: a read function (fc 03) takes no values",
                ModbusServiceValidator.validateInvoke(function(1), Map.of("进水温度", 1)));
        // 没给 values 的读方法照旧
        assertNull(ModbusServiceValidator.validateInvoke(function(1), null));
    }

    @Test
    void invokeRequiresFieldsWithoutDefault() {
        ModbusPolling write = writeFunction(ModbusPollingRequests.write("10", 0,
                ModbusPollingRequests.field(1, "设定温度", 0, "uint16", null)));
        assertEquals("values.设定温度 is required (this field has no default)",
                ModbusServiceValidator.validateInvoke(write, null));
        assertNull(ModbusServiceValidator.validateInvoke(write, Map.of("设定温度", 70)));
    }

    @Test
    void invokeRejectsUnknownFieldName() {
        ModbusPolling write = writeFunction(singleRegister());
        assertEquals("invalid values.运行模式: not a field of this function",
                ModbusServiceValidator.validateInvoke(write, Map.of("运行模式", 1)));
    }

    @Test
    void invokeChecksValueTypeAndRange() {
        ModbusPolling write = writeFunction(singleRegister());
        assertEquals("invalid values.设定温度: 70000 (out of range for uint16)",
                ModbusServiceValidator.validateInvoke(write, Map.of("设定温度", 70000)));
        assertEquals("invalid values.设定温度: 70.5 (format uint16 needs an integer)",
                ModbusServiceValidator.validateInvoke(write, Map.of("设定温度", 70.5)));
        assertEquals("invalid values.设定温度: abc (a register value is a finite number)",
                ModbusServiceValidator.validateInvoke(write, Map.of("设定温度", "abc")));

        // 请求体过 Jackson 之后 70 可能是 Double：按值判整数，不按 Java 类型判
        assertNull(ModbusServiceValidator.validateInvoke(write, Map.of("设定温度", 70.0)));
    }

    @Test
    void invokeChecksBitValues() {
        ModbusPolling write = writeFunction(ModbusPollingRequests.write("05", 0,
                ModbusPollingRequests.field(1, "开机", "bit", true)));
        assertEquals("invalid values.开机: 1 (a bit is true or false)",
                ModbusServiceValidator.validateInvoke(write, Map.of("开机", 1)));
        assertNull(ModbusServiceValidator.validateInvoke(write, Map.of("开机", false)));
    }

    // ===== 夹具 =====

    /** 一份各处都合法的定义：各用例只改自己关心的那一处。 */
    private static ModbusService service() {
        ModbusService service = new ModbusService();
        service.name = "1 号冷水机组";
        service.version = ModbusService.VERSION;
        service.slaveId = 1;
        service.device = device();
        service.functions = new ArrayList<>(List.of(function(1), function(2)));
        return service;
    }

    /** 一份只含给定写方法的定义（写方法没有 response，也没有 interval / polling）。 */
    private static ModbusService writeService(ModbusPollingRequest request) {
        ModbusService service = new ModbusService();
        service.name = "1 号冷水机组";
        service.version = ModbusService.VERSION;
        service.slaveId = 1;
        service.device = device();

        ModbusPolling write = new ModbusPolling();
                    write.index = 1;      // 方法身份（type.index 只是溯源）
                    write.from = new ModbusPollingFrom();
                    write.from.type = "command";
                    write.from.index = 1;
        write.name = "写设定温度";
        write.request = request;
        service.functions = new ArrayList<>(List.of(write));
        return service;
    }

    private static ModbusPolling function(int index) {
        ModbusPolling function = new ModbusPolling();
                    function.index = index;   // 方法身份（type.index 只是溯源）
                    function.from = new ModbusPollingFrom();
                    function.from.type = "command";
                    function.from.index = index;
        function.name = "读进水温度";
        function.request = ModbusPollingRequests.holding();

        ModbusPollingResponseField field = new ModbusPollingResponseField();
        field.index = 1;
        field.field = "进水温度";
        field.bytes = 2;
        field.format = "uint16";
        field.byteOrder = "ABCD";
        function.response = ModbusPollingResponses.of(field);
        return function;
    }

    /** 依赖设备的调用坐标：各用例只关心自己那一处，这里给一份各处都合法的。 */
    private static DeviceOn device() {
        DeviceOn device = new DeviceOn();
        device.did = "did-1";
        device.siid = 1;
        device.aiid = 1;
        device.argument = 1;
        return device;
    }

    /** 最常用的那条写请求：fc 06 写一个寄存器，带缺省值。 */
    private static ModbusPollingRequest singleRegister() {
        return ModbusPollingRequests.write("06", 0,
                ModbusPollingRequests.field(1, "设定温度", "uint16", 70));
    }

    private static ModbusPolling writeFunction(ModbusPollingRequest request) {
        return writeService(request).functions.get(0);
    }

    private static ModbusPollingResponseFieldValue value(Long value, String description) {
        ModbusPollingResponseFieldValue entry = new ModbusPollingResponseFieldValue();
        entry.value = value;
        entry.description = description;
        return entry;
    }

    private static ModbusPollingResponseFieldBit bit(int offset, String field) {
        ModbusPollingResponseFieldBit entry = new ModbusPollingResponseFieldBit();
        entry.offset = offset;
        entry.field = field;
        return entry;
    }

    /** 一份「启用之后应当自洽」的规则：各用例只改自己关心的那一处。{@code id} 默认 {@code r1}。 */
    private static ModbusPollingResponseFieldAlarm alarm(String compare, Double threshold, String state) {
        return alarm("r1", compare, threshold, state);
    }

    /** 同上，但规则身份自己给（{@code null} 用来测「启用了却没有 id」）。 */
    private static ModbusPollingResponseFieldAlarm alarm(String id, String compare, Double threshold, String state) {
        ModbusPollingResponseFieldAlarm alarm = new ModbusPollingResponseFieldAlarm();
        alarm.id = id;
        alarm.enabled = true;
        alarm.compare = compare;
        alarm.threshold = threshold;
        alarm.state = state;
        alarm.level = "WARN";
        alarm.text = "温度过高";
        return alarm;
    }

    private static ModbusPollingResponseField field(String name, String format, Integer bytes) {
        ModbusPollingResponseField field = new ModbusPollingResponseField();
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
    private static String validateFieldAlarm(ModbusPollingResponseFieldAlarm alarm, ModbusPollingResponseField field) {
        return validateFieldAlarms(new ArrayList<>(List.of(alarm)), field);
    }

    /** 同上，但一次挂一组规则。 */
    private static String validateFieldAlarms(List<ModbusPollingResponseFieldAlarm> alarms, ModbusPollingResponseField field) {
        ModbusService service = service();
        field.alarms = new ArrayList<>(alarms);
        service.functions.get(0).response = ModbusPollingResponses.of(field);
        return ModbusServiceValidator.validate(service);
    }
}
