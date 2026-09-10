package cc.openxiot.matrix.api.modbus.service;

import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldValue;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import cc.openxiot.matrix.db.modbus.service.DeviceOn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ModbusServiceValidator} 的规则校验（MODBUS2.md 第四节）。
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

        ModbusService noOrg = service();
        noOrg.orgId = null;
        assertEquals("orgId is required", ModbusServiceValidator.validate(noOrg));
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

    // ===== 夹具 =====

    /** 一份各处都合法的定义：各用例只改自己关心的那一处。 */
    private static ModbusService service() {
        ModbusService service = new ModbusService();
        service.orgId = "org-1";
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
}
