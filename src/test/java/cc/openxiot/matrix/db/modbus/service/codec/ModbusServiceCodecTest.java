package cc.openxiot.matrix.db.modbus.service.codec;

import cc.openxiot.matrix.api.modbus.service.validator.ModbusServiceValidator;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import cc.openxiot.matrix.db.modbus.config.codec.ModbusRegistryAlarmCodec;
import cc.openxiot.matrix.db.modbus.service.DeviceOn;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.poll.codec.ModbusPollCodec;
import cc.openxiot.matrix.db.modbus.service.poll.response.field.ModbusResponseField;
import cc.openxiot.matrix.db.modbus.service.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.service.poll.response.ModbusResponses;
import cc.openxiot.matrix.db.modbus.service.poll.response.field.codec.ModbusResponseFieldCodec;
import cc.openxiot.matrix.db.modbus.service.poll.request.ModbusRequest;
import cc.openxiot.matrix.db.modbus.service.poll.request.ModbusRequestField;
import cc.openxiot.matrix.db.modbus.service.poll.request.codec.ModbusRequestCodec;
import cc.openxiot.matrix.db.modbus.service.poll.request.codec.ModbusRequestFieldCodec;
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
              "configSnapshot": {
                "id": "65f0c0ffee0123456789abcd",
                "visibility": "private",
                "slave": { "manufacturer": "特灵", "model": "19XRV", "slaveId": 1, "description": "1 号冷水机组" },
                "registries": [
                  { "name": "进水温度", "index": 1, "address": 0, "area": "holding",
                    "dataType": "uint16", "access": "R", "scale": 0.1, "unit": "℃",
                    "alarms": [
                      { "id": "a1", "enabled": true, "compare": ">", "threshold": 80, "level": "WARN", "text": "温度过高" },
                      { "id": "a2", "enabled": true, "compare": ">", "threshold": 90, "level": "CRITICAL", "text": "温度严重过高" }
                    ] },
                  { "name": "进水阀", "index": 2, "address": 2, "area": "coil", "access": "R",
                    "alarms": [
                      { "id": "b1", "enabled": true, "compare": "=", "threshold": 1, "level": "CRITICAL", "text": "进水阀未关闭" }
                    ] }
                ],
                "commands": [],
                "lifecycle": "development"
              },
              "device": { "did": "did-1", "siid": 1, "aiid": 1, "argument": 1 },
              "polls": [
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
                      "byteOrder": "ABCD", "scale": 0.1, "unit": "℃"
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
                        { "offset": 0, "field": "进水阀" },
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
        assertEquals(4, service.polls.stream().filter(f -> ModbusPoll.responseFields(f).isEmpty()).count());
    }

    @Test
    void decodesExampleBody() {
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));

        assertEquals("1 号冷水机组", service.name);
        assertEquals(ModbusService.VERSION, service.version.intValue());
        // 点表快照：id 是十六进制字符串对外，进实体要还原成 ObjectId
        assertEquals("65f0c0ffee0123456789abcd", service.configSnapshot.id.toHexString());
        assertEquals("特灵", service.configSnapshot.slave.manufacturer);
        assertEquals(2, service.configSnapshot.registries.size());
        assertEquals("进水温度", service.configSnapshot.registries.get(0).name);
        assertNull(service.id);      // id 不由请求体决定

        assertEquals("did-1", service.device.did);
        assertEquals(1, service.device.siid.intValue());
        assertEquals(1, service.device.aiid.intValue());
        assertEquals(1, service.device.argument.intValue());

        assertEquals(5, service.polls.size());
        ModbusPoll first = service.polls.get(0);
        assertEquals(1, first.index.intValue());   // 方法身份（modbus.polls.pollIndex 读它）
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

        ModbusResponseField field = first.response.fields.get(0);
        assertEquals(1, field.index.intValue());
        assertEquals("进水温度", field.field);
        assertEquals(2, field.bytes.intValue());
        assertEquals("uint16", field.format);
        assertEquals("ABCD", field.byteOrder);
        assertEquals(0.1, field.scale.doubleValue());
        assertEquals("℃", field.unit);
        assertNull(field.valueList);
        assertNull(field.bitList);

        // 阈值告警属寄存器（寄存器中心），不再属应答字段 —— 字段上不该有 alarms 键
        ModbusRegistry temp = service.configSnapshot.registries.get(0);
        // 一个出值一组规则，阈值 80 是工程单位（scale 已生效后的值），数组原序进来就该原序出去
        assertEquals(2, temp.alarms.size());
        assertEquals("a1", temp.alarms.get(0).id);
        assertTrue(temp.alarms.get(0).enabled);
        assertEquals(">", temp.alarms.get(0).compare);
        assertEquals(80.0, temp.alarms.get(0).threshold.doubleValue());
        assertNull(temp.alarms.get(0).state);
        assertEquals("WARN", temp.alarms.get(0).level);
        assertEquals("温度过高", temp.alarms.get(0).text);
        assertEquals("a2", temp.alarms.get(1).id);
        assertEquals(90.0, temp.alarms.get(1).threshold.doubleValue());
        assertEquals("CRITICAL", temp.alarms.get(1).level);
        assertEquals("温度严重过高", temp.alarms.get(1).text);

        ModbusResponseField state = service.polls.get(1).response.fields.get(0);
        assertEquals("开关状态", state.field);
        assertEquals("ABCD", state.byteOrder);
        assertEquals(2, state.valueList.size());
        assertEquals(0L, state.valueList.get(0).value.longValue());
        assertEquals("ON", state.valueList.get(0).description);
        assertEquals(1L, state.valueList.get(1).value.longValue());
        assertEquals("OFF", state.valueList.get(1).description);
        assertNull(state.bitList);

        // 01 读位：位清单落在字段上（字段自身出整段位掩码）
        ModbusResponseField coils = service.polls.get(2).response.fields.get(0);
        assertEquals("线圈状态", coils.field);
        assertEquals(2, coils.bitList.size());
        assertEquals(0, coils.bitList.get(0).offset.intValue());
        assertEquals("进水阀", coils.bitList.get(0).field);
        assertEquals(3, coils.bitList.get(1).offset.intValue());
        assertEquals("报警", coils.bitList.get(1).field);

        // 位是独立的结果键（parser 把每个命名位单独放进结果对象），故该位的告警挂在点表里
        // 名为「进水阀」的 coil 寄存器上（寄存器中心），而不是挂在 bit-list 上
        ModbusRegistry valve = service.configSnapshot.registries.get(1);
        assertEquals("进水阀", valve.name);
        assertEquals(1, valve.alarms.size());
        assertEquals("b1", valve.alarms.get(0).id);
        assertEquals("=", valve.alarms.get(0).compare);
        assertEquals(1.0, valve.alarms.get(0).threshold.doubleValue());
        assertEquals("CRITICAL", valve.alarms.get(0).level);
        assertEquals("进水阀未关闭", valve.alarms.get(0).text);

        // 写方法：数量由字段推出（quantity 不填），响应为空（写应答是请求回显）
        ModbusRequest single = service.polls.get(3).request;
        assertEquals("06", single.fc);
        assertEquals(1, single.start.intValue());
        assertNull(single.quantity);
        assertEquals(1, single.fields.size());
        assertEquals("设定温度", single.fields.get(0).field);
        assertEquals("int16", single.fields.get(0).format);
        assertNull(single.fields.get(0).offset);        // fc 06 帧里没有「第几个」
        assertEquals(70, ((Number) single.fields.get(0).value).intValue());
        assertNull(service.polls.get(3).response);

        ModbusRequest multiple = service.polls.get(4).request;
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
        JsonObject state = o.getJsonArray("polls").getJsonObject(1)
                .getJsonObject("response").getJsonArray("fields").getJsonObject(0);
        JsonArray valueList = state.getJsonArray("value-list");
        assertEquals(2, valueList.size());
        assertEquals("ON", valueList.getJsonObject(0).getString("description"));
        assertEquals(0L, valueList.getJsonObject(0).getLong("value").longValue());

        // 实体里叫 bitList，出去必须是示例文件里的 bit-list
        JsonObject coils = o.getJsonArray("polls").getJsonObject(2)
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

        // 告警属寄存器（寄存器中心）：从 configSnapshot.registries[].alarms 出
        JsonArray regs = o.getJsonObject("configSnapshot").getJsonArray("registries");
        JsonArray alarms = regs.getJsonObject(0).getJsonArray("alarms");
        assertEquals(2, alarms.size());
        JsonObject alarm = alarms.getJsonObject(0);
        assertEquals("a1", alarm.getString("id"));   // 规则身份必须原样落库：它是开行的 ruleId 的比对依据
        assertEquals(">", alarm.getString("compare"));
        assertEquals(80.0, alarm.getDouble("threshold").doubleValue());
        assertEquals("WARN", alarm.getString("level"));
        assertEquals("温度过高", alarm.getString("text"));
        assertFalse(alarm.containsKey("state"));   // 没给的项不写出去，免得往库里塞一堆空值

        // 位是独立的结果键：该位的告警挂在名为「进水阀」的寄存器上
        JsonObject bitAlarm = regs.getJsonObject(1).getJsonArray("alarms").getJsonObject(0);
        assertEquals("b1", bitAlarm.getString("id"));
        assertEquals("=", bitAlarm.getString("compare"));

        // 没配告警的字段不再有「alarms」键（空键不出现的行为归 emptyOrBogusAlarmArraysDoNotSurviveDecoding）
        JsonObject firstField = o.getJsonArray("polls").getJsonObject(0)
                .getJsonObject("response").getJsonArray("fields").getJsonObject(0);
        assertFalse(firstField.containsKey("alarms"));
        // 位清单上的位也不再有 alarms 键
        JsonObject bit = o.getJsonArray("polls").getJsonObject(2)
                .getJsonObject("response").getJsonArray("fields").getJsonObject(0)
                .getJsonArray("bit-list").getJsonObject(0);
        assertFalse(bit.containsKey("alarms"));
    }

    @Test
    void emptyOrBogusAlarmArraysDoNotSurviveDecoding() {
        // 空数组、以及「元素全是空对象」的数组，都读作「没配」（null）——
        // 于是那一句「非空才出键」把 [] 原样消掉，库里不会留下这个键，也不会留下一串空壳规则
        ModbusRegistry reg = new ModbusRegistry();
        reg.name = "进水温度";
        reg.alarms = ModbusRegistryAlarmCodec.decode(new JsonArray());
        assertNull(reg.alarms);

        JsonArray shells = new JsonArray().add(new JsonObject()).add(new JsonObject());
        assertNull(ModbusRegistryAlarmCodec.decode(shells));

        ModbusRegistry valve = new ModbusRegistry();
        valve.name = "进水阀";
        valve.alarms = ModbusRegistryAlarmCodec.decode(shells);
        assertNull(valve.alarms);

        // 反过来：空壳规则（null 元素）编进数组时也不该落地
        assertTrue(ModbusRegistryAlarmCodec.encode(
                Arrays.asList((ModbusRegistryAlarm) null, null)).isEmpty());
    }

    @Test
    void emptyResponseEncodesToNoKey() {
        // 写方法（fc 05/06/0F/10）：应答是请求回显，没有读值 —— 整个 response 键不出现，
        // 而不是留下 `response: {}` 或 `fields: []` 这种空壳
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        service.polls.get(0).response = ModbusResponses.of();

        JsonObject o = ModbusServiceCodec.encode(service);
        assertFalse(o.getJsonArray("polls").getJsonObject(0).containsKey("response"));

        // 反过来：解出来就是 null，和「本来没配」完全同形
        assertNull(ModbusServiceCodec.decode(ModbusServiceCodec.encode(service))
                .polls.get(0).response);
    }

    @Test
    void responseFieldsLiveUnderTheResponseObject() {
        // 对外的 JSON 形状：response 是对象，字段表在 response.fields 下（与 request.fields 对称）
        ModbusService service = ModbusServiceCodec.decode(new JsonObject(EXAMPLE));
        JsonObject first = ModbusServiceCodec.encode(service).getJsonArray("polls").getJsonObject(0);

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
        assertTrue(ModbusPollCodec.encode((ModbusPoll) null).isEmpty());
        assertTrue(ModbusPollCodec.encode((List<ModbusPoll>) null).isEmpty());
        assertTrue(ModbusResponseFieldCodec.encode((ModbusResponseField) null).isEmpty());
        assertTrue(ModbusResponseFieldCodec.encode((List<ModbusResponseField>) null).isEmpty());
        assertTrue(ModbusRequestCodec.encode((ModbusRequest) null).isEmpty());
        assertTrue(ModbusRequestFieldCodec.encode((ModbusRequestField) null).isEmpty());
        assertNull(ModbusServiceCodec.decode(null));
        assertNull(ModbusPollCodec.decode((JsonObject) null));
        assertNull(ModbusPollCodec.decode((JsonArray) null));
        assertNull(ModbusResponseFieldCodec.decode((JsonObject) null));
        assertNull(ModbusResponseFieldCodec.decode((JsonArray) null));
        assertNull(ModbusRequestCodec.decode(null));
        assertNull(ModbusRequestFieldCodec.decode((JsonObject) null));
        assertNull(ModbusRequestFieldCodec.decode((JsonArray) null));
        assertTrue(ModbusRegistryAlarmCodec.encode((ModbusRegistryAlarm) null).isEmpty());
        assertTrue(ModbusRegistryAlarmCodec.encode((List<ModbusRegistryAlarm>) null).isEmpty());
        // 两个重载都要显式转型（decode(null) 本身是有歧义的）
        assertNull(ModbusRegistryAlarmCodec.decode((JsonObject) null));
        assertNull(ModbusRegistryAlarmCodec.decode((JsonArray) null));
        // 空对象 / 空数组都读作「没配」（而不是一个字段全空的实例 / 一个空列表），这样 encode 才会把键省掉
        assertNull(ModbusRegistryAlarmCodec.decode(new JsonObject()));
        assertNull(ModbusRegistryAlarmCodec.decode(new JsonArray()));
    }
}
