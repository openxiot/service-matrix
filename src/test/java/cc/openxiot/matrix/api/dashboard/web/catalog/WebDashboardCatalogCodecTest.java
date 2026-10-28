package cc.openxiot.matrix.api.dashboard.web.catalog;

import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.fd.poll.ModbusPollFrom;
import cc.openxiot.matrix.db.modbus.fd.poll.ModbusPoll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.modbus.fd.DeviceOn;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.modbus.fd.poll.response.field.ModbusResponseField;
import cc.openxiot.matrix.db.modbus.fd.poll.response.ModbusResponses;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

/**
 * 候选清单的线上形状。**纯 JUnit，不连库**。
 *
 * <p>两条约定在这里被钉住：设备**不带 {@code name}**（那个名字前端自己按四级回退链解析），
 * 服务**整份下发**。寄存器中心下，编辑器要的候选 = 点表 {@code configSnapshot.registries}
 * （每只寄存器是一条可选字段，含线圈）；应答字段只引用 {@code registryIndex}</p>
 */
class DashboardCatalogCodecTest {

    @Test
    void encode_emptyLists_givesBothKeys() {
        JsonObject o = WebDashboardCatalogCodec.encode(List.of(), List.of());
        assertTrue(o.getJsonArray("devices").isEmpty());
        assertTrue(o.getJsonArray("fds").isEmpty());
    }

    @Test
    void encode_nullLists_doesNotThrow() {
        JsonObject o = WebDashboardCatalogCodec.encode(null, null);
        assertTrue(o.getJsonArray("devices").isEmpty());
        assertTrue(o.getJsonArray("fds").isEmpty());
    }

    @Test
    void encodeDevice_carriesDidTypeAndOnline() {
        JsonObject device = WebDashboardCatalogCodec.encode(List.of(device("did-1", "urn:x:tr:ac", true)), List.of())
                .getJsonArray("devices").getJsonObject(0);

        assertEquals("did-1", device.getString("did"));
        assertEquals("urn:x:tr:ac", device.getString("type"));
        assertTrue(device.getBoolean("online"));
    }

    @Test
    void encodeDevice_hasNoName() {
        JsonObject device = WebDashboardCatalogCodec.encode(List.of(device("did-1", "urn:x:tr:ac", false)), List.of())
                .getJsonArray("devices").getJsonObject(0);

        assertFalse(device.containsKey("name"));
        assertTrue(device.containsKey("online"));
        assertFalse(device.getBoolean("online"));
    }

    /**
     * 服务整份下发，形状与 {@code /modbus/device/one/{id}} 逐字相同。编辑器候选现在来自寄存器：
     * 每只寄存器给 name/format/unit（判数值与提示），线圈寄存器也是一条可选字段。
     */
    @Test
    void encodeService_reusesTheFullServiceShape() {
        JsonObject service = WebDashboardCatalogCodec.encode(List.of(), List.of(fd()))
                .getJsonArray("fds").getJsonObject(0);

        assertEquals("6512f3a4b5c6d7e8f9a0b1c5", service.getString("id"));
        assertEquals("1 号冷水机组", service.getString("name"));

        // 应答字段只引用寄存器（本体元数据在点表寄存里）
        JsonObject field = service.getJsonArray("polls").getJsonObject(0)
                .getJsonObject("response").getJsonArray("fields").getJsonObject(0);
        assertEquals(1, field.getInteger("registryIndex").intValue());
        assertFalse(field.containsKey("format"));

        JsonObject temp = service.getJsonObject("configSnapshot").getJsonArray("registries").getJsonObject(0);
        assertEquals("温度", temp.getString("name"));
        assertEquals("int16", temp.getString("dataType"));
        assertEquals("℃", temp.getString("unit"));

        JsonObject valve = service.getJsonObject("configSnapshot").getJsonArray("registries").getJsonObject(1);
        assertEquals("进水阀", valve.getString("name"));
        assertEquals("coil", valve.getString("area"));
    }

    // ===== helpers =====

    private static DeviceEntity device(String did, String type, boolean online) {
        DeviceEntity d = new DeviceEntity();
        d.did = did;
        d.type = type;
        d.online = online;
        return d;
    }

    private static FieldDevice fd() {
        ModbusRegistry temp = new ModbusRegistry();
        temp.index = 1;
        temp.address = 0;
        temp.name = "温度";
        temp.area = "holding";
        temp.dataType = "int16";
        temp.byteOrder = "ABCD";
        temp.access = "R";
        temp.unit = "℃";
        ModbusRegistry valve = new ModbusRegistry();
        valve.index = 2;
        valve.address = 0;
        valve.name = "进水阀";
        valve.area = "coil";
        valve.access = "RW";

        ModbusConfig config = new ModbusConfig();
        config.id = new ObjectId("6512f3a4b5c6d7e8f9a0b1c5");
        config.registries = List.of(temp, valve);

        ModbusResponseField field = new ModbusResponseField();
        field.registryIndex = 1;

        ModbusPoll poll = new ModbusPoll();
        poll.from = new ModbusPollFrom();
        poll.from.type = "command";
        poll.from.index = 1;
        poll.index = 1;
        poll.name = "读温度";
        poll.response = ModbusResponses.of(field);

        FieldDevice fd = new FieldDevice();
        fd.id = new ObjectId("6512f3a4b5c6d7e8f9a0b1c5");
        fd.name = "1 号冷水机组";
        fd.version = 3;
        fd.configSnapshot = config;
        fd.device = new DeviceOn();
        fd.polls = List.of(poll);
        return fd;
    }
}