package cc.openxiot.matrix.api.modbus.service;

import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
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
                }
              ]
            }
            """;

    @Test
    void decodesExampleBody() {
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));

        assertEquals("1 号冷水机组", service.name);
        assertEquals(1, service.version.intValue());
        assertEquals("65f0c0ffee0123456789abcd", service.configId);
        assertNull(service.id);      // id 不由请求体决定
        assertNull(service.orgId);   // org 取 X-Org-Id

        assertEquals("did-1", service.device.did);
        assertEquals(1, service.device.siid.intValue());
        assertEquals(1, service.device.aiid.intValue());
        assertEquals(1, service.device.argument.intValue());

        assertEquals(2, service.functions.size());
        ModbusServiceFunction first = service.functions.get(0);
        assertEquals(1, first.index.intValue());
        assertEquals("读进水温度", first.name);
        assertEquals("010300000001840A", first.request);
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
    }

    @Test
    void encodeDecodeRoundTripIsStable() {
        JsonObject once = ModbusServiceCodec.encode(ModbusServiceCodec.decode(new JsonObject(EXAMPLE)));
        JsonObject twice = ModbusServiceCodec.encode(ModbusServiceCodec.decode(once));

        assertEquals(once, twice);
        // 请求体里没有的字段不该凭空出现
        assertEquals("1 号冷水机组", once.getString("name"));
        assertNull(once.getString("id"));
        assertNull(once.getString("orgId"));
    }

    @Test
    void encodeWritesHexIdAndValueListKey() {
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        service.id = new ObjectId("65f0c0ffee0123456789abcd");
        service.orgId = "org-1";

        JsonObject o = ModbusServiceCodec.encode(service);
        // 主键对外一律十六进制字符串（URL 路径参数 / invoke 请求体同口径）
        assertEquals("65f0c0ffee0123456789abcd", o.getString("id"));
        assertEquals("org-1", o.getString("orgId"));

        // 实体里叫 valueList，出去必须是示例文件里的 value-list
        JsonObject state = o.getJsonArray("functions").getJsonObject(1)
                .getJsonArray("response").getJsonObject(0);
        JsonArray valueList = state.getJsonArray("value-list");
        assertEquals(2, valueList.size());
        assertEquals("ON", valueList.getJsonObject(0).getString("description"));
        assertEquals(0L, valueList.getJsonObject(0).getLong("value").longValue());
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
    void encodeBriefKeepsOnlyIdNameAndType() {
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        service.id = new ObjectId("65f0c0ffee0123456789abcd");
        service.orgId = "org-1";

        JsonObject o = ModbusServiceCodec.encodeBrief(service);
        // 空间图这类清单只认 id / name / type：整份定义（functions / response）不进图
        assertEquals("65f0c0ffee0123456789abcd", o.getString("id"));
        assertEquals("1 号冷水机组", o.getString("name"));
        assertEquals("modbus", o.getString("type"));
        assertEquals(3, o.size());

        JsonArray arr = ModbusServiceCodec.encodeBrief(List.of(service));
        assertEquals(1, arr.size());
        assertEquals("modbus", arr.getJsonObject(0).getString("type"));

        // 主键没生成 / 名字为空时不硬塞 null
        JsonObject bare = ModbusServiceCodec.encodeBrief(new ModbusService());
        assertEquals("modbus", bare.getString("type"));
        assertEquals(1, bare.size());
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
