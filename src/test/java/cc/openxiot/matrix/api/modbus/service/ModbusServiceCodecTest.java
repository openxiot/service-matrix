package cc.openxiot.matrix.api.modbus.service;

import cc.openxiot.matrix.db.modbus.service.DeviceOn;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import cc.openxiot.matrix.db.space.SpaceOn;
import cc.openxiot.matrix.db.modbus.service.codec.ModbusServiceCodec;
import cc.openxiot.matrix.db.modbus.service.codec.ModbusServiceFieldCodec;
import cc.openxiot.matrix.db.modbus.service.codec.ModbusServiceFunctionCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusServiceCodec} 等 Codec 的 JSON 互转：按 modbus-service-example.json 的口径解请求体，
 * 编回去再解一次应完全一致（encode∘decode 稳定），并且实体字段名与对外 JSON 名（如 value-list）对得上。
 */
class ModbusServiceCodecTest {

    /** 与 modbus-service-example.json 同形（第二个方法的 index 按唯一性改成 2，请求帧换成可解析的真实帧）。 */
    private static final String EXAMPLE = """
            {
              "name": "1 号冷水机组",
              "version": 1,
              "configId": "65f0c0ffee0123456789abcd",
              "device": { "did": "did-1", "siid": 1, "aiid": 1, "argument": 1 },
              "functions": [
                {
                  "index": 1,
                  "name": "读进水温度",
                  "request": "010300000001840A",
                  "interval": 5,
                  "polling": true,
                  "response": [
                    {
                      "index": 1, "field": "进水温度", "bytes": 2, "format": "uint16",
                      "byteOrder": "ABCD", "scale": 0.1, "unit": "℃"
                    }
                  ]
                },
                {
                  "index": 2,
                  "name": "读开关状态",
                  "request": "010300010001D5CA",
                  "response": [
                    {
                      "index": 1, "field": "开关状态", "bytes": 1, "format": "uint8", "scale": 1.0,
                      "value-list": [
                        { "value": 0, "description": "ON" },
                        { "value": 1, "description": "OFF" }
                      ]
                    }
                  ]
                },
                {
                  "index": 3,
                  "name": "读8个线圈",
                  "request": "0101000000083DCC",
                  "response": [
                    {
                      "index": 1, "field": "线圈状态", "bytes": 1, "format": "uint8", "scale": 1.0,
                      "bit-list": [
                        { "offset": 0, "field": "进水阀" },
                        { "offset": 3, "field": "报警" }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

    /** 依赖设备落点的夹具（投影 / 精简视图都只认 spaceId）。 */
    private static SpaceOn space(String spaceId, String rootId) {
        SpaceOn space = new SpaceOn();
        space.spaceId = spaceId;
        space.rootId = rootId;
        return space;
    }

    @Test
    void decodesExampleBody() {
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));

        assertEquals("1 号冷水机组", service.name);
        assertEquals(1, service.version.intValue());
        assertEquals("65f0c0ffee0123456789abcd", service.configId);
        assertNull(service.id);      // id 不由请求体决定

        assertEquals("did-1", service.device.did);
        assertEquals(1, service.device.siid.intValue());
        assertEquals(1, service.device.aiid.intValue());
        assertEquals(1, service.device.argument.intValue());

        assertEquals(3, service.functions.size());
        ModbusServiceFunction first = service.functions.get(0);
        assertEquals(1, first.index.intValue());
        assertEquals("读进水温度", first.name);
        assertEquals("010300000001840A", first.request);
        assertEquals(5, first.interval.intValue());   // 服务端自动调用周期（秒）
        assertEquals(Boolean.TRUE, first.polling);    // 自动轮询开关
        assertEquals(1, first.response.size());

        ModbusServiceField field = first.response.get(0);
        assertEquals(1, field.index.intValue());
        assertEquals("进水温度", field.field);
        assertEquals(2, field.bytes.intValue());
        assertEquals("uint16", field.format);
        assertEquals("ABCD", field.byteOrder);
        assertEquals(0.1, field.scale.doubleValue());
        assertEquals("℃", field.unit);
        assertNull(field.valueList);

        ModbusServiceField state = service.functions.get(1).response.get(0);
        assertEquals("开关状态", state.field);
        assertNull(state.byteOrder);
        assertEquals(2, state.valueList.size());
        assertEquals(0L, state.valueList.get(0).value.longValue());
        assertEquals("ON", state.valueList.get(0).description);
        assertEquals(1L, state.valueList.get(1).value.longValue());
        assertEquals("OFF", state.valueList.get(1).description);
        assertNull(state.bitList);

        // 01 读位：位清单落在字段上（字段自身出整段位掩码）
        ModbusServiceField coils = service.functions.get(2).response.get(0);
        assertEquals("线圈状态", coils.field);
        assertEquals(2, coils.bitList.size());
        assertEquals(0, coils.bitList.get(0).offset.intValue());
        assertEquals("进水阀", coils.bitList.get(0).field);
        assertEquals(3, coils.bitList.get(1).offset.intValue());
        assertEquals("报警", coils.bitList.get(1).field);
    }

    @Test
    void encodeDecodeRoundTripIsStable() {
        JsonObject once = ModbusServiceCodec.encode(ModbusServiceCodec.decode(new JsonObject(EXAMPLE)));
        JsonObject twice = ModbusServiceCodec.encode(ModbusServiceCodec.decode(once));

        assertEquals(once, twice);
        // 请求体里没有的字段不该凭空出现
        assertEquals("1 号冷水机组", once.getString("name"));
        assertNull(once.getString("id"));
    }

    @Test
    void encodeWritesHexIdAndValueListKey() {
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        service.id = new ObjectId("65f0c0ffee0123456789abcd");

        JsonObject o = ModbusServiceCodec.encode(service);
        // 主键对外一律十六进制字符串（URL 路径参数 / invoke 请求体同口径）
        assertEquals("65f0c0ffee0123456789abcd", o.getString("id"));

        // 实体里叫 valueList，出去必须是示例文件里的 value-list
        JsonObject state = o.getJsonArray("functions").getJsonObject(1)
                .getJsonArray("response").getJsonObject(0);
        JsonArray valueList = state.getJsonArray("value-list");
        assertEquals(2, valueList.size());
        assertEquals("ON", valueList.getJsonObject(0).getString("description"));
        assertEquals(0L, valueList.getJsonObject(0).getLong("value").longValue());

        // 实体里叫 bitList，出去必须是示例文件里的 bit-list
        JsonObject coils = o.getJsonArray("functions").getJsonObject(2)
                .getJsonArray("response").getJsonObject(0);
        JsonArray bitList = coils.getJsonArray("bit-list");
        assertEquals(2, bitList.size());
        assertEquals(0, bitList.getJsonObject(0).getInteger("offset").intValue());
        assertEquals("进水阀", bitList.getJsonObject(0).getString("field"));
    }

    @Test
    void emptyResponseEncodesToEmptyArray() {
        // 写方法（fc 05/06/0F/10）：应答是请求回显，没有读值，response 是空数组
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        service.functions.get(0).response = List.of();

        JsonObject o = ModbusServiceCodec.encode(service);
        assertTrue(o.getJsonArray("functions").getJsonObject(0).getJsonArray("response").isEmpty());
    }

    @Test
    void encodeListKeepsOrder() {
        ModbusService one = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        ModbusService two = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        two.name = "2 号冷水机组";

        JsonArray arr = ModbusServiceCodec.encode(List.of(one, two));
        assertEquals(2, arr.size());
        assertEquals("1 号冷水机组", arr.getJsonObject(0).getString("name"));
        assertEquals("2 号冷水机组", arr.getJsonObject(1).getString("name"));
    }

    @Test
    void encodeBriefKeepsOnlyListFields() {
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        service.id = new ObjectId("65f0c0ffee0123456789abcd");
        service.device.space = space("space-1", "root-1");

        JsonObject o = ModbusServiceCodec.encodeBrief(service);
        // 空间图这类清单只认这五个字段：整份定义（functions / response）不进图
        assertEquals("65f0c0ffee0123456789abcd", o.getString("id"));
        assertEquals("1 号冷水机组", o.getString("name"));
        assertEquals("modbus", o.getString("type"));
        assertEquals("did-1", o.getString("did"));
        assertEquals("space-1", o.getString("spaceId"));
        assertEquals(5, o.size());

        JsonArray arr = ModbusServiceCodec.encodeBrief(List.of(service));
        assertEquals(1, arr.size());
        assertEquals("modbus", arr.getJsonObject(0).getString("type"));

        // 主键没生成 / 名字为空 / 没有依赖设备时不硬塞 null
        JsonObject bare = ModbusServiceCodec.encodeBrief(new ModbusService());
        assertEquals("modbus", bare.getString("type"));
        assertEquals(1, bare.size());

        // 精简查询投影只带 id / name / did / spaceId，space 子文档没有 rootId
        ModbusService projected = new ModbusService();
        projected.id = new ObjectId("65f0c0ffee0123456789abcd");
        projected.name = "1 号冷水机组";
        projected.device = new DeviceOn();
        projected.device.did = "did-1";
        projected.device.space = new SpaceOn();
        projected.device.space.spaceId = "space-1";
        JsonObject brief = ModbusServiceCodec.encodeBrief(projected);
        assertEquals(5, brief.size());
        assertEquals("did-1", brief.getString("did"));
        assertEquals("space-1", brief.getString("spaceId"));
    }

    @Test
    void nullsEncodeToEmptyContainers() {
        assertTrue(ModbusServiceCodec.encodeBrief((ModbusService) null).isEmpty());
        assertTrue(ModbusServiceCodec.encodeBrief((List<ModbusService>) null).isEmpty());
        assertTrue(ModbusServiceCodec.encode((ModbusService) null).isEmpty());
        assertTrue(ModbusServiceCodec.encode((List<ModbusService>) null).isEmpty());
        assertTrue(ModbusServiceFunctionCodec.encode((ModbusServiceFunction) null).isEmpty());
        assertTrue(ModbusServiceFunctionCodec.encode((List<ModbusServiceFunction>) null).isEmpty());
        assertTrue(ModbusServiceFieldCodec.encode((ModbusServiceField) null).isEmpty());
        assertTrue(ModbusServiceFieldCodec.encode((List<ModbusServiceField>) null).isEmpty());
        assertNull(ModbusServiceCodec.decode(null));
        assertNull(ModbusServiceFunctionCodec.decode((JsonObject) null));
        assertNull(ModbusServiceFunctionCodec.decode((JsonArray) null));
        assertNull(ModbusServiceFieldCodec.decode((JsonObject) null));
        assertNull(ModbusServiceFieldCodec.decode((JsonArray) null));
    }
}
