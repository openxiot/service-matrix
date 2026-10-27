package cc.openxiot.matrix.api.modbus.polling;

import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.modbus.fd.poll.ModbusPoll;

/**
 * 轮询任务的身份：{@code (fdid, pollIndex)}，与 {@code modbus.polls} 的唯一索引同口径。
 *
 * <p>做成 record 是为了直接当 {@code HashMap} 的键用 —— 调度循环的内存表（任务、内存基准）
 * 都拿它做键，等值语义必须是结构等值而不是引用等值。</p>
 */
public record ModbusPollingKey(String fdid, Integer pollIndex) {

    /** 从定义文档与其中的一个方法取出键；服务还没落库（没有 id）时返回 null。 */
    public static ModbusPollingKey of(FieldDevice fd, ModbusPoll poll) {
        if (fd == null || fd.id == null || poll == null) {
            return null;
        }
        return new ModbusPollingKey(fd.id.toHexString(), poll.index);
    }

    /** 日志与内存指标里用：{@code 6512f3...:1}。 */
    @Override
    public String toString() {
        return fdid + ":" + pollIndex;
    }
}
