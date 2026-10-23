package cc.openxiot.matrix.db.modbus.alarm.codec;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarm;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusAlarmCodec} 的对外形状。
 *
 * <p>纯函数，不需要数据库（{@code @QuarkusTest} 要连 Mongo）。</p>
 */
class ModbusAlarmCodecTest {

    private static final Date AT = new Date(1_700_000_000_000L);

    // ===== 明细 =====

    @Test
    void encodesAFullRow() {
        ModbusServiceAlarm alarm = open("进水温度", 85.0);
        alarm.id = new ObjectId("65f0c0ffee0123456789abcd");
        alarm.recoveredAt = new Date(1_700_000_600_000L);
        alarm.closeType = ModbusServiceAlarm.CLOSE_VALUE;
        alarm.handled = true;
        alarm.handledBy = person("u-1", "张三");

        JsonObject o = ModbusAlarmCodec.encode(alarm);

        // 每条都带 id：告警行有逐行动作（点「处理」），前端得知道点的是哪一条
        assertEquals("65f0c0ffee0123456789abcd", o.getString("id"));
        assertEquals("svc-1", o.getString("serviceId"));
        assertEquals(2, o.getInteger("transactionIndex").intValue());
        assertEquals("进水温度", o.getString("field"));
        // 触发那一刻的定义快照
        assertEquals(">", o.getString("compare"));
        assertEquals(80.0, o.getDouble("threshold").doubleValue());
        assertEquals("WARN", o.getString("level"));
        assertEquals("温度过高", o.getString("text"));
        assertEquals("℃", o.getString("unit"));
        // 时刻一律 epoch 毫秒
        assertEquals(1_700_000_000_000L, o.getLong("at").longValue());
        assertEquals(1_700_000_600_000L, o.getLong("recoveredAt").longValue());
        assertEquals("VALUE", o.getString("closeType"));
        assertEquals(Boolean.TRUE, o.getBoolean("handled"));
        // handledBy 走既有的 PersonCodec：只有 id / name / timestamp
        JsonObject who = o.getJsonObject("handledBy");
        assertEquals(3, who.size());
        assertEquals("u-1", who.getString("id"));
        assertEquals("张三", who.getString("name"));
        assertEquals(1_700_000_000_000L, who.getLong("timestamp").longValue());
        // 样本原样出去
        assertEquals(85.0, o.getDouble("sample").doubleValue());
    }

    @Test
    void openKeyIsNeverEncoded() {
        ModbusServiceAlarm alarm = open("进水温度", 85.0);
        assertEquals("svc-1#2#进水温度", alarm.openKey);   // 行内确实带着它

        JsonObject o = ModbusAlarmCodec.encode(alarm);
        // 但它是并发护栏用的内部字段，不下发：把 key 的拼法泄漏成接口契约以后就改不动了
        assertFalse(o.containsKey("openKey"));
        // 「是否已恢复」对外的表达是 recoveredAt，不是 openKey
        assertFalse(o.containsKey("recoveredAt"));
    }

    @Test
    void nullsAreOmittedNotWrittenAsNull() {
        // 没配过的东西不该凭空出现（与全仓 Codec 的「null 不出键」同口径）
        JsonObject bare = ModbusAlarmCodec.encode(new ModbusServiceAlarm());
        assertTrue(bare.isEmpty());

        ModbusServiceAlarm alarm = new ModbusServiceAlarm();
        alarm.serviceId = "svc-1";
        alarm.field = "进水温度";
        alarm.at = AT;
        JsonObject o = ModbusAlarmCodec.encode(alarm);
        assertEquals(3, o.size());
        for (String absent : List.of("id", "transactionIndex", "compare", "threshold", "state", "level",
                "text", "unit", "recoveredAt", "closeType", "handled", "handledBy", "sample", "openKey")) {
            assertFalse(o.containsKey(absent), absent + " 不该出键");
        }
    }

    @Test
    void encodesTheValueListStateInsteadOfANumber() {
        // 带 value-list 的字段比的是描述字符串，快照里没有 threshold
        ModbusServiceAlarm alarm = new ModbusServiceAlarm();
        alarm.field = "水泵状态";
        alarm.compare = "=";
        alarm.state = "ON";
        alarm.sample = "ON";

        JsonObject o = ModbusAlarmCodec.encode(alarm);
        assertEquals("=", o.getString("compare"));
        assertEquals("ON", o.getString("state"));
        assertFalse(o.containsKey("threshold"));
        assertEquals("ON", o.getString("sample"));   // 字符串样本原样出去，不翻译
    }

    @Test
    void encodeListKeepsOrder() {
        ModbusServiceAlarm first = open("进水温度", 85.0);
        ModbusServiceAlarm second = open("回水温度", 4.0);

        JsonArray arr = ModbusAlarmCodec.encode(List.of(first, second));
        assertEquals(2, arr.size());
        assertEquals("进水温度", arr.getJsonObject(0).getString("field"));
        assertEquals("回水温度", arr.getJsonObject(1).getString("field"));
        assertTrue(ModbusAlarmCodec.encode((List<ModbusServiceAlarm>) null).isEmpty());
    }

    // ===== 汇总 =====

    @Test
    void summarizesCountsAndTallies() {
        // 三条：两条开着未处理（WARN/CRITICAL 各一，文本不同）、一条已恢复已处理（WARN，同一个文本）
        ModbusServiceAlarm a = open("进水温度", 85.0);
        a.level = "WARN";
        a.text = "温度过高";

        ModbusServiceAlarm b = open("回水温度", 4.0);
        b.level = "CRITICAL";
        b.text = "温度过低";

        ModbusServiceAlarm c = open("进水温度", 20.0);
        c.level = "WARN";
        c.text = "温度过高";
        c.recoveredAt = new Date(1_700_000_600_000L);
        c.closeType = ModbusServiceAlarm.CLOSE_VALUE;
        c.handled = true;
        c.handledBy = person("u-1", "张三");
        c.openKey = null;              // 已恢复：openKey 被 $unset 了

        JsonObject summary = ModbusAlarmCodec.encodeSummary(List.of(a, b, c));

        assertEquals(3, summary.getInteger("total").intValue());
        assertEquals(2, summary.getInteger("open").intValue());        // 只有 a、b 还开着
        assertEquals(2, summary.getInteger("unhandled").intValue());   // 只有 c 处理过了

        // 条数降序、同数按名字升序
        JsonArray byLevel = summary.getJsonArray("byLevel");
        assertEquals(2, byLevel.size());
        assertEquals("WARN", byLevel.getJsonObject(0).getString("level"));
        assertEquals(2, byLevel.getJsonObject(0).getInteger("count").intValue());
        assertEquals("CRITICAL", byLevel.getJsonObject(1).getString("level"));
        assertEquals(1, byLevel.getJsonObject(1).getInteger("count").intValue());
        assertEquals(1_700_000_000_000L, byLevel.getJsonObject(0).getLong("lastAt").longValue());

        JsonArray byText = summary.getJsonArray("byText");
        assertEquals(2, byText.size());
        assertEquals("温度过高", byText.getJsonObject(0).getString("text"));
        assertEquals(2, byText.getJsonObject(0).getInteger("count").intValue());
        assertEquals("温度过低", byText.getJsonObject(1).getString("text"));
    }

    @Test
    void summaryBucketsMissingLevelAndTextWithoutInventingUserData() {
        // level / text 缺失只可能是更早的口径或脏数据（新存的定义被校验器挡住了必填）；
        // 归桶是为了让计数与 total 对得上，不是替用户编一个告警文本
        ModbusServiceAlarm bare = new ModbusServiceAlarm();
        bare.at = AT;

        JsonObject summary = ModbusAlarmCodec.encodeSummary(List.of(bare));
        assertEquals(1, summary.getInteger("total").intValue());
        assertEquals(1, summary.getInteger("open").intValue());       // 没 recoveredAt 就是开着
        assertEquals(1, summary.getInteger("unhandled").intValue());  // handled 缺省 = 未处理
        assertEquals("UNKNOWN", summary.getJsonArray("byLevel").getJsonObject(0).getString("level"));
        assertEquals("UNKNOWN", summary.getJsonArray("byText").getJsonObject(0).getString("text"));
    }

    @Test
    void summaryOfNothingIsAllZeroes() {
        JsonObject summary = ModbusAlarmCodec.encodeSummary(null);
        assertEquals(0, summary.getInteger("total").intValue());
        assertEquals(0, summary.getInteger("open").intValue());
        assertEquals(0, summary.getInteger("unhandled").intValue());
        assertTrue(summary.getJsonArray("byLevel").isEmpty());
        assertTrue(summary.getJsonArray("byText").isEmpty());
        assertTrue(ModbusAlarmCodec.encodeSummary(List.of()).getJsonArray("byLevel").isEmpty());
    }

    // ===== 开行标识 =====

    @Test
    void openKeyFormatIsStable() {
        // 判定（按出值名）与落库（按 key 关闭）靠这个拼法对上；改了它，库里已有的开行就再也关不掉
        assertEquals("svc-1#2#进水温度", ModbusServiceAlarm.openKeyOf("svc-1", 2, "进水温度"));
    }

    // ===== 夹具 =====

    /** 一条未恢复、未处理的告警（字段名与样本给定）。 */
    private static ModbusServiceAlarm open(String field, Object sample) {
        ModbusServiceAlarm alarm = new ModbusServiceAlarm();
        alarm.serviceId = "svc-1";
        alarm.transactionIndex = 2;
        alarm.field = field;
        alarm.compare = ">";
        alarm.threshold = 80.0;
        alarm.level = "WARN";
        alarm.text = "温度过高";
        alarm.unit = "℃";
        alarm.at = AT;
        alarm.handled = false;
        alarm.sample = sample;
        alarm.openKey = ModbusServiceAlarm.openKeyOf("svc-1", 2, field);
        return alarm;
    }

    private static Person person(String id, String name) {
        Person person = new Person();
        person.id = id;
        person.name = name;
        person.timestamp = AT;
        return person;
    }
}
