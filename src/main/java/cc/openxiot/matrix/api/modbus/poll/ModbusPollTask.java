package cc.openxiot.matrix.api.modbus.poll;

import cc.openxiot.matrix.db.modbus.poll.ModbusPoll;
import org.bson.types.ObjectId;

/**
 * 调度循环内存里的一个任务：{@code modbus.polls} 一行 + 调度要用的归属。
 *
 * <p>与实体 {@link ModbusPoll} 分开，是因为两者的字段口径本来就不同：实体是**持久化状态**
 * （含上次结果、连续失败次数这些只给前端看的字段），这里是**内存调度状态**（含 {@link #ownerId} 这种
 * 每次对账才解析一次、不该落库的字段）。合成一个类就得在实体上挂 transients，
 * 而 transients 恰恰是「同一份数据两种生命周期」最容易出错的地方。</p>
 *
 * <p><b>只有调度线程改这些字段</b>（{@code ModbusPollReconciler} / {@code ModbusPollScheduler}
 * 都跑在同一个线程上），工作线程只读，靠 {@code ExecutorService.execute} 的 happens-before 拿到值。
 * 因此这里不加 volatile、也不加锁，见 {@link ModbusPollWheel} 的类注释。</p>
 *
 * <p>{@link #id} 在任务刚被对账算出来时还是 null —— 它要等插入拿到 {@code _id} 才有值，
 * 而抢租约、写状态都靠它。</p>
 */
public final class ModbusPollTask {

    /** {@code modbus.polls} 的 {@code _id}；未落库时为 null。 */
    public final ObjectId id;

    public final String serviceId;

    public final Integer functionIndex;

    /** 父设备 did：同一个 did 下的方法必须串行（同一条 RS485 总线）。**设备换了要整条替换**，故为 final。 */
    public final String did;

    /** 调用周期（秒）—— 与定义同步，定义改了会变。 */
    public int intervalSeconds;

    /** 设备所在**根空间** id（manipulation 的归属）：每次对账解析一次，之后每次采集直接取用。 */
    public String ownerId;

    /** 下一次该跑的时刻（epoch 毫秒）。**它是时间轮的排序键**，改它必须先把它移出时间轮。 */
    public long nextRunAt;

    /** 对账要求删除、但它正在飞：不打断这一帧，跑完不再回轮（见 {@link ModbusPollWheel#finish}）。 */
    boolean retired;

    public ModbusPollTask(ObjectId id, String serviceId, Integer functionIndex, String did) {
        this.id = id;
        this.serviceId = serviceId;
        this.functionIndex = functionIndex;
        this.did = did;
    }

    /** 从库里的一行建内存任务（{@code ownerId} 由对账解析后传入；{@code nextRunAt} 缺失当 0 = 立刻到期）。 */
    public static ModbusPollTask of(ModbusPoll poll, String ownerId) {
        ModbusPollTask task = new ModbusPollTask(poll.id, poll.serviceId, poll.functionIndex, poll.did);
        task.intervalSeconds = poll.interval == null ? 0 : poll.interval;
        task.ownerId = ownerId;
        task.nextRunAt = poll.nextRunAt == null ? 0L : poll.nextRunAt.getTime();
        return task;
    }

    public ModbusPollKey key() {
        return new ModbusPollKey(serviceId, functionIndex);
    }

    @Override
    public String toString() {
        return key() + "@" + did;
    }
}
