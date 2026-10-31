package cc.openxiot.matrix.api.energy;

import cc.openxiot.matrix.api.device.manipulation.ManipulationService;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergySourceKind;
import cc.openxiot.matrix.db.energy.EnergyValueMode;
import cc.openxiot.matrix.db.energy.config.EnergyConfig;
import cc.openxiot.matrix.db.energy.config.EnergyConfigRepository;
import cc.openxiot.matrix.db.energy.config.EnergyPoint;
import cc.openxiot.matrix.db.energy.stat.EnergySample;
import cc.openxiot.matrix.db.energy.stat.EnergyStatRepository;
import cc.openxiot.matrix.db.energy.state.EnergyPointState;
import cc.openxiot.matrix.db.energy.state.EnergyPointStateRepository;
import cc.openxiot.matrix.db.modbus.shadow.ModbusShadow;
import cc.openxiot.matrix.db.modbus.shadow.ModbusShadowRepository;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 能源采样循环：每 {@code energy.sample.interval-seconds} 秒把全部启用配置的点位读一遍，
 * 算成小时桶里的增量。
 *
 * <p><b>为什么不是 {@code @Scheduled}</b>：本仓没有 quarkus-scheduler 依赖，Modbus 轮询器也是
 * 自己起一个 {@link ScheduledExecutorService}；这里沿用同一套（见 {@code ModbusPollingScheduler}）。
 * 该循环跑在普通线程上（不是 event loop），所以可以 {@code await()} 库调用。</p>
 *
 * <p><b>开关缺省关</b>（{@code energy.sample.enabled=false}）：与 Modbus 轮询同一条部署约定 ——
 * 多个 api 副本同时采样会把同一段时间的增量重复累加。部署时由其中一个副本（通常是 poller）
 * 打开它。</p>
 *
 * <p>每条配置独立处理、失败互不影响：一个点位读不到（设备离线）只让这一轮少一笔，
 * 不会让整个采样循环停摆。</p>
 */
@ApplicationScoped
public class EnergySampler {

    @Inject
    Logger logger;

    @Inject
    EnergyConfigRepository configRepo;

    @Inject
    EnergyStatRepository statRepo;

    @Inject
    EnergyPointStateRepository stateRepo;

    @Inject
    ModbusShadowRepository shadowRepo;

    @Inject
    ManipulationService manipulationService;

    @ConfigProperty(name = "energy.sample.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "energy.sample.interval-seconds", defaultValue = "60")
    long intervalSeconds;

    /** 桶边界时区；{@code system} = 跟随 JVM。（空字符串在 MP Config 里等于「没配」，不能当默认值。） */
    @ConfigProperty(name = "energy.sample.zone", defaultValue = "system")
    String zoneId;

    @ConfigProperty(name = "energy.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    private final AtomicBoolean running = new AtomicBoolean();

    private volatile ScheduledExecutorService ticker;

    void onStart(@Observes StartupEvent event) {
        if (indexEnabled) {
            configRepo.ensureIndexes()
                    .chain(ignored -> statRepo.ensureIndexes())
                    .subscribe().with(
                            ignored -> logger.info("energy indexes ensured"),
                            failure -> logger.warnv("ensure energy indexes failed: {0}", failure.getMessage()));
        }

        if (!enabled) {
            return;
        }

        long interval = Math.max(intervalSeconds, 10);
        ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "energy-sampler");
            thread.setDaemon(true);
            return thread;
        });

        // 首轮延后一个周期：让应用先把索引/连接起来，也避免与启动时的库初始化抢
        ticker.scheduleWithFixedDelay(this::tickSafely, interval, interval, TimeUnit.SECONDS);
        logger.infov("energy sampler started, interval={0}s", interval);
    }

    void onStop(@Observes ShutdownEvent event) {
        ScheduledExecutorService current = ticker;
        if (current != null) {
            current.shutdownNow();
        }
    }

    /** 调度线程的入口：上一轮没跑完就跳过这一轮（采样是幂等的，但叠着跑只会互相等）。 */
    void tickSafely() {
        if (!running.compareAndSet(false, true)) {
            logger.debug("previous energy sampling still running, skip this tick");
            return;
        }
        try {
            tick().await().atMost(Duration.ofSeconds(Math.max(intervalSeconds * 2, 60)));
        } catch (Exception e) {
            logger.errorv(e, "energy sampling failed");
        } finally {
            running.set(false);
        }
    }

    /**
     * 一轮采样：逐配置读点位、算增量、写小时桶。
     *
     * <p>包可见是为了单测之外也能在开发时手动触发；正常由 {@link #tickSafely()} 调。</p>
     */
    public Uni<Void> tick() {
        long now = System.currentTimeMillis();
        return configRepo.listEnabled()
                .chain(configs -> {
                    Uni<Void> chain = Uni.createFrom().voidItem();
                    for (EnergyConfig config : configs) {
                        if (config == null || config.id == null || config.points == null
                                || config.points.isEmpty()) {
                            continue;
                        }
                        chain = chain.chain(() -> sampleConfig(config, now)
                                .onFailure().recoverWithItem(failure -> {
                                    // 带上异常本身：getMessage() 可能为 null（NPE、驱动包装异常），
                                    // 只打 message 会变成「failed: null」，排不了障。
                                    logger.warnv(failure, "sample energy config {0} failed", config.id);
                                    return null;
                                }));
                    }
                    return chain;
                });
    }

    /** 一条配置的一轮采样。 */
    private Uni<Void> sampleConfig(EnergyConfig config, long now) {
        String configId = config.id.toHexString();
        return Uni.combine().all()
                .unis(readServiceValues(config), readRegisterValues(config), stateRepo.listByConfig(configId))
                .asTuple()
                .chain(tuple -> {
                    Map<String, Observation> observations =
                            mergeObservations(tuple.getItem1(), tuple.getItem2());
                    Map<String, EnergyPointState> states = statesByKey(tuple.getItem3());

                    long intervalMillis = Math.max(intervalSeconds, 10) * 1_000L;
                    boolean integratePower = config.role == EnergyRole.STORAGE
                            && !config.hasChargeDischargePoints();

                    List<EnergySample> samples = new ArrayList<>();
                    List<EnergyPointState> dirty = new ArrayList<>();
                    for (EnergyPoint point : config.points) {
                        if (point == null) {
                            continue;
                        }
                        String key = point.key();
                        EnergyPointState state = states.get(key);
                        if (state == null) {
                            state = new EnergyPointState();
                            state.id = configId + "#" + key;
                            state.configId = configId;
                            state.pointKey = key;
                        }
                        Observation observation = observations.get(key);
                        Double raw = observation == null ? null : observation.value();
                        boolean fresh = fresh(point, observation, state);
                        EnergyAccumulator.PointOutcome outcome = EnergyAccumulator.consume(
                                point, raw, fresh, now,
                                state.lastRaw, state.lastAt == null ? null : state.lastAt.getTime(),
                                integratePower, intervalMillis);
                        samples.add(outcome.sample());
                        if (raw != null) {
                            state.lastRaw = outcome.lastRaw();
                            state.lastAt = outcome.lastAt() == null ? null : new Date(outcome.lastAt());
                            state.total += outcome.delta();
                            state.updatedAt = new Date(now);
                            dirty.add(state);
                        }
                    }

                    EnergySample sample = EnergyAccumulator.price(config,
                            EnergyAccumulator.combine(samples));

                    Uni<Void> saveStates = saveStates(dirty);
                    if (sample.isEmpty()) {
                        return saveStates;
                    }
                    long bucketAt = EnergyRollup.bucketAt(now, EnergyGranularity.HOUR, zone());
                    return saveStates.chain(ignored ->
                            statRepo.addSample(config.spaceId, config, bucketAt, sample));
                });
    }

    /**
     * 读设备服务属性：一个配置下的 did 一次批量取影子。
     *
     * <p>返回按 {@link EnergyPoint#key()} 索引的观测值。设备属性影子没有时间戳，
     * 「是不是新上报」用**值是否变化**判断（见 {@link EnergyAccumulator} 的增量口径）。</p>
     */
    private Uni<Map<String, Observation>> readServiceValues(EnergyConfig config) {
        List<String> dids = config.points.stream()
                .filter(point -> point != null && point.source == EnergySourceKind.SERVICE)
                .map(point -> point.did)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (dids.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        return manipulationService.getShadows(config.spaceId, dids)
                .map(shadows -> serviceObservations(config, shadows))
                .onFailure().recoverWithItem(failure -> {
                    logger.warnv("read device shadows for energy config {0} failed: {1}",
                            config.id, failure.getMessage());
                    return Map.of();
                });
    }

    private static Map<String, Observation> serviceObservations(EnergyConfig config, JsonArray shadows) {
        Map<String, Double> byPid = new HashMap<>();
        if (shadows != null) {
            for (int i = 0; i < shadows.size(); i++) {
                if (!(shadows.getValue(i) instanceof JsonObject element)) {
                    continue;
                }
                String pid = element.getString("pid");
                int status = element.getInteger("status", 0);
                if (pid == null || status < 0) {
                    continue;
                }
                Double value = EnergyAccumulator.number(element.getValue("value"));
                if (value != null) {
                    byPid.put(pid, value);
                }
            }
        }
        Map<String, Observation> observations = new HashMap<>();
        for (EnergyPoint point : config.points) {
            if (point == null || point.source != EnergySourceKind.SERVICE || point.did == null) {
                continue;
            }
            Double value = byPid.get(point.did + "." + point.siid + "." + point.piid);
            if (value != null) {
                // 设备属性影子没有时间戳：增量口径只能用「值变没变」判断新旧（见 fresh()）
                observations.put(point.key(), new Observation(value, null));
            }
        }
        return observations;
    }

    /**
     * 读 Modbus 寄存器影子：按 fdid 各取一次。
     *
     * <p>寄存器影子带 {@code recordedAt}：成功的轮询会刷新它（即使值没变），所以增量口径下
     * 「这次读是不是新的」可以精确判断，不用靠值变化去猜。</p>
     */
    private Uni<Map<String, Observation>> readRegisterValues(EnergyConfig config) {
        List<String> fdids = config.points.stream()
                .filter(point -> point != null && point.source == EnergySourceKind.REGISTER)
                .map(point -> point.fdid)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (fdids.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        List<Uni<List<ModbusShadow>>> queries = fdids.stream()
                .map(shadowRepo::listByFdid)
                .toList();
        return Uni.join().all(queries).andFailFast()
                .map(groups -> registerObservations(config, groups))
                .onFailure().recoverWithItem(failure -> {
                    logger.warnv("read modbus shadows for energy config {0} failed: {1}",
                            config.id, failure.getMessage());
                    return Map.of();
                });
    }

    private static Map<String, Observation> registerObservations(
            EnergyConfig config, List<List<ModbusShadow>> groups) {
        Map<String, ModbusShadow> byFdAndIndex = new HashMap<>();
        for (List<ModbusShadow> group : groups) {
            for (ModbusShadow shadow : group) {
                if (shadow != null && shadow.fdid != null && shadow.registerIndex != null) {
                    byFdAndIndex.put(shadow.fdid + "#" + shadow.registerIndex, shadow);
                }
            }
        }
        Map<String, Observation> observations = new HashMap<>();
        for (EnergyPoint point : config.points) {
            if (point == null || point.source != EnergySourceKind.REGISTER || point.fdid == null) {
                continue;
            }
            ModbusShadow shadow = byFdAndIndex.get(point.fdid + "#" + point.registerIndex);
            if (shadow == null) {
                continue;
            }
            Double value = EnergyAccumulator.number(shadow.value);
            if (value != null) {
                observations.put(point.key(), new Observation(value,
                        shadow.recordedAt == null ? null : shadow.recordedAt.getTime()));
            }
        }
        return observations;
    }

    /**
     * 合并设备属性与寄存器两路观测。
     *
     * <p>任一侧在没有该来源的点位时返回 {@link Map#of()}（不可变实例），所以不能直接在
     * 其中一侧上 {@code putAll}：那会让只配一种来源的配置每轮都抛
     * {@link UnsupportedOperationException}（message 为 null）。</p>
     */
    static Map<String, Observation> mergeObservations(
            Map<String, Observation> services, Map<String, Observation> registers) {
        Map<String, Observation> merged = new HashMap<>(services);
        merged.putAll(registers);
        return merged;
    }

    private static Map<String, EnergyPointState> statesByKey(List<EnergyPointState> states) {
        Map<String, EnergyPointState> byKey = new HashMap<>();
        if (states != null) {
            for (EnergyPointState state : states) {
                if (state != null && state.pointKey != null) {
                    byKey.put(state.pointKey, state);
                }
            }
        }
        return byKey;
    }

    private Uni<Void> saveStates(List<EnergyPointState> states) {
        if (states == null || states.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        List<Uni<Void>> saves = states.stream().map(stateRepo::save).toList();
        return Uni.join().all(saves).andFailFast().replaceWithVoid();
    }

    private ZoneId zone() {
        try {
            return zoneId == null || zoneId.isBlank() || "system".equalsIgnoreCase(zoneId)
                    ? ZoneId.systemDefault()
                    : ZoneId.of(zoneId);
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }

    /**
     * 增量口径下「这次读是不是一次新上报」。
     *
     * <ul>
     *   <li>Modbus 寄存器：影子带 {@code recordedAt}，比上一次读数时刻新才算新
     *       （成功的轮询即使值没变也会刷新它）；</li>
     *   <li>设备属性：影子没有时间戳，只能按值变化判断。代价是「连续两次增量恰好相同」会被漏掉一次
     *       （例如每轮都上报 1 kWh），这是没有时间戳时可做的唯一选择，且比每轮重复累加安全得多。</li>
     * </ul>
     */
    private static boolean fresh(EnergyPoint point, Observation observation, EnergyPointState state) {
        if (observation == null) {
            return false;
        }
        if (state.lastRaw == null) {
            // 第一条读数只建基线，fresh 与否都不产生增量
            return false;
        }
        if (point.mode != EnergyValueMode.INCREMENT) {
            return true;
        }
        if (observation.observedAt() != null) {
            return state.lastAt == null || observation.observedAt() > state.lastAt.getTime();
        }
        return !Objects.equals(observation.value(), state.lastRaw);
    }

    /**
     * 一条点位的本轮观测。
     *
     * @param value      原始读数
     * @param observedAt 设备侧的读取时刻（只有 Modbus 影子有）；null = 没有时间戳
     */
    record Observation(double value, Long observedAt) {
    }
}
