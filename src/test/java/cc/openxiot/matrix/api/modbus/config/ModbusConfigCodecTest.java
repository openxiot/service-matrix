package cc.openxiot.matrix.api.modbus.config;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigOwner;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusSlave;
import cc.openxiot.matrix.db.modbus.config.codec.ModbusCommandCodec;
import cc.openxiot.matrix.db.modbus.config.codec.ModbusConfigCodec;
import cc.openxiot.matrix.db.modbus.config.codec.ModbusRegistryCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusConfigCodec} 及其子 Codec 的投影：
 * 从 {@code ModbusJson} 拆成一实体一 Codec 后，对外 JSON 形状必须与拆分前逐字一致。
 *
 * <p>点表命令已迁移到**以寄存器为中心**：读命令只出 name/fc/index/start/quantity，
 * 不再内联 数据格式/字节序/缩放/单位/位名；写命令统一成 writeItems（{@code [{registryIndex, value}]}）。</p>
 */
class ModbusConfigCodecTest {

    @Test
    void encodesConfigWithSlaveCommandsAndPerson() {
        ModbusConfig config = config();

        JsonObject o = ModbusConfigCodec.encode(config);
        // 主键对外一律十六进制字符串（URL 路径参数、服务 configId 同口径）
        assertEquals("65f0c0ffee0123456789abcd", o.getString("id"));
        assertEquals("public", o.getString("visibility"));
        assertEquals("development", o.getString("lifecycle"));

        // owner 对象打包为 {id, type, name}
        JsonObject owner = o.getJsonObject("owner");
        assertEquals("org-1", owner.getString("id"));
        assertEquals("organization", owner.getString("type"));
        assertEquals("第一个组织", owner.getString("name"));

        JsonObject slave = o.getJsonObject("slave");
        assertEquals("特灵", slave.getString("manufacturer"));
        assertEquals("19XRV", slave.getString("model"));
        assertEquals(1, slave.getInteger("slaveId").intValue());
        assertEquals("离心机", slave.getString("description"));
        // 设备类型（可选）有值才出
        assertEquals("离心式冷水机组", slave.getString("type"));

        // 读命令（03）：只出 公共字段 + quantity——数据格式/字节序/应答字段不再内联（在引用寄存器上）
        JsonObject read = o.getJsonArray("commands").getJsonObject(0);
        assertEquals("读进水温度", read.getString("name"));
        assertEquals("03", read.getString("fc"));
        assertEquals(2, read.getInteger("quantity").intValue());
        assertNull(read.getString("dataType"));
        assertNull(read.getString("byteOrder"));
        assertNull(read.getJsonArray("fields"));
        assertNull(read.getJsonArray("writeItems"));

        // 01 读位：只出 quantity，无 bitNames
        JsonObject bitRead = o.getJsonArray("commands").getJsonObject(2);
        assertEquals("01", bitRead.getString("fc"));
        assertEquals(8, bitRead.getInteger("quantity").intValue());
        assertNull(bitRead.getJsonArray("bitNames"));
        assertNull(bitRead.getJsonArray("writeItems"));

        // 写命令（0F）：统一成 writeItems = [{registryIndex, value}]
        JsonObject write = o.getJsonArray("commands").getJsonObject(1);
        assertEquals("0F", write.getString("fc"));
        JsonArray writeItems = write.getJsonArray("writeItems");
        assertEquals(1, writeItems.size());
        assertEquals(2, writeItems.getJsonObject(0).getInteger("registryIndex").intValue());
        assertEquals(true, writeItems.getJsonObject(0).getBoolean("value"));

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

    @Test
    void encodesRegistryDescriptionOnlyWhenPresent() {
        ModbusRegistry reg = new ModbusRegistry();
        reg.name = "A相电压";
        reg.index = 1;
        reg.address = 0;
        reg.area = "holding";
        reg.access = "R";
        reg.description = "来自 1 号电表";

        assertEquals("来自 1 号电表", ModbusRegistryCodec.encode(reg).getString("description"));

        // 没填就不出键（与其余可选字段同口径）
        reg.description = null;
        assertFalse(ModbusRegistryCodec.encode(reg).containsKey("description"));
    }

    @Test
    void encodesWriteItemsInOrder() {
        ModbusCommand c = new ModbusCommand();
        c.name = "写多寄存器";
        c.fc = "10";
        c.index = 1;
        c.start = 10;
        ModbusCommand.ModbusCommandWriteItem a = new ModbusCommand.ModbusCommandWriteItem();
        a.registryIndex = 2;
        a.value = 100L;
        ModbusCommand.ModbusCommandWriteItem b = new ModbusCommand.ModbusCommandWriteItem();
        b.registryIndex = 3;
        b.value = -1;
        c.writeItems = List.of(a, b);

        JsonArray arr = ModbusCommandCodec.encode(c).getJsonArray("writeItems");
        assertEquals(2, arr.size());
        assertEquals(2, arr.getJsonObject(0).getInteger("registryIndex").intValue());
        assertEquals(100L, arr.getJsonObject(0).getLong("value").longValue());
        assertEquals(3, arr.getJsonObject(1).getInteger("registryIndex").intValue());
        assertEquals(-1, arr.getJsonObject(1).getInteger("value").intValue());
    }

    // ===== 夹具 =====

    private static ModbusConfig config() {
        ModbusConfig config = new ModbusConfig();
        config.id = new ObjectId("65f0c0ffee0123456789abcd");
        config.owner = ModbusConfigOwner.ofOrg("org-1", "第一个组织");
        config.visibility = "public";
        config.lifecycle = "development";

        ModbusSlave slave = new ModbusSlave();
        slave.manufacturer = "特灵";
        slave.model = "19XRV";
        slave.slaveId = 1;
        slave.type = "离心式冷水机组";
        slave.description = "离心机";
        config.slave = slave;

        ModbusCommand read = new ModbusCommand();
        read.name = "读进水温度";
        read.fc = "03";
        read.index = 1;
        read.start = 0;
        read.quantity = 2;

        ModbusCommand write = new ModbusCommand();
        write.name = "写开关";
        write.fc = "0F";
        write.index = 2;
        write.start = 10;
        ModbusCommand.ModbusCommandWriteItem coil = new ModbusCommand.ModbusCommandWriteItem();
        coil.registryIndex = 2;
        coil.value = true;
        write.writeItems = List.of(coil);

        ModbusCommand bitRead = new ModbusCommand();
        bitRead.name = "读开关状态";
        bitRead.fc = "01";
        bitRead.index = 3;
        bitRead.start = 0;
        bitRead.quantity = 8;

        config.commands = List.of(read, write, bitRead);

        Person creator = new Person();
        creator.id = "u-1";
        creator.name = "张三";
        creator.timestamp = new java.util.Date(1_700_000_000_000L);
        config.creator = creator;
        return config;
    }
}