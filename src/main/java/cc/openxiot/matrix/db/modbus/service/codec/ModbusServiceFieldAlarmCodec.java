package cc.openxiot.matrix.db.modbus.service.codec;

import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldAlarm;
import io.vertx.core.json.JsonObject;

/**
 * {@link ModbusServiceFieldAlarm}（出值的阈值告警配置）与 JSON 的互转。
 *
 * <p>守全仓的「null 不出键」：没配的项不写出去，免得往库里写一堆空值；
 * {@link #decode} 对空对象返回 null（而不是一个字段全空的实例），这样
 * {@link ModbusServiceFieldCodec} 才能靠「null 就不出键」把「没配」原样带回去。</p>
 */
public final class ModbusServiceFieldAlarmCodec {

    private ModbusServiceFieldAlarmCodec() {
    }

    public static JsonObject encode(ModbusServiceFieldAlarm alarm) {
        JsonObject o = new JsonObject();
        if (alarm == null) {
            return o;
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

    public static ModbusServiceFieldAlarm decode(JsonObject o) {
        if (o == null || o.isEmpty()) {
            return null;
        }
        ModbusServiceFieldAlarm alarm = new ModbusServiceFieldAlarm();
        alarm.enabled = o.getBoolean("enabled");
        alarm.compare = o.getString("compare");
        alarm.threshold = o.getDouble("threshold");
        alarm.state = o.getString("state");
        alarm.level = o.getString("level");
        alarm.text = o.getString("text");
        return alarm;
    }
}
