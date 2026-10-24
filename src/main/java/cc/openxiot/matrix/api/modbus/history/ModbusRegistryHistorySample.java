package cc.openxiot.matrix.api.modbus.history;

import java.util.Date;

/**
 * 喂给 {@link ModbusRegistryHistoryWindow} 的内存样本：一次区间查询要整形的一「点」，只留窗口化要用的
 * {@link #at} 与 {@link #value}。
 *
 * <p><b>不是 Mongo 实体</b>：不加 {@code @RegisterForReflection}、没有 codec、不落库。它只是
 * {@link ModbusRegistryHistoryWindow}（纯函数、只跑内存）与数据源（{@code ModbusRegistryHistory}）之间
 * 的中间载体 —— 原来用已停写的库实体 {@code ModbusServiceHistory} 充当，把窗口从那套实体里解耦出来。
 * 谁产出它：{@code ModbusRegistryHistoryService#sampleOf} 与 {@code DashboardWidgetDataService#sampleOf}。
 * 谁消费它：{@link ModbusRegistryHistoryWindow} 与 {@link ModbusRegistryHistoryRangeCodec}。</p>
 *
 * <p>值仍是异构的（数值 / {@code value-list} 的 description / 位展开的 0|1）—— 窗口对值只读、不做
 * 类型判断，数值折统计、非数值只留首末值，见 {@link ModbusRegistryHistoryWindow} 的说明。</p>
 */
public class ModbusRegistryHistorySample {

    /** 采样时刻（UTC）：曲线的横轴坐标。 */
    public Date at;

    /** 值：数值，或 value-list 的 description，或位展开的 0|1。 */
    public Object value;

    public ModbusRegistryHistorySample() {
    }

    public ModbusRegistryHistorySample(Date at, Object value) {
        this.at = at;
        this.value = value;
    }
}