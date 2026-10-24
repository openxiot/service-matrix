package cc.openxiot.matrix.api.modbus.polling;

import cc.openxiot.matrix.api.modbus.history.ModbusPollBaseline;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存里的比对基准：{@code (serviceId, pollIndex) → 当前值文档}，省掉每次采集的一次影子读。
 *
 * <p><b>只在 poller 单副本时成立</b>（Modbus-Service-Poller.md 第四节）。多副本时每个副本各记一份，
 * 谁采到谁更新自己那份：另一副本改了值，本副本的基准还是旧的 —— 于是「值没变」的判定出错，
 * 漏记一次变位。**这个前提是部署侧（ACA 副本数）保证的，代码这边只能写明它**（启动日志里会打出实例标识，
 * 见 {@code ModbusPollScheduler}）。</p>
 *
 * <p>手动 {@code invoke} 跑在 api 副本（N 个）上，**不吃内存基准**：它的 {@code baselines} 传 null，
 * 每次读库。这也是「缓存只由调度线程与它的工作线程填充」这条不变量的意义 ——
 * api 副本上这个 Map 永远是空的，所以那条路径自然就是每次读库，不需要额外判断。</p>
 *
 * <p>并发：{@code ConcurrentHashMap} 是必要的 —— 回填发生在工作线程（最多 {@code concurrency} 个）。
 * 同一个键不会有两个线程同时写（一个键只属于一个 did，而同一个 did 的任务是串行的）。</p>
 */
@ApplicationScoped
public class ModbusPollBaselines {

    private final Map<ModbusPollKey, ModbusPollBaseline> cache = new ConcurrentHashMap<>();

    /**
     * 能不能吃内存基准：**故障态的不能吃**。
     *
     * <p>故障时 {@code valueByField} / {@code recordedAt} 保留的是「最后一次成功采集」，
     * 但故障态本身（{@code lastError}）必须让 {@code ModbusHistoryWriteService} 看见 ——
     * 它决定了「恢复后的第一次成功要强制落全字段」。缓存里那份的故障可能已经被 api 副本清掉了
     * （手动 invoke 成功过一次），拿它当基准会把一次新的同类故障并进上一次的账上。</p>
     */
    public static boolean usable(ModbusPollBaseline cached) {
        return cached != null && cached.usable();
    }

    /** 取基准：没有缓存、或缓存不可用（故障态）时返回 null —— 调用方据此回退到读库。 */
    public ModbusPollBaseline get(ModbusPollKey key) {
        if (key == null) {
            return null;
        }
        ModbusPollBaseline cached = cache.get(key);
        return usable(cached) ? cached : null;
    }

    /**
     * 回填新基准。
     *
     * <p>{@code next} 为 null（这次什么都没写、没有新基准）时**保留旧缓存**而不是清空 ——
     * 清空等于下一次采集白读一次库，而它并不会因此变准。</p>
     */
    public void put(ModbusPollKey key, ModbusPollBaseline next) {
        if (key == null || next == null) {
            return;
        }
        cache.put(key, next);
    }

    /** 任务离场时丢掉它的基准（服务被删、方法不再轮询）。 */
    public void forget(ModbusPollKey key) {
        if (key != null) {
            cache.remove(key);
        }
    }

    public int size() {
        return cache.size();
    }
}
