package cc.openxiot.matrix.api.modbus.config;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusDeviceType;
import cc.openxiot.matrix.db.modbus.config.ModbusSlave;
import cc.openxiot.matrix.db.modbus.config.codec.ModbusCommandCodec;
import cc.openxiot.matrix.db.modbus.config.codec.ModbusConfigCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusConfigCodec} 及其子 Codec 的投影：
 * 从 {@code ModbusJson} 拆成一实体一 Codec 后，对外 JSON 形状必须与拆分前逐字一致。
 */
class ModbusConfigCodecTest {

    @Test
    void encodesConfigWithSlaveCommandsAndPerson() {
        ModbusConfig config = config();

        JsonObject o = ModbusConfigCodec.encode(config);
        // 主键对外一律十六进制字符串（URL 路径参数、虚拟设备 configId 同口径）
        assertEquals("65f0c0ffee0123456789abcd", o.getString("id"));
        assertEquals("org-1", o.getString("orgId"));
        assertEquals("public", o.getString("visibility"));
        assertEquals("development", o.getString("lifecycle"));

        JsonObject slave = o.getJsonObject("slave");
        assertEquals("特灵", slave.getString("manufacturer"));
        assertEquals("19XRV", slave.getString("model"));
        assertEquals(1, slave.getInteger("slaveId").intValue());
        assertEquals("离心机", slave.getString("description"));
        // 已废弃的类型快照仍然照原样回显（存量文档兼容）
        assertEquals("urn:xiot-spec:device:chiller:0000A005", slave.getJsonObject("type").getString("type"));
        assertEquals("冷水机组", slave.getJsonObject("type").getJsonObject("typeDescription").getString("zh-CN"));

        JsonObject read = o.getJsonArray("commands").getJsonObject(0);
        assertEquals("读进水温度", read.getString("name"));
        assertEquals("03", read.getString("fc"));
        assertEquals(2, read.getInteger("quantity").intValue());
        assertEquals("uint16", read.getString("dataType"));
        assertEquals("ABCD", read.getString("byteOrder"));
        assertEquals(0.1, read.getDouble("scale").doubleValue());
        assertEquals("℃", read.getString("unit"));

        JsonObject write = o.getJsonArray("commands").getJsonObject(1);
        assertEquals("0F", write.getString("fc"));
        assertEquals(1, write.getJsonArray("coils").size());
        assertEquals(0, write.getJsonArray("coils").getJsonObject(0).getInteger("offset").intValue());
        assertEquals(true, write.getJsonArray("coils").getJsonObject(0).getBoolean("on"));
        assertEquals(1, write.getJsonArray("registers").size());
        assertEquals(100L, write.getJsonArray("registers").getJsonObject(0).getLong("value").longValue());

        // 人员按 id / name / 毫秒时间戳输出
        JsonObject creator = o.getJsonObject("creator");
        assertEquals("u-1", creator.getString("id"));
        assertEquals("张三", creator.getString("name"));
        assertEquals(1_700_000_000_000L, creator.getLong("timestamp").longValue());
    }

    @Test
    void encodesListKeepingOrder() {
        ModbusConfig one = config();
        ModbusConfig two = config();
        two.slave.model = "CVHG";

        JsonArray arr = ModbusConfigCodec.encode(List.of(one, two));
        assertEquals(2, arr.size());
        assertEquals("19XRV", arr.getJsonObject(0).getJsonObject("slave").getString("model"));
        assertEquals("CVHG", arr.getJsonObject(1).getJsonObject("slave").getString("model"));
    }

    @Test
    void emptyContainersInsteadOfNulls() {
        // slave / commands 恒为对象与数组（前端不必判空），其余字段没值就不出现
        JsonObject o = ModbusConfigCodec.encode(new ModbusConfig());
        assertEquals(2, o.size());
        assertTrue(o.getJsonObject("slave").isEmpty());
        assertTrue(o.getJsonArray("commands").isEmpty());
        assertNull(o.getString("id"));
        assertNull(o.getString("visibility"));

        assertTrue(ModbusConfigCodec.encode((ModbusConfig) null).isEmpty());
        assertTrue(ModbusConfigCodec.encode((List<ModbusConfig>) null).isEmpty());
        assertTrue(ModbusCommandCodec.encode((ModbusCommand) null).isEmpty());
        assertTrue(ModbusCommandCodec.encode((List<ModbusCommand>) null).isEmpty());
    }

    // ===== 夹具 =====

    private static ModbusConfig config() {
        ModbusConfig config = new ModbusConfig();
        config.id = new ObjectId("65f0c0ffee0123456789abcd");
        config.orgId = "org-1";
        config.visibility = "public";
        config.lifecycle = "development";

        ModbusSlave slave = new ModbusSlave();
        slave.manufacturer = "特灵";
        slave.model = "19XRV";
        slave.slaveId = 1;
        slave.description = "离心机";
        ModbusDeviceType type = new ModbusDeviceType();
        type.type = "urn:xiot-spec:device:chiller:0000A005";
        type.typeDescription = Map.of("zh-CN", "冷水机组");
        slave.type = type;
        config.slave = slave;

        ModbusCommand read = new ModbusCommand();
        read.name = "读进水温度";
        read.fc = "03";
        read.index = 1;
        read.start = 0;
        read.quantity = 2;
        read.dataType = "uint16";
        read.byteOrder = "ABCD";
        read.scale = 0.1;
        read.unit = "℃";

        ModbusCommand write = new ModbusCommand();
        write.name = "写开关";
        write.fc = "0F";
        write.index = 2;
        write.start = 10;
        ModbusCommand.Coil coil = new ModbusCommand.Coil();
        coil.offset = 0;
        coil.on = true;
        write.coils = List.of(coil);
        ModbusCommand.Register register = new ModbusCommand.Register();
        register.address = 100;
        register.dataType = "uint16";
        register.byteOrder = "ABCD";
        register.value = 100L;
        write.registers = List.of(register);

        config.commands = List.of(read, write);

        Person creator = new Person();
        creator.id = "u-1";
        creator.name = "张三";
        creator.timestamp = new java.util.Date(1_700_000_000_000L);
        config.creator = creator;
        return config;
    }
}
