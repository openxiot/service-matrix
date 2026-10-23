package cc.openxiot.matrix.api.modbus.poll;

import cc.openxiot.matrix.db.modbus.poll.ModbusPolling;
import org.bson.types.ObjectId;

/**
 * 调度循环内存里的一个任务：{@code modbus.polls} 一行 + 调度要用的归属。
 *
 * <p>与实体 {@link ModbusPolling} 分开，是因为两者的字段口径本来就不同：实体是**持久化状态**
 * （含上次结果、连续失败次数这些只给前端看的字段），这里是**内存调度状态**（含 {@link #ownerId} 这种
 * 每次对账才解析一次、不该落库的字段）。合成一个类就得在实体上挂 transients，
 * 而 transients 恰恰是「同一份数据两种生命周期」最容易出错的地方。</p>
 *
 * <p>{@link #intervalSeconds} / {@link #ownerId} / {@link #id} 都是**对账同步来的**：
 * 定义改了、归属换了、库里的行被删了又插（换了个 {@code _id}），改的都是这个对象，
 * 而不是换一个对象进轮 —— 在飞的那一帧不能换对象（见 {@link ModbusPollWheel#offer}）。</p>
 *
 * <p><b>只有调度线程改这些字段</b>（{@code ModbusPollReconciler} / {@code ModbusPollScheduler}
 * 都跑在同一个线程上），工作线程只读，靠 {@code ExecutorService.execute} 的 happens-before 拿到值。
 * 因此这里不加 volatile、也不加锁，见 {@link ModbusPollWheel} 的类注释。</p>
 *
 * <p>{@link #id} <b>必须</b>在放进时间轮时就有值：抢租约、写状态都只认它，没有它的任务
 * 只能每周期空转（见 {@code ModbusPollScheduler#claim}）。它由对账从两处之一取：
 * 库里已有这一行时取**行上的 {@code _id}**，否则取计划预生成的那个 —— 一律不从计划对象上直接读
 * （计划对象在「库里已经有这一行」时根本没有 id）。</p>
 */
public final class ModbusPollTask {

    /**
     * {@code modbus.polls} 里这个键当前那一行的 {@code _id}；未落库时为 null。
     *
     * <p>**跟着对账走**（不是 final）：库里那一行可能被删掉又插回来（关掉轮询再打开、
     * 方法序号被删了又加），换行就换了 {@code _id} —— 停在旧的 {@code _id} 上的话，
     * 认领会一直「抢不到」，表现为这个任务静默地一次都不采（见 {@link ModbusPollWheel#offer}）。</p>
     */
    public ObjectId id;

    public final String serviceId;

    public final Integer transactionIndex;

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

    public ModbusPollTask(ObjectId id, String serviceId, Integer transactionIndex, String did) {
        this.id = id;
        this.serviceId = serviceId;
        this.transactionIndex = transactionIndex;
        this.did = did;
    }

    /**
     * 从计划里的一条建内存任务：{@code id} / {@code ownerId} 都由对账解析后传入
     * （{@code nextRunAt} 缺失当 0 = 立刻到期）。
     *
     * <p>{@code id} 显式传而不从 {@code poll.id} 读：{@code poll} 是**计划**对象
     * （{@code ModbusPollPlan.tasksOf} 现算的），库里已经有这一行时它身上没有 id，
     * 而权威的那个 id 在库里那一行上。</p>
     */
    public static ModbusPollTask of(ModbusPolling poll, ObjectId id, String ownerId) {
        ModbusPollTask task = new ModbusPollTask(id, poll.serviceId, poll.transactionIndex, poll.did);
        task.intervalSeconds = poll.interval == null ? 0 : poll.interval;
        task.ownerId = ownerId;
        task.nextRunAt = poll.nextRunAt == null ? 0L : poll.nextRunAt.getTime();
        return task;
    }

    public ModbusPollKey key() {
        return new ModbusPollKey(serviceId, transactionIndex);
    }

    @Override
    public String toString() {
        return key() + "@" + did;
    }
}
