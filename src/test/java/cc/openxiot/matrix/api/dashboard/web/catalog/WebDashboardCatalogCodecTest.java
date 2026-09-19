package cc.openxiot.matrix.api.dashboard.web.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.modbus.service.DeviceOn;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

/**
 * 候选清单的线上形状。**纯 JUnit，不连库**。
 *
 * <p>两条约定在这里被钉住：设备**不带 {@code name}**（那个名字前端自己按四级回退链解析，
 * 服务端没有它、也不该再实现一遍），服务**整份下发**（编辑器要 {@code format} / {@code unit} /
 * {@code valueList} / {@code bitList} 做级联与提示）。</p>
 */
class DashboardCatalogCodecTest {

    @Test
    void encode_emptyLists_givesBothKeys() {
        JsonObject o = WebDashboardCatalogCodec.encode(List.of(), List.of());

        // 两个键恒在：编辑器据此显示「没有可选项」，而不是把级联画成坏掉的样子
        assertTrue(o.getJsonArray("devices").isEmpty());
        assertTrue(o.getJsonArray("services").isEmpty());
    }

    @Test
    void encode_nullLists_doesNotThrow() {
        JsonObject o = WebDashboardCatalogCodec.encode(null, null);

        assertTrue(o.getJsonArray("devices").isEmpty());
        assertTrue(o.getJsonArray("services").isEmpty());
    }

    @Test
    void encodeDevice_carriesDidTypeAndOnline() {
        JsonObject device = WebDashboardCatalogCodec.encode(List.of(device("did-1", "urn:x:tr:ac", true)), List.of())
                .getJsonArray("devices").getJsonObject(0);

        assertEquals("did-1", device.getString("did"));
        assertEquals("urn:x:tr:ac", device.getString("type"));
        assertTrue(device.getBoolean("online"));
    }

    /**
     * 设备名**不在**这里 —— 前端用 {@code DeviceDisplayService} 解析（产品名 → 实例描述 → URN 段 → did），
     * 与设备列表页同一条链。服务端要是也解一遍，两处迟早解出两个名字。
     */
    @Test
    void encodeDevice_hasNoName() {
        JsonObject device = WebDashboardCatalogCodec.encode(List.of(device("did-1", "urn:x:tr:ac", false)), List.of())
                .getJsonArray("devices").getJsonObject(0);

        assertFalse(device.containsKey("name"));
        // offline 也是 false，不能因为「假值就当没这个字段」——它和「不知道」不是一回事
        assertTrue(device.containsKey("online"));
        assertFalse(device.getBoolean("online"));
    }

    /** 服务整份下发，形状与 {@code /modbus/service/one/{id}} 逐字相同，前端直接复用已有的解码器。 */
    @Test
    void encodeService_reusesTheFullServiceShape() {
        JsonObject service = WebDashboardCatalogCodec.encode(List.of(), List.of(service()))
                .getJsonArray("services").getJsonObject(0);

        assertEquals("6512f3a4b5c6d7e8f9a0b1c5", service.getString("id"));
        assertEquals("1 号冷水机组", service.getString("name"));

        JsonObject field = service.getJsonArray("functions").getJsonObject(0)
                .getJsonArray("response").getJsonObject(0);
        // 编辑器判「是不是数值」靠 format、「几位小数/单位」靠 unit，两者都必须在
        assertEquals("温度", field.getString("field"));
        assertEquals("int16", field.getString("format"));
        assertEquals("℃", field.getString("unit"));
        // 位名是独立的可选字段（用户在下拉里选它配一张卡），所以位清单也要在。
        // 线上的键是**连字符**的 `bit-list`（实体里叫 bitList）—— 前端 ModbusServiceCodec
        // 就是按这个键解的，所以这里必须钉住线上拼写，不能跟着实体字段名走
        assertEquals("进水阀", field.getJsonArray("bit-list").getJsonObject(0).getString("field"));
    }

    // ===== helpers =====

    private static DeviceEntity device(String did, String type, boolean online) {
        DeviceEntity d = new DeviceEntity();
        d.did = did;
        d.type = type;
        d.online = online;
        return d;
    }

    private static ModbusService service() {
        ModbusServiceFieldBit bit = new ModbusServiceFieldBit();
        bit.offset = 0;
        bit.field = "进水阀";

        ModbusServiceField field = new ModbusServiceField();
        field.index = 1;
        field.field = "温度";
        field.format = "int16";
        field.unit = "℃";
        field.bitList = List.of(bit);

        ModbusServiceFunction function = new ModbusServiceFunction();
        function.index = 1;
        function.name = "读温度";
        function.response = List.of(field);

        ModbusService service = new ModbusService();
        service.id = new ObjectId("6512f3a4b5c6d7e8f9a0b1c5");
        service.name = "1 号冷水机组";
        service.device = new DeviceOn();
        service.functions = List.of(function);
        return service;
    }
}
