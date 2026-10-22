package cc.openxiot.matrix.api.modbus.shadow;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Date;

/**
 * 寄存器缓存网格的一行：点表里一个**可读**寄存器 + 它当前缓存的最新值（来自最后一次轮询快照）。
 *
 * <p>这是读侧**投影**，不落库——把 per-method 的 {@code ModbusServiceLastPolling} 快照与点表
 * {@code ModbusRegistry} 归并成「按寄存器」的视图（见 {@code ModbusServiceService.registerCache}）。
 *
 * <p>{@link #status} 三态：
 * {@code current} = 有缓存值且来源方法最后一次调用成功；
 * {@code stale} = 有缓存值（最后已知值）但来源方法最近失败，{@link #lastError}/（errorAt）带上，
 * `fields`/recordedAt 保留的是"值从哪一刻起不再更新"的最后已知值；
 * {@code no-data} = 点表可读但从没被调用过 / 没有对应读方法。</p>
 */
@RegisterForReflection
public class ModbusRegisterSnapshot {

    public static final String STATUS_CURRENT = "current";
    public static final String STATUS_STALE = "stale";
    public static final String STATUS_NO_DATA = "no-data";

    public String name;
    public String area;
    public String dataType;
    public String unit;
    public String access;

    /** 来源读方法：谁负责读这个寄存器（ModbusPolling.index）；null = 无对应读方法。 */
    public Integer pollingIndex;

    /** 缓存值；null = 无缓存（status 为 no-data）。 */
    public Object value;

    /** 这个值最后**成功**落库的时刻；无缓存时为 null。 */
    public Date recordedAt;

    public String status;
    public String lastError;
    public Date errorAt;
}