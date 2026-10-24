package cc.openxiot.matrix.api.modbus.polling;

import java.util.Date;
import java.util.Map;

/**
 * 一次采集的**内存比对基准**（已停写的 {@code service-last-polling} 的影子，只活在内存里）。
 *
 * <p>它承接旧 {@code ModbusServiceLastPolling} 在「变位/死区判定、恢复判定、故障去重」里的角色，
 * 字段逐字相同（值映射 + 时刻 + 故障消息），但**不再落库**：轮询器经 {@code ModbusPollingBaselines} 缓存它，
 * 手动 invoke 从逐寄存器影子（{@code ModbusShadow}）重建它；落库一面已全走逐寄存器
 * （RegistryHistory + Shadow）。</p>
 *
 * <p>{@code lastError} 非空 = 上次调用失败（此时 {@code valueByField}/{@code recordedAt} 仍是
 * 最后一次**成功**采集的值与时刻）。失败态基准**不可用**：轮询器要见故障态才能让恢复后的第一次
 * 成功强制落全字段（与旧 {@code ModbusPollingBaselines.usable} 同口径）。</p>
 *
 * @param valueByField 上一次落库的「字段（=寄存器名）→ 值」；没有影子时传 null（⇒ 首次采集）
 * @param recordedAt   上次落库时刻；没有影子时传 null
 * @param lastError    上一次调用的失败消息；null = 上次成功
 */
public record ModbusPollingBaseline(
        Map<String, Object> valueByField,
        Date recordedAt,
        String lastError
) {
    /** 失败态的基准**不可用**：轮询器平时不吃它（回了到读库），恢复后才见故障态。 */
    public boolean usable() {
        return lastError == null;
    }
}