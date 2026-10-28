package cc.openxiot.matrix.db.modbus.fd.codec;

import cc.openxiot.matrix.api.modbus.fd.validator.FieldDeviceValidator;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistries;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.modbus.fd.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.fd.poll.request.ModbusRequest;
import cc.openxiot.matrix.db.modbus.fd.poll.response.ModbusResponses;
import cc.openxiot.matrix.db.modbus.config.codec.ModbusRegistryAlarmCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FieldDeviceCodec} 等 Codec 的 JSON 互转：按请求体的口径解，编回去再解一次应完全一致。
 *
 * <p>字段只带 {@code registryIndex}（引用点表寄存器），解码/编码元数据全部由寄存器携带，
 * 故字段上不再有 format/bytes/byteOrder/scale/unit/value-list/bit-list 这些键。</p>
 */
class FieldDeviceCodecTest {

    private static final String EXAMPLE = """
            {
              "name": "1 号冷水机组",
              "version": 3,
              "configSnapshot": {
                "id": "65f0c0ffee0123456789abcd",
                "visibility": "private",
                "slave": { "manufacturer": "特灵", "model": "19XRV", "slaveId": 1, "description": "1 号冷水机组" },
                "registries": [
                  { "name": "进水温度", "index": 1, "address": 0, "area": "holding",
                    "dataType": "uint16", "access": "R", "scale": 0.1, "unit": "℃",
                    "alarms": [
                      { "id": "a1", "enabled": true, "compare": ">", "threshold": 80, "level": "WARN", "text": "温度过高" }
                    ] },
                  { "name": "进水阀", "index": 2, "address": 0, "area": "coil", "access": "RW" },
                  { "name": "开关状态", "index": 3, "address": 2, "area": "holding",
                    "dataType": "uint16", "access": "R", "enumMap": { "0": "ON", "1": "OFF" } },
                  { "name": "设定温度", "index": 4, "address": 10, "area": "holding",
                    "dataType": "int16", "access": "RW" },
                  { "name": "设定温度2", "index": 5, "address": 11, "area": "holding",
                    "dataType": "float32", "access": "RW" },
                  { "name": "运行模式", "index": 6, "address": 13, "area": "holding",
                    "dataType": "uint16", "access": "RW" }
                ],
                "commands": [],
                "lifecycle": "development"
              },
              "device": { "did": "did-1", "siid": 1, "aiid": 1, "argument": 1 },
              "slaveId": 1,
              "polls": [
                {
                  "index": 1,
                  "from": { "type": "command", "index": 1 },
                  "name": "读进水温度",
                  "request": { "slaveId": 1, "fc": "03", "start": 0, "quantity": 1 },
                  "interval": 5,
                  "polling": true,
                  "response": { "fields": [ { "registryIndex": 1 } ] }
                },
                {
                  "index": 2,
                  "from": { "type": "command", "index": 2 },
                  "name": "读开关状态",
                  "request": { "slaveId": 1, "fc": "03", "start": 2, "quantity": 1 },
                  "response": { "fields": [ { "registryIndex": 3 } ] }
                },
                {
                  "index": 3,
                  "from": { "type": "command", "index": 3 },
                  "name": "读进水阀",
                  "request": { "slaveId": 1, "fc": "01", "start": 0, "quantity": 1 },
                  "response": { "fields": [ { "registryIndex": 2 } ] }
                },
                {
                  "index": 4,
                  "from": { "type": "command", "index": 4 },
                  "name": "写设定温度",
                  "request": { "slaveId": 1, "fc": "06", "start": 10,
                    "fields": [ { "registryIndex": 4, "value": 70 } ] }
                },
                {
                  "index": 5,
                  "from": { "type": "command", "index": 5 },
                  "name": "写运行参数",
                  "request": { "slaveId": 1, "fc": "10", "start": 11,
                    "fields": [ { "registryIndex": 5, "value": 7.5 },
                                { "registryIndex": 6, "value": 1 } ] }
                }
              ]
            }
            """;

    @Test
    void decodesExampleBody() {
        FieldDevice fd = FieldDeviceCodec.decode(new JsonObject(EXAMPLE));

        assertEquals("1 号冷水机组", fd.name);
        assertEquals(FieldDevice.VERSION, fd.version.intValue());
        assertEquals("65f0c0ffee0123456789abcd", fd.configSnapshot.id.toHexString());
        assertEquals(6, fd.configSnapshot.registries.size());
        assertEquals("进水温度", fd.configSnapshot.registries.get(0).name);
        assertEquals("did-1", fd.device.did);

        assertEquals(5, fd.polls.size());
        ModbusPoll first = fd.polls.get(0);
        assertEquals("读进水温度", first.name);
        assertEquals(1, first.response.fields.size());
        assertEquals(1, first.response.fields.get(0).registryIndex.intValue());
        assertNull(first.request.fields);

        // 写方法第 4 条：fc 06 单寄存器，字段只引用寄存器 + 缺省值
        assertEquals("06", fd.polls.get(3).request.fc);
        assertNull(fd.polls.get(3).request.quantity);
        assertEquals(1, fd.polls.get(3).request.fields.size());
        assertEquals(4, fd.polls.get(3).request.fields.get(0).registryIndex.intValue());
        assertEquals(70, ((Number) fd.polls.get(3).request.fields.get(0).value).intValue());
        assertNull(fd.polls.get(3).response);

        ModbusRequest multiple = fd.polls.get(4).request;
        assertEquals("10", multiple.fc);
        assertEquals(2, multiple.fields.size());
        assertEquals(5, multiple.fields.get(0).registryIndex.intValue());
        assertEquals(6, multiple.fields.get(1).registryIndex.intValue());
        assertEquals(7.5, ((Number) multiple.fields.get(0).value).doubleValue());
        assertEquals(1, ((Number) multiple.fields.get(1).value).intValue());
    }

    @Test
    void decodeReadsRegistriesAlarms() {
        FieldDevice fd = FieldDeviceCodec.decode(new JsonObject(EXAMPLE));
        assertEquals(1, fd.configSnapshot.registries.get(0).alarms.size());
        assertEquals("a1", fd.configSnapshot.registries.get(0).alarms.get(0).id);
    }

    @Test
    void encodeDecodeRoundTripIsStable() {
        JsonObject once = FieldDeviceCodec.encode(FieldDeviceCodec.decode(new JsonObject(EXAMPLE)));
        JsonObject twice = FieldDeviceCodec.encode(FieldDeviceCodec.decode(once));

        assertEquals(once, twice);
        assertEquals("1 号冷水机组", once.getString("name"));
        assertNull(once.getString("id"));
    }

    @Test
    void encodeWritesHexIdAndRegistryRefs() {
        FieldDevice fd = FieldDeviceCodec.decode(new JsonObject(EXAMPLE));
        fd.id = new ObjectId("65f0c0ffee0123456789abcd");

        JsonObject o = FieldDeviceCodec.encode(fd);
        assertEquals("65f0c0ffee0123456789abcd", o.getString("id"));
        // 响应字段出去就是 registryIndex，不带解码元数据
        JsonObject field = o.getJsonArray("polls").getJsonObject(0)
                .getJsonObject("response").getJsonArray("fields").getJsonObject(0);
        assertEquals(1, field.getInteger("registryIndex").intValue());
        assertFalse(field.containsKey("format"));
        assertFalse(field.containsKey("bytes"));
    }

    @Test
    void encodesAlarmOnlyWhenConfigured() {
        FieldDevice fd = FieldDeviceCodec.decode(new JsonObject(EXAMPLE));
        JsonObject o = FieldDeviceCodec.encode(fd);

        JsonArray regs = o.getJsonObject("configSnapshot").getJsonArray("registries");
        JsonArray alarms = regs.getJsonObject(0).getJsonArray("alarms");
        assertEquals(1, alarms.size());
        assertEquals("a1", alarms.getJsonObject(0).getString("id"));
        assertFalse(alarms.getJsonObject(0).containsKey("state"));

        // 没配告警的寄存器没有 alarms 键
        assertFalse(regs.getJsonObject(1).containsKey("alarms"));
    }

    @Test
    void emptyOrBogusAlarmArraysDoNotSurviveDecoding() {
        ModbusRegistry reg = new ModbusRegistry();
        reg.name = "进水温度";
        reg.alarms = ModbusRegistryAlarmCodec.decode(new JsonArray());
        assertNull(reg.alarms);

        JsonArray shells = new JsonArray().add(new JsonObject()).add(new JsonObject());
        assertNull(ModbusRegistryAlarmCodec.decode(shells));

        assertTrue(ModbusRegistryAlarmCodec.encode(
                Arrays.asList((ModbusRegistryAlarm) null, null)).isEmpty());
    }

    @Test
    void emptyResponseEncodesToNoKey() {
        FieldDevice fd = FieldDeviceCodec.decode(new JsonObject(EXAMPLE));
        fd.polls.get(0).response = ModbusResponses.of();

        JsonObject o = FieldDeviceCodec.encode(fd);
        assertFalse(o.getJsonArray("polls").getJsonObject(0).containsKey("response"));
        assertNull(FieldDeviceCodec.decode(FieldDeviceCodec.encode(fd)).polls.get(0).response);
    }

    @Test
    void exampleIsValidatorClean() {
        FieldDevice fd = FieldDeviceCodec.decode(new JsonObject(EXAMPLE));
        assertNull(FieldDeviceValidator.validate(fd));
        assertEquals(FieldDevice.VERSION, fd.version.intValue());
    }
}