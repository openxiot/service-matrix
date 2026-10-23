package cc.openxiot.matrix.api.modbus.poll;

import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusTransaction;

/**
 * 轮询任务的身份：{@code (serviceId, pollingIndex)}，与 {@code modbus.polls} 的唯一索引同口径。
 *
 * <p>做成 record 是为了直接当 {@code HashMap} 的键用 —— 调度循环的内存表（任务、内存基准）
 * 都拿它做键，等值语义必须是结构等值而不是引用等值。</p>
 */
public record ModbusPollKey(String serviceId, Integer pollingIndex) {

    /** 从定义文档与其中的一个方法取出键；服务还没落库（没有 id）时返回 null。 */
    public static ModbusPollKey of(ModbusService service, ModbusTransaction function) {
        if (service == null || service.id == null || function == null) {
            return null;
        }
        return new ModbusPollKey(service.id.toHexString(), function.index);
    }

    /** 日志与内存指标里用：{@code 6512f3...:1}。 */
    @Override
    public String toString() {
        return serviceId + ":" + pollingIndex;
    }
}
