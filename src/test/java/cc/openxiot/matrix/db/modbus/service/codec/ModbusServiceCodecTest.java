package cc.openxiot.matrix.db.modbus.service.codec;

import cc.openxiot.matrix.api.modbus.service.validator.ModbusServiceValidator;
import cc.openxiot.matrix.db.modbus.service.DeviceOn;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.polling.codec.ModbusPollingCodec;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseField;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseFieldAlarm;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseFieldBit;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.service.polling.response.ModbusPollingResponses;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.codec.ModbusPollingResponseFieldAlarmCodec;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.codec.ModbusPollingResponseFieldCodec;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequest;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequestField;
import cc.openxiot.matrix.db.modbus.service.polling.request.codec.ModbusPollingRequestCodec;
import cc.openxiot.matrix.db.modbus.service.polling.request.codec.ModbusPollingRequestFieldCodec;
import cc.openxiot.matrix.db.space.SpaceOn;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusServiceCodec} 等 Codec 的 JSON 互转：按 modbus-service-example.json 的口径解请求体，
 * 编回去再解一次应完全一致（encode∘decode 稳定），并且实体字段名与对外 JSON 名（如 value-list）对得上。
 */
class ModbusServiceCodecTest {

    /**
     * 与 modbus-service-example.json 同形（方法的 index 按唯一性重排过）。
     *
     * <p>request 是**结构化**的：读方法给 slaveId/fc/start/quantity，写方法给 fields
     * （字段名 / 偏移 / 格式 / 字节序 / 缺省值）。名字里的「读8个线圈」与 quantity 8 一致，
     * 「读开关状态」读一个寄存器（2 字节）故响应字段是 uint16 —— 请求与应答的字节数是对得上的。</p>
     */
    private static final String EXAMPLE = """
            {
              "name": "1 号冷水机组",
              "version": 2,
              "configId": "65f0c0ffee0123456789abcd",
              "device": { "did": "did-1", "siid": 1, "aiid": 1, "argument": 1 },
              "functions": [
                {
                  "index": 1,
                  "from": { "type": "command", "index": 1 },
                  "name": "读进水温度",
                  "request": { "slaveId": 1, "fc": "03", "start": 0, "quantity": 1 },
                  "interval": 5,
                  "polling": true,
                  "response": { "fields": [
                    {
                      "index": 1, "field": "进水温度", "bytes": 2, "format": "uint16",
                      "byteOrder": "ABCD", "scale": 0.1, "unit": "℃",
                      "alarms": [
                        { "id": "a1", "enabled": true, "compare": ">", "threshold": 80, "level": "WARN", "text": "温度过高" },
                        { "id": "a2", "enabled": true, "compare": ">", "threshold": 90, "level": "CRITICAL", "text": "温度严重过高" }
                      ]
                    }
                  ] }
                },
                {
                  "index": 2,
                  "from": { "type": "command", "index": 2 },
                  "name": "读开关状态",
                  "request": { "slaveId": 1, "fc": "03", "start": 1, "quantity": 1 },
                  "response": { "fields": [
                    {
                      "index": 1, "field": "开关状态", "bytes": 2, "format": "uint16",
                      "byteOrder": "ABCD", "scale": 1.0,
                      "value-list": [
                        { "value": 0, "description": "ON" },
                        { "value": 1, "description": "OFF" }
                      ]
                    }
                  ] }
                },
                {
                  "index": 3,
                  "from": { "type": "command", "index": 3 },
                  "name": "读8个线圈",
                  "request": { "slaveId": 1, "fc": "01", "start": 0, "quantity": 8 },
                  "response": { "fields": [
                    {
                      "index": 1, "field": "线圈状态", "bytes": 1, "format": "uint8", "scale": 1.0,
                      "bit-list": [
                        { "offset": 0, "field": "进水阀",
                          "alarms": [
                            { "id": "b1", "enabled": true, "compare": "=", "threshold": 1, "level": "CRITICAL", "text": "进水阀未关闭" }
                          ] },
                        { "offset": 3, "field": "报警" }
                      ]
                    }
                  ] }
                },
                {
                  "index": 4,
                  "from": { "type": "command", "index": 4 },
                  "name": "写设定温度",
                  "request": {
                    "slaveId": 1, "fc": "06", "start": 1,
                    "fields": [ { "index": 1, "field": "设定温度", "format": "int16", "value": 70 } ]
                  }
                },
                {
                  "index": 5,
                  "from": { "type": "command", "index": 5 },
                  "name": "写运行参数",
                  "request": {
                    "slaveId": 1, "fc": "10", "start": 10,
                    "fields": [
                      { "index": 1, "field": "运行模式", "offset": 0, "format": "uint16", "value": 1 },
                      { "index": 2, "field": "设定温度", "offset": 1, "format": "float32",
                        "byteOrder": "ABCD", "value": 7.5 }
                    ]
                  }
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

    /**
     * 文档示例文件（模块根目录下的 modbus-service-example.json）必须真的过得了校验。
     *
     * <p>它是用户复制粘贴的模板，覆盖了 01/03 读与 05/06/0F/10 写各自的字段形态。规则改了却忘了
     * 改它，比没有示例更糟 —— 照着抄一份必然被拒。文件在模块根目录（surefire 的工作目录就是模块目录），
     * 它不在 test classpath 上，所以按路径读。</p>
     */
    @Test
    void documentedExampleFileIsValid() throws Exception {
        String text = Files.readString(Path.of("modbus-service-example.json"));
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(text));

        assertNull(ModbusServiceValidator.validate(service));
        assertEquals(ModbusService.VERSION, service.version.intValue());
        // 四种写方法（05 单线圈 / 06 单寄存器 / 0F 多位 / 10 多寄存器）各一个，读方法四个
        assertEquals(4, service.functions.stream().filter(f -> ModbusPolling.responseFields(f).isEmpty()).count());
    }

    @Test
    void decodesExampleBody() {
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));

        assertEquals("1 号冷水机组", service.name);
        assertEquals(ModbusService.VERSION, service.version.intValue());
        assertEquals("65f0c0ffee0123456789abcd", service.configId);
        assertNull(service.id);      // id 不由请求体决定

        assertEquals("did-1", service.device.did);
        assertEquals(1, service.device.siid.intValue());
        assertEquals(1, service.device.aiid.intValue());
        assertEquals(1, service.device.argument.intValue());

        assertEquals(5, service.functions.size());
        ModbusPolling first = service.functions.get(0);
        assertEquals(1, first.index.intValue());   // 方法身份（modbus.polls.pollingIndex 读它）
        assertEquals("读进水温度", first.name);
        assertEquals(5, first.interval.intValue());   // 服务端自动调用周期（秒）
        assertEquals(Boolean.TRUE, first.polling);    // 自动轮询开关
        assertEquals(1, first.response.fields.size());

        // 请求是结构化的：读方法给 slaveId/fc/start/quantity，没有写字段
        assertEquals(1, first.request.slaveId.intValue());
        assertEquals("03", first.request.fc);
        assertEquals(0, first.request.start.intValue());
        assertEquals(1, first.request.quantity.intValue());
        assertNull(first.request.fields);

        ModbusPollingResponseField field = first.response.fields.get(0);
        assertEquals(1, field.index.intValue());
        assertEquals("进水温度", field.field);
        assertEquals(2, field.bytes.intValue());
        assertEquals("uint16", field.format);
        assertEquals("ABCD", field.byteOrder);
        assertEquals(0.1, field.scale.doubleValue());
        assertEquals("℃", field.unit);
        assertNull(field.valueList);

        // 字段自身的阈值告警：一个出值一组规则，阈值 80 是工程单位（scale 已生效后的值），
        // 数组原序进来就该原序出去（顺序参与同级并列裁决）
        assertEquals(2, field.alarms.size());
        assertEquals("a1", field.alarms.get(0).id);
        assertTrue(field.alarms.get(0).enabled);
        assertEquals(">", field.alarms.get(0).compare);
        assertEquals(80.0, field.alarms.get(0).threshold.doubleValue());
        assertNull(field.alarms.get(0).state);
        assertEquals("WARN", field.alarms.get(0).level);
        assertEquals("温度过高", field.alarms.get(0).text);
        assertEquals("a2", field.alarms.get(1).id);
        assertEquals(90.0, field.alarms.get(1).threshold.doubleValue());
        assertEquals("CRITICAL", field.alarms.get(1).level);
        assertEquals("温度严重过高", field.alarms.get(1).text);

        ModbusPollingResponseField state = service.functions.get(1).response.fields.get(0);
        assertEquals("开关状态", state.field);
        assertEquals("ABCD", state.byteOrder);
        assertEquals(2, state.valueList.size());
        assertEquals(0L, state.valueList.get(0).value.longValue());
        assertEquals("ON", state.valueList.get(0).description);
        assertEquals(1L, state.valueList.get(1).value.longValue());
        assertEquals("OFF", state.valueList.get(1).description);
        assertNull(state.bitList);
        assertNull(state.alarms);      // 没配告警的字段不该带一个空数组（更不该带一个空对象）

        // 01 读位：位清单落在字段上（字段自身出整段位掩码）
        ModbusPollingResponseField coils = service.functions.get(2).response.fields.get(0);
        assertEquals("线圈状态", coils.field);
        assertEquals(2, coils.bitList.size());
        assertEquals(0, coils.bitList.get(0).offset.intValue());
        assertEquals("进水阀", coils.bitList.get(0).field);
        assertEquals(3, coils.bitList.get(1).offset.intValue());
        assertEquals("报警", coils.bitList.get(1).field);

        // 位是独立的结果键（parser 把每个命名位单独放进结果对象），所以告警也配在位上
        assertEquals(1, coils.bitList.get(0).alarms.size());
        assertEquals("b1", coils.bitList.get(0).alarms.get(0).id);
        assertEquals("=", coils.bitList.get(0).alarms.get(0).compare);
        assertEquals(1.0, coils.bitList.get(0).alarms.get(0).threshold.doubleValue());
        assertEquals("CRITICAL", coils.bitList.get(0).alarms.get(0).level);
        assertEquals("进水阀未关闭", coils.bitList.get(0).alarms.get(0).text);
        assertNull(coils.bitList.get(1).alarms);

        // 写方法：数量由字段推出（quantity 不填），响应为空（写应答是请求回显）
        ModbusPollingRequest single = service.functions.get(3).request;
        assertEquals("06", single.fc);
        assertEquals(1, single.start.intValue());
        assertNull(single.quantity);
        assertEquals(1, single.fields.size());
        assertEquals("设定温度", single.fields.get(0).field);
        assertEquals("int16", single.fields.get(0).format);
        assertNull(single.fields.get(0).offset);        // fc 06 帧里没有「第几个」
        assertEquals(70, ((Number) single.fields.get(0).value).intValue());
        assertNull(service.functions.get(3).response);

        ModbusPollingRequest multiple = service.functions.get(4).request;
        assertEquals("10", multiple.fc);
        assertEquals(2, multiple.fields.size());
        assertEquals(0, multiple.fields.get(0).offset.intValue());
        assertEquals(1, multiple.fields.get(1).offset.intValue());   // 紧接上一个 uint16
        assertEquals("ABCD", multiple.fields.get(1).byteOrder);
        // value 是异构的，码器不做归一：float 还是 Double，整数还是整数
        assertEquals(7.5, ((Number) multiple.fields.get(1).value).doubleValue());
        assertEquals(1, ((Number) multiple.fields.get(0).value).intValue());
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
                .getJsonObject("response").getJsonArray("fields").getJsonObject(0);
        JsonArray valueList = state.getJsonArray("value-list");
        assertEquals(2, valueList.size());
        assertEquals("ON", valueList.getJsonObject(0).getString("description"));
        assertEquals(0L, valueList.getJsonObject(0).getLong("value").longValue());

        // 实体里叫 bitList，出去必须是示例文件里的 bit-list
        JsonObject coils = o.getJsonArray("functions").getJsonObject(2)
                .getJsonObject("response").getJsonArray("fields").getJsonObject(0);
        JsonArray bitList = coils.getJsonArray("bit-list");
        assertEquals(2, bitList.size());
        assertEquals(0, bitList.getJsonObject(0).getInteger("offset").intValue());
        assertEquals("进水阀", bitList.getJsonObject(0).getString("field"));
    }

    @Test
    void encodesAlarmOnlyWhenConfigured() {
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        JsonObject o = ModbusServiceCodec.encode(service);

        JsonArray alarms = o.getJsonArray("functions").getJsonObject(0)
                .getJsonObject("response").getJsonArray("fields").getJsonObject(0).getJsonArray("alarms");
        assertEquals(2, alarms.size());
        JsonObject alarm = alarms.getJsonObject(0);
        assertEquals("a1", alarm.getString("id"));   // 规则身份必须原样落库：它是开行的 ruleId 的比对依据
        assertEquals(">", alarm.getString("compare"));
        assertEquals(80.0, alarm.getDouble("threshold").doubleValue());
        assertEquals("WARN", alarm.getString("level"));
        assertEquals("温度过高", alarm.getString("text"));
        assertFalse(alarm.containsKey("state"));   // 没给的项不写出去，免得往库里塞一堆空值

        JsonObject bitAlarm = o.getJsonArray("functions").getJsonObject(2)
                .getJsonObject("response").getJsonArray("fields").getJsonObject(0)
                .getJsonArray("bit-list").getJsonObject(0).getJsonArray("alarms").getJsonObject(0);
        assertEquals("b1", bitAlarm.getString("id"));
        assertEquals("=", bitAlarm.getString("compare"));

        // 没配告警的字段：不该凭空生成一个空数组（也不该是空对象）—— 否则「没配」与「配了个空」就分不清了
        JsonObject unconfigured = o.getJsonArray("functions").getJsonObject(1)
                .getJsonObject("response").getJsonArray("fields").getJsonObject(0);
        assertFalse(unconfigured.containsKey("alarms"));
    }

    @Test
    void emptyOrBogusAlarmArraysDoNotSurviveDecoding() {
        // 空数组、以及「元素全是空对象」的数组，都读作「没配」（null）——
        // 于是那一句「非空才出键」把 [] 原样消掉，库里不会留下这个键，也不会留下一串空壳规则
        ModbusPollingResponseField field = new ModbusPollingResponseField();
        field.field = "进水温度";
        field.alarms = ModbusPollingResponseFieldAlarmCodec.decode(new JsonArray());
        assertNull(field.alarms);

        JsonArray shells = new JsonArray().add(new JsonObject()).add(new JsonObject());
        assertNull(ModbusPollingResponseFieldAlarmCodec.decode(shells));

        ModbusPollingResponseFieldBit bit = new ModbusPollingResponseFieldBit();
        bit.offset = 0;
        bit.field = "进水阀";
        bit.alarms = ModbusPollingResponseFieldAlarmCodec.decode(shells);
        assertNull(bit.alarms);

        // 反过来：空壳规则（null 元素）编进数组时也不该落地
        assertTrue(ModbusPollingResponseFieldAlarmCodec.encode(
                Arrays.asList((ModbusPollingResponseFieldAlarm) null, null)).isEmpty());
    }

    @Test
    void emptyResponseEncodesToNoKey() {
        // 写方法（fc 05/06/0F/10）：应答是请求回显，没有读值 —— 整个 response 键不出现，
        // 而不是留下 `response: {}` 或 `fields: []` 这种空壳
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        service.functions.get(0).response = ModbusPollingResponses.of();

        JsonObject o = ModbusServiceCodec.encode(service);
        assertFalse(o.getJsonArray("functions").getJsonObject(0).containsKey("response"));

        // 反过来：解出来就是 null，和「本来没配」完全同形
        assertNull(ModbusServiceCodec.decode(ModbusServiceCodec.encode(service))
                .functions.get(0).response);
    }

    @Test
    void responseFieldsLiveUnderTheResponseObject() {
        // 对外的 JSON 形状：response 是对象，字段表在 response.fields 下（与 request.fields 对称）
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        JsonObject first = ModbusServiceCodec.encode(service).getJsonArray("functions").getJsonObject(0);

        assertTrue(first.containsKey("response"));
        assertEquals(1, first.getJsonObject("response").getJsonArray("fields").size());
        assertEquals("进水温度", first.getJsonObject("response").getJsonArray("fields")
                .getJsonObject(0).getString("field"));
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
        assertTrue(ModbusPollingCodec.encode((ModbusPolling) null).isEmpty());
        assertTrue(ModbusPollingCodec.encode((List<ModbusPolling>) null).isEmpty());
        assertTrue(ModbusPollingResponseFieldCodec.encode((ModbusPollingResponseField) null).isEmpty());
        assertTrue(ModbusPollingResponseFieldCodec.encode((List<ModbusPollingResponseField>) null).isEmpty());
        assertTrue(ModbusPollingRequestCodec.encode((ModbusPollingRequest) null).isEmpty());
        assertTrue(ModbusPollingRequestFieldCodec.encode((ModbusPollingRequestField) null).isEmpty());
        assertNull(ModbusServiceCodec.decode(null));
        assertNull(ModbusPollingCodec.decode((JsonObject) null));
        assertNull(ModbusPollingCodec.decode((JsonArray) null));
        assertNull(ModbusPollingResponseFieldCodec.decode((JsonObject) null));
        assertNull(ModbusPollingResponseFieldCodec.decode((JsonArray) null));
        assertNull(ModbusPollingRequestCodec.decode(null));
        assertNull(ModbusPollingRequestFieldCodec.decode((JsonObject) null));
        assertNull(ModbusPollingRequestFieldCodec.decode((JsonArray) null));
        assertTrue(ModbusPollingResponseFieldAlarmCodec.encode((ModbusPollingResponseFieldAlarm) null).isEmpty());
        assertTrue(ModbusPollingResponseFieldAlarmCodec.encode((List<ModbusPollingResponseFieldAlarm>) null).isEmpty());
        // 两个重载都要显式转型（decode(null) 本身是有歧义的）
        assertNull(ModbusPollingResponseFieldAlarmCodec.decode((JsonObject) null));
        assertNull(ModbusPollingResponseFieldAlarmCodec.decode((JsonArray) null));
        // 空对象 / 空数组都读作「没配」（而不是一个字段全空的实例 / 一个空列表），这样 encode 才会把键省掉
        assertNull(ModbusPollingResponseFieldAlarmCodec.decode(new JsonObject()));
        assertNull(ModbusPollingResponseFieldAlarmCodec.decode(new JsonArray()));
    }
}
