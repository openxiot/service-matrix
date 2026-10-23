package cc.openxiot.matrix.db.modbus.config.codec;

import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusRegistryAlarm}（一个寄存器的一条阈值告警规则）与 JSON 的互转。
 *
 * <p>守全仓的「null 不出键」：没配的项不写出去，免得往库里写一堆空值；
 * {@link #decode(JsonObject)} 对空对象返回 null（而不是一个字段全空的实例），
 * {@link #decode(JsonArray)} 对空数组返回 null，且**丢掉全空的元素** ——
 * 这样 {@link ModbusRegistryCodec} 才能靠「null / 空就不出键」把「没配」原样带回去，
 * 一个寄存器也不至于在库里留下一串空壳规则。</p>
 */
public final class ModbusRegistryAlarmCodec {

    private ModbusRegistryAlarmCodec() {
    }

    public static JsonObject encode(ModbusRegistryAlarm alarm) {
        JsonObject o = new JsonObject();
        if (alarm == null) {
            return o;
        }
        if (alarm.id != null) {
            o.put("id", alarm.id);
        }
        if (alarm.enabled != null) {
            o.put("enabled", alarm.enabled);
        }
        if (alarm.compare != null) {
            o.put("compare", alarm.compare);
        }
        if (alarm.threshold != null) {
            o.put("threshold", alarm.threshold);
        }
        if (alarm.state != null) {
            o.put("state", alarm.state);
        }
        if (alarm.level != null) {
            o.put("level", alarm.level);
        }
        if (alarm.text != null) {
            o.put("text", alarm.text);
        }
        return o;
    }

    /** 一组规则：**空壳（null / 空元素）不进数组**，与 {@link #decode(JsonObject)} 同一条口径。 */
    public static JsonArray encode(List<ModbusRegistryAlarm> alarms) {
        JsonArray arr = new JsonArray();
        if (alarms != null) {
            for (ModbusRegistryAlarm alarm : alarms) {
                if (alarm != null) {
                    arr.add(encode(alarm));
                }
            }
        }
        return arr;
    }

    public static ModbusRegistryAlarm decode(JsonObject o) {
        if (o == null || o.isEmpty()) {
            return null;
        }
        ModbusRegistryAlarm alarm = new ModbusRegistryAlarm();
        alarm.id = o.getString("id");
        alarm.enabled = o.getBoolean("enabled");
        alarm.compare = o.getString("compare");
        alarm.threshold = o.getDouble("threshold");
        alarm.state = o.getString("state");
        alarm.level = o.getString("level");
        alarm.text = o.getString("text");
        return alarm;
    }

    /**
     * 一组规则。**空数组与「元素全是空对象」都读作 null**（= 没配），
     * 于是调用方那一句「非空才出键」把 {@code []} 也原样消掉，库里不会留下这个键。
     */
    public static List<ModbusRegistryAlarm> decode(JsonArray arr) {
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<ModbusRegistryAlarm> alarms = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            ModbusRegistryAlarm alarm = decode(arr.getJsonObject(i));
            if (alarm != null) {
                alarms.add(alarm);
            }
        }
        return alarms.isEmpty() ? null : alarms;
    }
}