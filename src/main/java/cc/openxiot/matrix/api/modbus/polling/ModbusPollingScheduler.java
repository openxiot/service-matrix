package cc.openxiot.matrix.api.modbus.polling;

import cc.openxiot.matrix.typedef.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.typedef.modbus.failure.ModbusInvokeException;
import cc.openxiot.matrix.api.modbus.fd.FieldDeviceService;
import cc.openxiot.matrix.db.modbus.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.polling.ModbusPollingRepository;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * 轮询调度循环：到点就把服务方法调一遍（见 Modbus-Service-Poller.md）。
 *
 * <p>同一个 jar 双部署：api N 副本 + poller 1 副本，靠 {@code modbus.poll.enabled}（缺省 **false**）区分。
 * api 副本不碰调度，但一样会维护任务表（写入路径对账）。</p>
 *
 * <h2>线程模型（改动前先读这段）</h2>
 *
 * <ul>
 *   <li><b>调度线程</b>：{@link ScheduledExecutorService} 单线程，{@code scheduleAtFixedRate} 唤醒。
 *       它独占**内存时间轮**（{@link ModbusPollingWheel} 不加锁）与**任务对象的全部字段**——
 *       工作线程只读 {@code ownerId} / {@code fdid} / {@code pollIndex}，
 *       算好的下一次时刻**回填到调度线程**，不直接改时间轮。</li>
 *   <li><b>工作线程池</b>：{@code modbus.poll.concurrency} 个，池大小即并发上限。
 *       时间轮的 {@code pump} 也按同一个数限流，所以池里不会排队。</li>
 *   <li><b>按 did 串行</b>：同一条 RS485 总线上的方法不能并发 —— 由时间轮的 {@code busyDevices} 保证。</li>
 * </ul>
 *
 * <p>为什么不用 {@code vertx.setPeriodic}：它的回调跑在 event loop 上，而调度步要读库
 * （对账、认领租约、写结果），在 event loop 上 {@code await()} 会被 Quarkus 直接拒掉；
 * 轮询也完全不需要 event loop 的低延迟。</p>
 *
 * <h2>一轮的顺序</h2>
 *
 * <ol>
 *   <li>需要的话先全量对账（被写入路径置脏，或到了 {@code reconcile-seconds}）；</li>
 *   <li>从时间轮取到点、且设备不忙的任务；</li>
 *   <li>逐个**认领**（{@code claim}：租约 + 前推 nextRunAt + 记 lastRunAt，一次原子写）；
 *       认领不到 = 别的副本在跑，直接重排，不计失败；</li>
 *   <li>认领到的丢给工作线程：{@code invokePolled} 调一次 → 写回结果状态 → 回填下一次时刻。</li>
 * </ol>
 *
 * <p>任务在「派发那一刻」就已经前推并落库了 {@code nextRunAt}（第 3 步）：一次崩溃不会让任务
 * 按旧的时刻立刻重跑，而只会多等一个周期（见 Modbus-Service-Poller.md 第四节）。</p>
 */
@ApplicationScoped
public class ModbusPollingScheduler {

    @Inject
    ModbusPollingReconciler reconciler;

    @Inject
    ModbusPollingBaselines baselines;

    @Inject
    FieldDeviceService fdService;

    @Inject
    ModbusPollingRepository pollRepo;

    @Inject
    Logger logger;

    /** 调度开关：**缺省关**。一个进程采不采，由部署侧的 env 决定（api 副本不设它）。 */
    @ConfigProperty(name = "modbus.poll.enabled", defaultValue = "false")
    boolean enabled;

    /** 并发上限 = 工作线程池大小。同一条 RS485 上的方法无论如何都是串行的，所以这个数管的是「同时几台设备」。 */
    @ConfigProperty(name = "modbus.poll.concurrency", defaultValue = "20")
    int concurrency;

    /** 唤醒周期（毫秒）：时间轮的推进粒度。等于「到点的任务最多晚这么久被派发」。 */
    @ConfigProperty(name = "modbus.poll.wakeup-millis", defaultValue = "1000")
    long wakeupMillis;

    /** 周期兜底对账的间隔（秒）：跨进程的定义变更生效延迟上限就是它。 */
    @ConfigProperty(name = "modbus.poll.reconcile-seconds", defaultValue = "300")
    long reconcileSeconds;

    @ConfigProperty(name = "modbus.poll.jitter-percent", defaultValue = "10")
    int jitterPercent;

    /** 单次采集的超时（秒）：**必须小于租约时长**，见 {@link #checkTimeoutAgainstLease}。 */
    @ConfigProperty(name = "modbus.poll.invoke-timeout-seconds", defaultValue = "10")
    long invokeTimeoutSeconds;

    /** 租约时长（秒）：持有者在这段时间内独占该任务，过了别人可以抢。 */
    @ConfigProperty(name = "modbus.poll.lease-seconds", defaultValue = "60")
    long leaseSeconds;

    /** 停机时等在飞任务收尾的上限（秒）。 */
    private static final long SHUTDOWN_GRACE_SECONDS = 10;

    /** 实例标识：租约持有者，日志里能看出「谁在采」。 */
    private final String instanceId = UUID.randomUUID().toString();

    private final AtomicInteger inFlight = new AtomicInteger();

    private final AtomicLong lastTickAt = new AtomicLong();

    private final RandomGenerator random = RandomGenerator.getDefault();

    private volatile ScheduledExecutorService ticker;

    private volatile ExecutorService workers;

    void init(@Observes StartupEvent event) {
        if (!enabled) {
            logger.info("modbus poll disabled");
            return;
        }
        checkTimeoutAgainstLease();
        ticker = Executors.newSingleThreadScheduledExecutor(named("modbus-poll-tick"));
        workers = Executors.newFixedThreadPool(Math.max(1, concurrency), named("modbus-poll-worker"));

        // 启动先把任务表与内存表对齐再起循环：否则循环会先看到一张空表
        reconciler.startup().await().indefinitely();
        ticker.scheduleAtFixedRate(this::tick, wakeupMillis, wakeupMillis, TimeUnit.MILLISECONDS);
        logger.infov("modbus poll enabled, instance={0}, concurrency={1}, wakeup={2}ms, timeout={3}s, lease={4}s",
                instanceId, concurrency, wakeupMillis, invokeTimeoutSeconds, leaseSeconds);
    }

    /**
     * 优雅停机：停唤醒 → 等在飞任务收尾（限时）→ 停线程池。
     *
     * <p>不释放租约（{@code ModbusPollingRepository.claim} 的注释写了原因）：被中断的那一帧可能已经在总线上，
     * 让租约自然到期比立刻放给别人重发安全。代价是停机后最多 {@code lease-seconds} 的空窗。</p>
     */
    void stop(@Observes ShutdownEvent event) {
        ScheduledExecutorService currentTicker = ticker;
        ExecutorService currentWorkers = workers;
        if (currentTicker == null || currentWorkers == null) {
            return;
        }
        logger.infov("modbus poll stopping, instance={0}, in-flight={1}", instanceId, inFlight.get());
        currentTicker.shutdownNow();
        currentWorkers.shutdown();
        try {
            if (!currentWorkers.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                currentWorkers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            currentWorkers.shutdownNow();
        }
        logger.info("modbus poll stopped");
    }

    // ===== 观测（查询接口用） =====

    public boolean isEnabled() {
        return enabled;
    }

    public String instanceId() {
        return instanceId;
    }

    public long lastTickAt() {
        return lastTickAt.get();
    }

    public int inFlightCount() {
        return inFlight.get();
    }

    /**
     * 时间轮里等待的任务数。
     *
     * <p><b>近似读数</b>：它在 HTTP 线程上读，而时间轮由调度线程改，取的时候没有加锁
     * （也就可能比真实值早一拍）。它只给查询接口显示用，不参与任何判定 ——
     * 为它给时间轮加锁，等于让每一次采集都去争一把只看不用的锁。</p>
     */
    public int queueSize() {
        return reconciler.wheel().waitingCount();
    }

    /** 正在被占用的设备数（同一个 did 串行）：与 {@link #queueSize()} 同样是近似读数。 */
    public int busyDeviceCount() {
        return reconciler.wheel().busyDeviceCount();
    }

    // ===== 调度 =====

    /**
     * 一次唤醒。**整个方法不能抛**：{@code scheduleAtFixedRate} 遇到未捕获异常会取消后续所有执行，
     * 一次数据库抖动就会静默地让整个轮询停摆。
     */
    private void tick() {
        try {
            long now = System.currentTimeMillis();
            lastTickAt.set(now);

            if (reconciler.isDirty() || now - reconciler.lastReconciledAt() >= reconcileSeconds * 1000L) {
                reconciler.reconcileAll().await().indefinitely();
                reconciler.clearDirty();
                logger.infov("modbus poll reconciled, tasks: {0}, in-flight: {1}",
                        reconciler.wheel().waitingCount(), inFlight.get());
            }
            dispatch(System.currentTimeMillis());
        } catch (Exception e) {
            logger.errorv(e, "modbus poll tick failed");
        }
    }

    /** 到点的任务：认领一个、派发一个。 */
    private void dispatch(long now) {
        List<ModbusPollingTask> due = reconciler.wheel().pump(now, Math.max(1, concurrency));
        for (ModbusPollingTask task : due) {
            try {
                claim(task, now);
            } catch (Exception e) {
                // 单个任务出问题不能把这一轮剩下的都带走（它们已经出轮，必须回填）
                logger.errorv(e, "modbus poll dispatch failed, task: {0}", task.key());
                finish(task, nextRunAt(now, now, task));
            }
        }
    }

    /**
     * 认领并派发。认领前先算出下一次时刻 —— 它要和租约**写在同一行**（见
     * {@code ModbusPollingRepository.claim}），所以这里先算、认领不到就丢掉重算。
     */
    private void claim(ModbusPollingTask task, long now) {
        long nextRunAt = nextRunAt(now, now, task);
        if (task.id == null) {
            // 守卫，不是流程：对账给每个入轮的任务都带上 id（见 ModbusPollingTask.of 的注释）。
            // 真到了这里就是不变式破了 —— 任务一次都跑不了、每周期重复一遍，所以记 error 而不是 warn，
            // 并且**照样放回时间轮**：它会在下一次对账时被重建成带 id 的（丢掉它反而会静默地不采）
            logger.errorv("modbus poll task has no id, key: {0}", task.key());
            finish(task, nextRunAt);
            return;
        }
        ModbusPolling claimed = pollRepo
                .claim(task.id, instanceId, new Date(now), new Date(now + leaseSeconds * 1000L),
                        new Date(nextRunAt))
                .onFailure().invoke(e -> logger.warnv(
                        "claim modbus poll task failed, key: {0}, error: {1}", task.key(), e.getMessage()))
                .onFailure().recoverWithNull()
                .await().indefinitely();
        if (claimed == null) {
            // 别的副本正持有（或库不可用）：当作这一周期没轮到 —— 不计失败、不动 lastStatus，重排等下一轮
            finish(task, nextRunAt);
            return;
        }
        inFlight.incrementAndGet();
        workers.execute(() -> run(task, now));
    }

    /** 工作线程：调一次 → 写结果 → 把下一次时刻回填到调度线程。 */
    private void run(ModbusPollingTask task, long startedAt) {
        Throwable failure = null;
        try {
            invoke(task);
        } catch (Throwable error) {
            failure = error;
        } finally {
            inFlight.decrementAndGet();
            recordOutcome(task, failure);
            long now = System.currentTimeMillis();
            long nextRunAt = nextRunAt(now, startedAt, task);
            // 时间轮归调度线程独占：回填必须排回它那条线程，不能在这里直接改
            ScheduledExecutorService currentTicker = ticker;
            if (currentTicker == null || currentTicker.isShutdown()) {
                return;
            }
            currentTicker.execute(() -> finish(task, nextRunAt));
        }
    }

    /**
     * 真正调一次。超时**必须**抛带分类的 {@link ModbusInvokeException}：
     * {@code ModbusFailureType} 里没有 TIMEOUT 这一类，裸的 {@code TimeoutException} 会被归成
     * {@code UNKNOWN} —— 一个挂死的 DTU 会被记成「不知道什么原因」，排查方向就丢了。
     */
    private void invoke(ModbusPollingTask task) {
        if (task.ownerId == null) {
            // 归属解析不出来（设备所在空间查不到，见 ModbusPollingReconciler）：不猜一个归属去调设备
            throw ModbusInvokeException.of(ModbusFailureType.CONFIG_ERROR,
                    "owner space is unknown for poll task, did=" + task.did);
        }
        JsonObject values = fdService
                .invokePolled(task.ownerId, task.fdid, task.pollIndex, baselines)
                .ifNoItem().after(Duration.ofSeconds(invokeTimeoutSeconds))
                .failWith(() -> ModbusInvokeException.of(ModbusFailureType.NO_RESPONSE,
                        "poll invoke timeout after " + invokeTimeoutSeconds + "s, did=" + task.did))
                .await().indefinitely();
        logger.debugv("modbus poll ok, key: {0}, fields: {1}", task.key(),
                values == null ? 0 : values.size());
    }

    /** 写回结果状态（**副作用**：写失败只记日志，不影响下一轮调度）。 */
    private void recordOutcome(ModbusPollingTask task, Throwable failure) {
        if (task.id == null) {
            return;
        }
        Uni<Void> write = failure == null
                ? pollRepo.updateSuccess(task.id)
                : pollRepo.updateFailure(task.id, messageOf(failure));
        write.onFailure().invoke(e -> logger.warnv(
                        "write modbus poll status failed, key: {0}, error: {1}", task.key(), e.getMessage()))
                .onFailure().recoverWithNull()
                .await().indefinitely();
        if (failure != null) {
            logger.warnv("modbus poll failed, key: {0}, error: {1}", task.key(), messageOf(failure));
        }
    }

    /** 回填下一次时刻，把任务放回时间轮。 */
    private void finish(ModbusPollingTask task, long nextRunAt) {
        reconciler.wheel().finish(task, nextRunAt);
    }

    private long nextRunAt(long now, long startedAt, ModbusPollingTask task) {
        return ModbusPollingSchedule.nextRunAt(now, startedAt, Math.max(1, task.intervalSeconds),
                jitterPercent, random);
    }

    /**
     * 超时必须小于租约时长：否则一次慢应答会在租约到期后才失败，另一个副本已经可以抢走这个任务，
     * 同一条帧上总线两次 —— 属于「偶发重复采集」那类最难查的故障。
     *
     * <p>不满足时**不阻断启动**：这份代码同时跑在 api 副本上，一个写错的配置不该让 API 起不来。
     * 钳到一个一定满足的值（{@code lease - 1}）并打一条 error —— 启动日志里很容易看见。</p>
     */
    private void checkTimeoutAgainstLease() {
        if (invokeTimeoutSeconds > 0 && invokeTimeoutSeconds < leaseSeconds) {
            return;
        }
        long clamped = Math.max(1, leaseSeconds - 1);
        logger.errorv("modbus.poll.invoke-timeout-seconds ({0}) must be less than"
                        + " modbus.poll.lease-seconds ({1}); using {2}s",
                invokeTimeoutSeconds, leaseSeconds, clamped);
        invokeTimeoutSeconds = clamped;
    }

    /**
     * 失败原因文本：与 {@code ModbusHistoryWriteService} 同口径（没 message 就用类名，超长截断）——
     * 这里的 message 来自同一个异常体系，长度风险也一样。
     */
    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500) + "…";
    }

    private static ThreadFactory named(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
