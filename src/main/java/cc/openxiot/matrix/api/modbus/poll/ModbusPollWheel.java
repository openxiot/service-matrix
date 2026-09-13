package cc.openxiot.matrix.api.modbus.poll;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 内存时间轮：按 {@code nextRunAt} 有序的任务表 + 「谁正在飞」的记账。
 *
 * <p><b>只在调度线程上访问，不加同步</b>。两个共享结构（任务的飞行态、内存基准）都只被工作线程回填，
 * 由 {@code ModbusPollScheduler} 用并发容器隔开；这个类本身不跨线程。</p>
 *
 * <p>三条不变量，改这个类之前先读：</p>
 *
 * <ul>
 *   <li><b>{@code nextRunAt} 是 {@link #waiting} 的排序键</b> —— 改它必须先把任务移出时间轮
 *       （{@link #offer} / {@link #finish} 都照这个顺序写）。在树里改排序键会让 {@code TreeSet} 静默失效：
 *       之后删不掉、找不到。</li>
 *   <li><b>「在飞」= 不在时间轮里</b>：{@link #pump} 把到点的任务从时间轮摘出来放进 {@link #inFlight}。
 *       于是同一个任务不可能重入 —— 它要么在轮里等，要么在飞，没有第三种状态。
 *       这比「派发前查一下上次跑完没」可靠：那种检查要读一个由别的线程写的标记。</li>
 *   <li><b>同一个 did 最多一个在飞</b>（同一条 RS485 总线不能并发）。{@link #pump} 靠
 *       {@link #busyDevices} 保证，所以 did 忙的任务**留在时间轮里**等它空 ——
 *       不重排、不丢弃，周期到了就该采，只是得排队（这也是不引入 Clock、不引入队列的原因）。</li>
 * </ul>
 */
public final class ModbusPollWheel {

    private static final Comparator<ModbusPollTask> ORDER = Comparator
            .comparingLong((ModbusPollTask task) -> task.nextRunAt)
            .thenComparing(task -> task.serviceId)
            .thenComparingInt(task -> task.functionIndex);

    /** 等下一次到点的任务，按 {@code nextRunAt} 有序。 */
    private final TreeSet<ModbusPollTask> waiting = new TreeSet<>(ORDER);

    /** 键 → 唯一活着的那个任务对象（在轮里的或在飞的，不在已退休的）。 */
    private final Map<ModbusPollKey, ModbusPollTask> byKey = new HashMap<>();

    /** 已派发、还没回填的任务。 */
    private final Map<ModbusPollKey, ModbusPollTask> inFlight = new HashMap<>();

    /** 正在飞的任务占用的设备（同一 did 串行）。 */
    private final Set<String> busyDevices = new HashSet<>();

    /**
     * 取出这一批该派发的任务：到点 + 设备不忙 + 并发没满。
     *
     * <p>取出来的任务**已经在飞**了，调用方必须对每一个都调 {@link #finish}（正常跑完、失败、
     * 或者租约没抢到）—— 漏一个就等于把一个任务永久挂在飞行态、再也不跑。</p>
     *
     * @param concurrency 并发上限（工作线程池大小）
     */
    public List<ModbusPollTask> pump(long now, int concurrency) {
        List<ModbusPollTask> started = new ArrayList<>();
        Iterator<ModbusPollTask> iterator = waiting.iterator();
        // inFlight 在这一轮里是边取边长的，所以判它就够 —— 再加一次 started.size() 会把上限折半
        while (iterator.hasNext() && inFlight.size() < concurrency) {
            ModbusPollTask task = iterator.next();
            if (!ModbusPollSchedule.isDue(task.nextRunAt, now)) {
                break;      // 有序：后面那些只会更晚
            }
            if (busyDevices.contains(task.did)) {
                continue;   // 同一条总线在忙：留在轮里，等它空
            }
            iterator.remove();
            inFlight.put(task.key(), task);
            busyDevices.add(task.did);
            started.add(task);
        }
        return started;
    }

    /**
     * 一次派发结束（成功 / 失败 / 跳过都走这里）：回填下一次时刻，放回时间轮。
     *
     * <p>对账期间被要求删除的任务（{@link #retired}）在这里离场 —— 不打断已经在飞的那一帧。</p>
     */
    public void finish(ModbusPollTask task, long nextRunAt) {
        inFlight.remove(task.key());
        busyDevices.remove(task.did);
        if (task.retired) {
            byKey.remove(task.key(), task);
            return;
        }
        task.nextRunAt = nextRunAt;
        waiting.add(task);
    }

    /**
     * 对账放进来的任务：新增，或按定义更新已有任务。
     *
     * @param nextRunAt 重算好的下一次时刻（新增 = 从现在起算一个周期；已有 = 按库里的 {@code lastRunAt} 续）
     * @return false = 这个任务正在飞、且它依赖的设备变了，**这一轮不动它**（在飞的那一帧不能换设备），
     *         留给下一次对账
     */
    public boolean offer(ModbusPollTask task, long nextRunAt) {
        ModbusPollKey key = task.key();
        ModbusPollTask live = byKey.get(key);
        if (live == null) {
            task.nextRunAt = nextRunAt;
            byKey.put(key, task);
            waiting.add(task);
            return true;
        }
        if (inFlight.containsKey(key)) {
            if (!live.did.equals(task.did)) {
                return false;
            }
            live.intervalSeconds = task.intervalSeconds;
            live.ownerId = task.ownerId;
            return true;    // nextRunAt 不动：跑完时 finish 会按新周期重排
        }

        waiting.remove(live);   // 先摘再改：nextRunAt 是排序键
        byKey.remove(key);
        if (live.did.equals(task.did)) {
            live.intervalSeconds = task.intervalSeconds;
            live.ownerId = task.ownerId;
            live.nextRunAt = nextRunAt;
            byKey.put(key, live);
            waiting.add(live);
        } else {
            task.nextRunAt = nextRunAt;
            byKey.put(key, task);
            waiting.add(task);
        }
        return true;
    }

    /** 对账要删的任务：在飞的话标记退休（跑完自然离场），否则立刻出轮。 */
    public void remove(ModbusPollKey key) {
        ModbusPollTask task = byKey.get(key);
        if (task == null) {
            return;
        }
        if (inFlight.containsKey(key)) {
            task.retired = true;
            return;
        }
        waiting.remove(task);
        byKey.remove(key);
    }

    /** 停机时清空（在飞的任务不在这里，由调度器自己等）。 */
    public void clear() {
        waiting.clear();
        byKey.clear();
    }

    /** 当前活着的任务的键（在轮里的 + 在飞的）：对账据此找出该摘掉的那些。 */
    public Set<ModbusPollKey> keys() {
        return Set.copyOf(byKey.keySet());
    }

    public int waitingCount() {
        return waiting.size();
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    public int busyDeviceCount() {
        return busyDevices.size();
    }
}
