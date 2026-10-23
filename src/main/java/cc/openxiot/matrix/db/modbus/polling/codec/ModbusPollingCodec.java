package cc.openxiot.matrix.db.modbus.polling.codec;

import cc.openxiot.matrix.db.modbus.polling.ModbusPolling;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Date;
import java.util.List;

/**
 * {@link ModbusPolling} → JSON（轮询任务查询接口用）。
 *
 * <p>只出不进：任务表是**派生数据**（口径由服务定义决定，见 {@code ModbusPollPlan}），
 * 不由请求体决定 —— 所以没有 decode。要改任务就去改服务定义，对账会跟着改。</p>
 *
 * <p>null 的字段**不出键**（与 {@code ModbusServiceLastPollingCodec} 同口径）：
 * 前端判「跑过没有」看 {@code lastStatus} 在不在，而不是判一个显式的 null。
 * {@code lastStatus} 只有 {@code OK} / {@code FAILED} 两个值 —— 跳过（设备忙、租约没抢到）
 * 不是状态，它不写这一列。</p>
 */
public final class ModbusPollingCodec {

    private ModbusPollingCodec() {
    }

    public static JsonObject encode(ModbusPolling poll) {
        JsonObject o = new JsonObject();
        if (poll == null) {
            return o;
        }
        if (poll.serviceId != null) {
            o.put("serviceId", poll.serviceId);
        }
        if (poll.transactionIndex != null) {
            o.put("transactionIndex", poll.transactionIndex);
        }
        if (poll.did != null) {
            o.put("did", poll.did);
        }
        if (poll.interval != null) {
            o.put("interval", poll.interval);
        }
        putTime(o, "nextRunAt", poll.nextRunAt);
        if (poll.leaseOwner != null) {
            o.put("leaseOwner", poll.leaseOwner);
        }
        putTime(o, "leaseUntil", poll.leaseUntil);
        putTime(o, "lastRunAt", poll.lastRunAt);
        if (poll.lastStatus != null) {
            o.put("lastStatus", poll.lastStatus);
        }
        if (poll.lastError != null) {
            o.put("lastError", poll.lastError);
        }
        if (poll.failCount != null) {
            o.put("failCount", poll.failCount);
        }
        return o;
    }

    public static JsonArray encode(List<ModbusPolling> polls) {
        JsonArray arr = new JsonArray();
        if (polls != null) {
            for (ModbusPolling poll : polls) {
                arr.add(encode(poll));
            }
        }
        return arr;
    }

    /** 时间一律输出 epoch 毫秒（与历史接口的 {@code at} / {@code recordedAt} 同口径）。 */
    private static void putTime(JsonObject o, String key, Date value) {
        if (value != null) {
            o.put(key, value.getTime());
        }
    }
}
