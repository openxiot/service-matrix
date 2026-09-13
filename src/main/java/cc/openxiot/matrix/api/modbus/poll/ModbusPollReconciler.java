package cc.openxiot.matrix.api.modbus.poll;

import cc.openxiot.matrix.db.modbus.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.poll.ModbusPollRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadowRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.SpaceOn;
import cc.openxiot.matrix.db.space.SpaceRepository;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轮询任务表的对账：让 {@code modbus.polls} 与内存时间轮跟着**服务定义**走
 * （见 Modbus-Service-Poller.md 第三节）。
 *
 * <p>任务表是派生数据：口径完全由定义决定（{@code ModbusPollPlan.shouldPoll}），随时可以从
 * {@code modbus.services} 全量重建。所以对账做成**幂等的重算**，不做增量事件 ——
 * 丢了事件、改错了库、poller 停机几天，都能靠下一次对账修回来。</p>
 *
 * <h2>三种时机，同一个实现</h2>
 *
 * <table border="1">
 *   <tr><th>时机</th><th>挂在哪</th><th>做到哪一步</th></tr>
 *   <tr><td>写入路径</td><td>{@code ModbusServiceService} 的 create / update / delete</td>
 *       <td>{@link #applyService} / {@link #applyRemoval}：**只写库**，再把内存表置脏</td></tr>
 *   <tr><td>启动</td><td>调度器的 {@code StartupEvent}（仅 {@code modbus.poll.enabled=true}）</td>
 *       <td>{@link #reconcileAll}：库 + 内存表</td></tr>
 *   <tr><td>周期</td><td>调度循环的唤醒里按 {@code reconcile-seconds} 计时，或内存表被置脏</td>
 *       <td>{@link #reconcileAll}：库 + 内存表</td></tr>
 * </table>
 *
 * <h2>为什么写入路径不直接改内存表</h2>
 *
 * <p>写入路径跑在 HTTP 线程上（api 副本处理定义维护请求），而内存表只由调度线程改
 * （见 {@link ModbusPollWheel} 的类注释：不加锁）。两个线程同时改同一个 {@code TreeSet} 是数据竞争，
 * 而且这种竞争不会报错、只会让时间轮静默丢掉几个任务。所以写路径只把 {@link #dirty} 置起来，
 * 由下一次唤醒在调度线程上重建 —— 唤醒周期是 {@code modbus.poll.wakeup-millis}（缺省 1 秒），
 * 比人手改定义的往返快得多，等于没有延迟。</p>
 *
 * <h2>写入路径买不到「立即生效」</h2>
 *
 * <p>上面那条只在本进程内成立。定义是在某个 api 副本上改的，**poller 是另一个进程**：
 * 它要等自己的周期对账（缺省 5 分钟）才知道。写入路径对账的意义是让 {@code polls} 表始终是对的
 * （poller 重启即恢复正确），不是跨进程通知。改动生效延迟 ≤ {@code modbus.poll.reconcile-seconds}
 * 是「同代码双部署」这套方案的固有代价。</p>
 */
@ApplicationScoped
public class ModbusPollReconciler {

    /** 空间 → 根空间 id 的解析缓存：归属每次对账解析一次就够（空间几乎不动），别每次采集都查一遍。 */
    private static final int MAX_CACHED_SPACES = 1000;

    @Inject
    ModbusServiceRepository serviceRepo;

    @Inject
    ModbusPollRepository pollRepo;

    @Inject
    ModbusServiceShadowRepository shadowRepo;

    @Inject
    SpaceRepository spaceRepo;

    @Inject
    ModbusPollBaselines baselines;

    @Inject
    Logger logger;

    /** 轮询周期抖动幅度（百分比）。对账算首次时刻要用它，与调度器同一份配置。 */
    @ConfigProperty(name = "modbus.poll.jitter-percent", defaultValue = "10")
    int jitterPercent;

    /** 内存时间轮：本类持有（对账是唯一往里放东西的地方），调度器通过 {@link #wheel()} 取用。 */
    private final ModbusPollWheel wheel = new ModbusPollWheel();

    private final Map<String, String> rootBySpace = new ConcurrentHashMap<>();

    /** 库里的任务表被写入路径改过，内存表已陈旧；由调度线程在下一次唤醒时重建。 */
    private final AtomicBoolean dirty = new AtomicBoolean();

    /** 上一次全量对账的时刻（周期兜底用它计时）。 */
    private final AtomicLong lastReconciledAt = new AtomicLong();

    /** 建索引开关，与点表 / 服务 / 历史共用同一个（见 ModbusServiceService）。 */
    @ConfigProperty(name = "modbus.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    void init(@Observes StartupEvent event) {
        if (!indexEnabled) {
            return;
        }
        pollRepo.ensureIndexes().await().indefinitely();
    }

    // ===== 对外 =====

    /** 内存时间轮（调度器用）。 */
    public ModbusPollWheel wheel() {
        return wheel;
    }

    /** 库被写入路径改过、内存表待重建？ */
    public boolean isDirty() {
        return dirty.get();
    }

    public void clearDirty() {
        dirty.set(false);
    }

    public long lastReconciledAt() {
        return lastReconciledAt.get();
    }

    /**
     * 写入路径：某个服务的定义刚被创建 / 修改，把任务表对齐到它（**只写库**，内存表置脏）。
     *
     * <p>调用方的约定（{@code ModbusServiceService}）：失败只记日志，不让定义维护的操作跟着失败。</p>
     */
    public Uni<Void> applyService(ModbusService service) {
        if (service == null || service.id == null) {
            return Uni.createFrom().voidItem();
        }
        String serviceId = service.id.toHexString();
        long now = System.currentTimeMillis();
        List<ModbusPoll> planned = ModbusPollPlan.tasksOf(service, didOf(service), now,
                jitterPercent, ThreadLocalRandom.current());
        return pollRepo.findByServiceId(serviceId)
                .chain(existing -> sequence(taskWrites(serviceId, planned, existing)))
                .invoke(() -> dirty.set(true))
                .replaceWithVoid();
    }

    /**
     * 删除路径：服务（或依赖设备）被删掉，任务跟着删（**只写库**，内存表置脏）。
     *
     * <p>内存里那几个任务由下一次全量对账摘掉 —— 磁盘上的表已经不含它们了。</p>
     */
    public Uni<Void> applyRemoval(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return pollRepo.deleteByServiceIds(serviceIds)
                .invoke(count -> {
                    if (count != null && count > 0) {
                        logger.infov("modbus poll tasks removed, services: {0}, tasks: {1}", serviceIds, count);
                    }
                })
                .invoke(() -> dirty.set(true))
                .replaceWithVoid();
    }

    /**
     * 启动：先把任务表与内存表对齐，再把影子全量扫一遍。**只在调度线程上调用**（循环起来之前）。
     *
     * <p>影子为什么要单独在启动时全量扫：定义变更必经写入路径，那一层已经对过账了；
     * 剩下唯一能让影子陈旧的是**直接改库**（运维脚本、迁移），启动这次就是兜它的。
     * 不进周期对账 —— 那要扫「服务 × 影子」，成本比它兜住的概率高太多。</p>
     */
    public Uni<Void> startup() {
        return reconcileAll().chain(ignored -> sweepShadows());
    }

    /**
     * 全量对账：库与内存表都刷到与定义一致。**只在调度线程上调用**。
     *
     * <p>稳态下（没有任何定义变更）它只有两次读：全部服务、全部任务；写操作是零 ——
     * 这是「周期对账可以每 5 分钟跑一次」的前提。</p>
     */
    public Uni<Void> reconcileAll() {
        long now = System.currentTimeMillis();
        return serviceRepo.listAll()
                .chain(services -> pollRepo.listAll()
                        .chain(polls -> reconcileAll(services, polls, now)))
                .invoke(() -> lastReconciledAt.set(System.currentTimeMillis()));
    }

    /** 影子全量扫一遍（启动用）：逐个服务按当前定义对账。 */
    private Uni<Void> sweepShadows() {
        return serviceRepo.listAll().chain(services -> {
            List<Uni<Void>> steps = new ArrayList<>(services.size());
            for (ModbusService service : services) {
                steps.add(reconcileShadows(service)
                        .onFailure().invoke(e -> logger.warnv(
                                "sweep modbus shadows failed, service: {0}, error: {1}",
                                service == null ? null : service.id, e.getMessage()))
                        .onFailure().recoverWithNull());
            }
            return sequence(steps);
        });
    }

    /**
     * 影子对账（见 Modbus-Service-Poller.md 第九节）：定义改了以后，影子会留下收不到更新的东西
     * —— 孤儿方法（整条删）与废弃字段键（剪掉）。**只写库**，与内存表无关。
     *
     * <p>只在**定义变更的写入路径 + 启动**做，不进周期对账：那要扫「服务 × 影子」，
     * 而影子的陈旧只可能来自定义变更。</p>
     */
    public Uni<Void> reconcileShadows(ModbusService service) {
        if (service == null || service.id == null) {
            return Uni.createFrom().voidItem();
        }
        String serviceId = service.id.toHexString();
        return shadowRepo.listByServiceId(serviceId).chain(shadows -> {
            List<ModbusShadowPlan.Fix> fixes = ModbusShadowPlan.plan(service, shadows);
            if (fixes.isEmpty()) {
                return Uni.createFrom().voidItem();
            }
            List<Uni<Void>> ops = new ArrayList<>();
            List<ObjectId> orphans = new ArrayList<>();
            int trimmed = 0;
            for (ModbusShadowPlan.Fix fix : fixes) {
                if (fix.orphan()) {
                    orphans.add(fix.shadow().id);
                    continue;
                }
                // fields 是整份替换的（ModbusHistoryService.record）：剪掉废弃键，值/时刻/故障态都留着
                Map<String, Object> kept = new LinkedHashMap<>(fix.shadow().fields);
                fix.staleFields().forEach(kept::remove);
                fix.shadow().fields = kept;
                trimmed++;
                ops.add(shadowRepo.replace(fix.shadow()));
            }
            if (!orphans.isEmpty()) {
                ops.add(shadowRepo.deleteByIds(orphans).replaceWithVoid());
            }
            final int trimmedCount = trimmed;
            return sequence(ops).invoke(() -> logger.infov(
                    "modbus shadows reconciled, service: {0}, orphan functions: {1}, stale fields fixed: {2}",
                    serviceId, orphans.size(), trimmedCount));
        });
    }

    // ===== 全量对账 =====

    private Uni<Void> reconcileAll(List<ModbusService> services, List<ModbusPoll> polls, long now) {
        Set<String> liveServiceIds = new HashSet<>();
        for (ModbusService service : services) {
            if (service != null && service.id != null) {
                liveServiceIds.add(service.id.toHexString());
            }
        }

        Set<ModbusPollKey> plannedKeys = new HashSet<>();
        List<Uni<Void>> steps = new ArrayList<>();
        for (ModbusService service : services) {
            if (service == null || service.id == null) {
                continue;
            }
            // 单个服务对账失败（定义坏了、空间查不到）不该让别的服务跟着不跑 —— 各自兜住
            steps.add(reconcileService(service, polls, plannedKeys, now)
                    .onFailure().invoke(e -> logger.warnv(
                            "reconcile modbus polls failed, service: {0}, error: {1}",
                            service.id, e.getMessage()))
                    .onFailure().recoverWithNull());
        }

        // 服务已经不在了的任务（poller 停机期间被删的、上次对账崩在中途的）：一起收掉
        List<String> deadServiceIds = polls.stream()
                .map(poll -> poll.serviceId)
                .filter(id -> id == null || !liveServiceIds.contains(id))
                .distinct()
                .toList();
        if (!deadServiceIds.isEmpty()) {
            steps.add(pollRepo.deleteByServiceIds(deadServiceIds)
                    .invoke(count -> logger.infov(
                            "modbus poll tasks removed with their services: {0}, tasks: {1}",
                            deadServiceIds, count))
                    .replaceWithVoid());
        }

        // 内存表：计划里没有的摘掉（在飞的先标记退休，跑完自然离场）
        steps.add(Uni.createFrom().voidItem().invoke(() -> pruneWheel(plannedKeys)));
        return sequence(steps);
    }

    private Uni<Void> reconcileService(ModbusService service, List<ModbusPoll> polls,
                                       Set<ModbusPollKey> plannedKeys, long now) {
        String serviceId = service.id.toHexString();
        List<ModbusPoll> planned = ModbusPollPlan.tasksOf(service, didOf(service), now,
                jitterPercent, ThreadLocalRandom.current());
        List<ModbusPoll> existing = polls.stream()
                .filter(poll -> serviceId.equals(poll.serviceId))
                .toList();

        return resolveOwnerId(service).chain(owner -> {
            String ownerId = owner.isEmpty() ? null : owner;
            if (ownerId == null && !planned.isEmpty()) {
                // 不抛异常：把循环带停的代价比这条任务跑不了大得多。任务照样入表，
                // 调度器派发时会因为归属为空而记一次失败（lastError 里能看到原因）
                logger.warnv("modbus poll task has no owner space, service: {0}, did: {1}",
                        serviceId, didOf(service));
            }
            return sequence(taskWrites(serviceId, planned, existing)).chain(() -> {
                List<Uni<Void>> offers = new ArrayList<>(planned.size());
                for (ModbusPoll task : planned) {
                    ModbusPollKey key = new ModbusPollKey(serviceId, task.functionIndex);
                    plannedKeys.add(key);
                    ModbusPollTask memory = ModbusPollTask.of(task, ownerId);
                    ModbusPoll row = rowOf(existing, task.functionIndex);
                    // 库里的 nextRunAt 优先：对账不是重排，别把一次采集往后推
                    long nextRunAt = row != null && row.nextRunAt != null
                            ? row.nextRunAt.getTime()
                            : memory.nextRunAt;
                    offers.add(Uni.createFrom().voidItem().invoke(() -> {
                        if (!wheel.offer(memory, nextRunAt)) {
                            logger.warnv("modbus poll task keeps its device this round, key: {0}", key);
                        }
                    }));
                }
                return sequence(offers);
            });
        });
    }

    /**
     * 库侧的对齐：计划里有、库里没有 → 插；两边都有但调度字段不同 → 改；库里多出来的 → 删。
     *
     * <p>改的时候**不动 {@code nextRunAt}**：那是调度器的字段（派发那一刻才前推）。周期从 10 秒改成
     * 3600 秒时，任务按旧时刻再跑一次、然后按新周期排 —— 比「改完周期立刻重排」更不容易出意外
     * （重排可能把一次采集顶到前面去，RS485 上就是多一帧）。</p>
     */
    private List<Uni<Void>> taskWrites(String serviceId, List<ModbusPoll> planned, List<ModbusPoll> existing) {
        List<Uni<Void>> writes = new ArrayList<>();
        Set<Integer> plannedIndexes = new HashSet<>();
        for (ModbusPoll task : planned) {
            plannedIndexes.add(task.functionIndex);
            ModbusPoll row = rowOf(existing, task.functionIndex);
            if (row == null) {
                writes.add(pollRepo.insert(task));
            } else if (!Objects.equals(row.did, task.did) || !Objects.equals(row.interval, task.interval)) {
                writes.add(pollRepo.updatePlan(row.id, task.did, task.interval, null));
            }
        }
        List<Integer> removed = existing.stream()
                .map(poll -> poll.functionIndex)
                .filter(index -> index != null && !plannedIndexes.contains(index))
                .toList();
        if (!removed.isEmpty()) {
            writes.add(pollRepo.deleteByFunctionIndexes(serviceId, removed)
                    .invoke(count -> logger.infov(
                            "modbus poll tasks removed, service: {0}, functions: {1}, tasks: {2}",
                            serviceId, removed, count))
                    .replaceWithVoid());
        }
        return writes;
    }

    /** 内存表里计划之外的都摘掉（含它们的基准缓存）。 */
    private void pruneWheel(Set<ModbusPollKey> planned) {
        for (ModbusPollKey key : wheel.keys()) {
            if (!planned.contains(key)) {
                wheel.remove(key);
                baselines.forget(key);
            }
        }
    }

    // ===== 归属 =====

    /**
     * 服务依赖的设备所在**根空间** id（manipulation 的 {@code ownerId}）。
     *
     * <p>权威口径是 {@code SpaceEntity.rootSpaceId()}（空间表的祖先链）；空间查不到时退回定义文档上冗余的
     * {@code device.space.rootId}（写服务时从同一个 {@code SpaceEntity} 现取的）。两者都没有返回**空串**
     * —— 调用方当 null 处理（内存表的键不能存 null，所以这里不用 null 表达「没有」）。</p>
     *
     * <p>解析结果按空间 id 缓存（{@code rootBySpace}）：空间几乎不动，一次对账解析一次就够，
     * 不必每次采集都查一遍空间表。</p>
     */
    private Uni<String> resolveOwnerId(ModbusService service) {
        SpaceOn space = service.device == null ? null : service.device.space;
        String spaceId = space == null ? null : space.spaceId;
        String fallback = space == null || space.rootId == null ? "" : space.rootId;
        if (spaceId == null || spaceId.isBlank()) {
            return Uni.createFrom().item(fallback);
        }
        String cached = rootBySpace.get(spaceId);
        if (cached != null) {
            return Uni.createFrom().item(cached);
        }

        Uni<SpaceEntity> found;
        try {
            found = spaceRepo.findById(spaceId);
        } catch (RuntimeException e) {
            // spaceId 不是合法的 ObjectId（历史数据 / 手工改库）：findById 在构造期就抛
            logger.warnv("invalid space id on modbus service, spaceId: {0}", spaceId);
            return Uni.createFrom().item(fallback);
        }
        return found
                .onItem().transform(entity -> entity == null ? "" : nullToEmpty(entity.rootSpaceId()))
                .onFailure().recoverWithItem("")
                .map(root -> {
                    String resolved = root.isEmpty() ? fallback : root;
                    if (!resolved.isEmpty() && rootBySpace.size() < MAX_CACHED_SPACES) {
                        rootBySpace.put(spaceId, resolved);
                    }
                    return resolved;
                });
    }

    // ===== 内部 =====

    private static String didOf(ModbusService service) {
        return service.device == null ? null : service.device.did;
    }

    private static ModbusPoll rowOf(List<ModbusPoll> polls, Integer functionIndex) {
        if (functionIndex == null) {
            return null;
        }
        for (ModbusPoll poll : polls) {
            if (functionIndex.equals(poll.functionIndex)) {
                return poll;
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 顺序执行（对账是「一遍过」的，并发跑这些写操作没有收益，反而让日志难读）。 */
    private static Uni<Void> sequence(List<Uni<Void>> operations) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (Uni<Void> operation : operations) {
            chain = chain.chain(() -> operation);
        }
        return chain;
    }
}
