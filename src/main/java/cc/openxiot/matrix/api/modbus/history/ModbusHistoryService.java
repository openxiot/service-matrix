package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.typedef.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.typedef.modbus.failure.ModbusInvokeException;
import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarmRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceFailure;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceFailureRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistoryRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadowRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集历史：把一次 invoke 的结果按落库规则记下来 —— 成功记「字段 → 值」，失败记故障（规则见 Modbus-History.md）。
 *
 * <p>手动 invoke 与服务端轮询走的是这一个入口 —— 两边分开写，影子迟早会和实际值脱节。</p>
 *
 * <p><b>写入顺序是正确性的一部分</b>：读影子 → 判定 → **先写明细（历史 / 失败） → 再更新影子**。
 * 反过来的话，明细写失败而影子已经前进，那次变化就永久丢了
 * （下次比较基准已经是新值）；按现在的顺序，失败时影子停在旧态，下次采集仍判为「变了」而重试。
 * 失败记录同理：影子留着旧故障，下次同样的故障仍会被重判并重试落库。</p>
 *
 * <p>本类与副本数无关：影子读的是库、不是内存。轮询器在**单副本**时可以把基准缓存到内存里
 * 省掉这次读，那是轮询器的优化（见 Modbus-History-TODO.md 第三步），不影响这里的正确性。</p>
 *
 * <p>因此 {@code record} / {@code recordFailure} 各有**一对**入口：三个参数的那个自己去读影子
 * （手动 invoke 用），四个参数的那个基准由调用方给（轮询器用）。四个参数的那个是唯一实现，
 * 两个入口的差别仅在于基准从哪来 —— 判定规则一份都不复制。</p>
 */
@ApplicationScoped
public class ModbusHistoryService {

    /** 失败原因文本的长度上限：上游描述可能很长，而越长越容易让「同一条故障」被判成不同的两条。 */
    private static final int MAX_MESSAGE_LENGTH = 500;

    @Inject
    ModbusServiceShadowRepository shadowRepo;

    @Inject
    ModbusServiceHistoryRepository historyRepo;

    @Inject
    ModbusServiceFailureRepository failureRepo;

    /** 只为级联删除而注入：告警行与历史同命 —— 服务没了，它再也没人能处理（见 {@link #purge}）。 */
    @Inject
    ModbusServiceAlarmRepository alarmRepo;

    @Inject
    Logger logger;

    /** 建索引开关，与点表 / 服务共用同一个（见 ModbusServiceService）。 */
    @ConfigProperty(name = "modbus.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    /** 死区（仅对数值生效）：0 = 精确比较。见 Modbus-History.md 第八节。 */
    @ConfigProperty(name = "modbus.history.deadband", defaultValue = "0")
    double deadband;

    /** 保活打点周期（秒）：值没变也强制落一条；{@code <= 0} = 关闭保活。 */
    @ConfigProperty(name = "modbus.history.keep-alive-seconds", defaultValue = "600")
    long keepAliveSeconds;

    /**
     * 历史明细的保留天数：{@code > 0} 建 {@code at} 上的 TTL 索引，{@code <= 0} = 关
     * （见 Modbus-History.md 第六节）。
     *
     * <p><b>缺省就是开着的</b>（90 天）：不设 env 也会生效，部署起来就开始删 90 天前的明细。
     * 想先观察就显式设 0。只给 {@code service-histories}：故障记录与影子不设（理由见文档同一节）。</p>
     */
    @ConfigProperty(name = "modbus.history.retention-days", defaultValue = "90")
    int retentionDays;

    void init(@Observes StartupEvent event) {
        if (!indexEnabled) {
            return;
        }
        long retentionSeconds = ModbusHistoryRetention.ttlSeconds(retentionDays);
        if (retentionSeconds > 0) {
            logger.infov("history retention enabled, {0} days", retentionDays);
        } else {
            logger.info("history retention disabled, history is kept forever");
        }
        shadowRepo.ensureIndexes()
                .chain(ignored -> historyRepo.ensureIndexes(retentionSeconds))
                .chain(ignored -> failureRepo.ensureIndexes())
                .await().indefinitely();
    }

    /**
     * 记一次采集结果，**基准从库里读**（手动 invoke 走这条）。写方法（没有读值）、
     * 未落库的服务定义等一律静默跳过。
     *
     * <p>失败（数据库不可用等）由调用方决定怎么处置：{@code ModbusServiceService} 只记日志、
     * 不让 invoke 跟着失败 —— 值已经拿到了，历史只是副作用。</p>
     *
     * @return 新的比对基准（= 写后的影子文档）；null 表示**这次什么都没写、没有新基准**，
     *         调用方据此保留它原有的基准而不是清空（没写就说明库里的状态没变，旧基准仍然有效）
     */
    public Uni<ModbusServiceShadow> record(ModbusService service, ModbusPolling function, JsonObject values) {
        if (!recordable(service, function)) {
            return Uni.createFrom().nullItem();
        }
        return shadowRepo.find(service.id.toHexString(), function.index)
                .chain(shadow -> record(service, function, values, shadow));
    }

    /**
     * 记一次采集结果，**基准由调用方给**（轮询器用它自己的内存基准，省掉上面的读库）。
     *
     * <p>只有这一处实现，两个入口的差别仅在于基准从哪来 —— 落库规则（变位 / 死区 / 保活 /
     * 恢复落全字段 / 先明细后影子）一份都不复制。</p>
     *
     * <p><b>{@code baseline} 是权威的</b>，本方法不读影子。所以传 null 表示**库里确实没有影子**
     * （真·首次采集，会 {@code insert}）—— 而不是「我不知道」。拿不准就传库里读出来的那份；
     * 轮询器的判定见 {@code ModbusPollBaselines.usable}：故障态与没缓存都回退到读库。</p>
     */
    public Uni<ModbusServiceShadow> record(ModbusService service, ModbusPolling function,
                                           JsonObject values, ModbusServiceShadow baseline) {
        if (!recordable(service, function)) {
            return Uni.createFrom().nullItem();
        }
        // 拷一份：JsonObject 内部的 map 是活的，直接塞进实体会被后续改动牵连
        Map<String, Object> sample = values == null
                ? Map.of()
                : new LinkedHashMap<>(values.getMap());
        String serviceId = service.id.toHexString();
        Date now = new Date();
        ModbusServiceShadow shadow = baseline;
        boolean failedBefore = shadow != null && shadow.lastError != null;

        if (sample.isEmpty()) {
            // 写方法（response 为空）：没有读值可记。但「这次调用成功了」本身要落下来 ——
            // 否则上一次失败留在影子上的故障态永远清不掉，前端会一直看到一条早已恢复的故障。
            if (!failedBefore) {
                return Uni.createFrom().nullItem();
            }
            ModbusServiceShadow cleared = clearedShadow(shadow, serviceId, function.index);
            return shadowRepo.replace(cleared).replaceWith(cleared);
        }

        // 上次调用失败过：把这次成功当「首次采集」处理（基准置空 → 每个字段都落一条）。
        // 否则值恰好没变时，恢复的那一刻一个样本都不落，曲线上就分不清「已恢复」和「仍在故障」。
        boolean fresh = shadow == null || failedBefore;
        ModbusHistoryPolicy.Decision decision = ModbusHistoryPolicy.decide(
                fresh ? null : shadow.fields,
                fresh ? null : shadow.recordedAt,
                sample, now, deadband, keepAliveSeconds * 1000L);
        if (!decision.hasRows()) {
            // 绝大多数采集走这条：一个数据库操作都没有（见 Modbus-History-TODO.md 第四步的实测）
            return Uni.createFrom().nullItem();
        }
        // 先追加历史，成功后再动影子：中间失败则影子停在旧值，下次采集仍判为「变了」而重试。
        // 影子的写必须在 chain 之内构造 —— 放外面（哪怕 Uni 是懒的）也等于把「先历史后影子」
        // 这条约定寄托在 Mutiny 的懒执行上，读代码的人和测试都看不出来。
        ModbusServiceShadow next = shadowOf(shadow, serviceId, function.index, sample, now, null, null);
        return historyRepo.insertAll(rows(serviceId, function.index, sample, decision, now))
                .chain(ignored -> shadow == null ? shadowRepo.insert(next) : shadowRepo.replace(next))
                .replaceWith(next);
    }

    /**
     * 记一次 function 执行失败，**基准从库里读**。同一条故障持续存在时**一个数据库操作都不做**。
     *
     * <p>失败（数据库不可用等）由调用方处置，与 {@link #record} 同口径：{@code ModbusServiceService}
     * 只记日志，不改写对调用方的错误响应 —— 该报的错照旧往下报。</p>
     *
     * @return 新的基准（这里就是故障态本身）；null = 没有写、基准不变
     */
    public Uni<ModbusServiceShadow> recordFailure(ModbusService service, ModbusPolling function,
                                                  Throwable error) {
        if (!recordable(service, function)) {
            return Uni.createFrom().nullItem();
        }
        return shadowRepo.find(service.id.toHexString(), function.index)
                .chain(shadow -> recordFailure(service, function, error, shadow));
    }

    /**
     * 记一次 function 执行失败，**基准由调用方给**（同 {@link #record} 的一对入口）。
     *
     * <p><b>分类只做透传</b>：{@link ModbusFailureType} 由抛出点决定（只有那里知道是 CRC 不符还是
     * 从站异常码、异常码是几号），本方法不猜。</p>
     *
     * <p>{@code fields} / {@code recordedAt} 原样保留（最后一次成功的采集），只更新故障态
     * —— 前端要能显示「最后已知值 + 它从哪一刻起不再更新」。</p>
     *
     * <p>返回的基准带着 {@code lastError}，所以轮询器把它放进内存基准缓存后，
     * {@code ModbusPollBaselines.usable} 会判它不可用 → 下一次采集回退到读库。
     * 「故障态不吃内存基准」这条口径就是这样自然成立的，不需要额外的失效逻辑。</p>
     */
    public Uni<ModbusServiceShadow> recordFailure(ModbusService service, ModbusPolling function,
                                                  Throwable error, ModbusServiceShadow baseline) {
        if (!recordable(service, function)) {
            return Uni.createFrom().nullItem();
        }
        String serviceId = service.id.toHexString();
        String message = messageOf(error);
        Date now = new Date();
        ModbusServiceShadow shadow = baseline;

        if (shadow != null && message.equals(shadow.lastError)) {
            // 同一个故障还在持续（DTU 离线是最典型的形态）：不落重复记录。
            // 故障持续多久由这条记录到下一行成功样本的间隔推出来，逐次打点不增加任何信息。
            // 判定用 message 而不是分类：分类相同时 message 未必相同（异常码不同），
            // 反过来 message 相同则分类必然相同 —— 用 message 是更严的那个键。
            return Uni.createFrom().nullItem();
        }
        ModbusServiceShadow next = shadowOf(shadow, serviceId, function.index,
                shadow == null ? null : shadow.fields,
                shadow == null ? null : shadow.recordedAt,
                message, now);
        return failureRepo.insert(failureOf(serviceId, function.index, error, message, now))
                .chain(ignored -> shadow == null ? shadowRepo.insert(next) : shadowRepo.replace(next))
                .replaceWith(next);
    }

    /**
     * 级联清理一批服务的影子、历史、失败记录与**告警**（服务或依赖设备被删除时调用）。
     *
     * <p>告警为什么跟着删、而不是像定义变更那样按 {@code DEFINITION} **关掉**：关掉的语义是
     * 「这条告警的原因是定义不再覆盖它」，那是一条给人看的历史，得留着；而服务整个没了的时候，
     * 那条告警再也没人能处理（页面按空间的子树查，查不到它）、也没人需要「当时为什么报」——
     * 它就是存储垃圾。真正该保留的「这个点位历史上报过什么」在历史与故障记录里另有其份。</p>
     *
     * <p>失败照常往上抛：服务已经删掉了，这里留下的是存储垃圾，该怎么处置由调用方定 ——
     * {@code ModbusServiceService} 的选择是只记日志，不让业务操作跟着失败。</p>
     */
    public Uni<Void> purge(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.combine().all()
                .unis(shadowRepo.deleteByServiceIds(serviceIds),
                        historyRepo.deleteByServiceIds(serviceIds),
                        failureRepo.deleteByServiceIds(serviceIds),
                        alarmRepo.deleteByServiceIds(serviceIds))
                .asTuple()
                .replaceWithVoid();
    }

    // ===== 内部 =====

    /** 能不能记：服务已落库、方法有序号。否则静默跳过 —— 也不产生基准。 */
    private static boolean recordable(ModbusService service, ModbusPolling function) {
        return service != null && service.id != null && function != null && function.index != null;
    }

    private static List<ModbusServiceHistory> rows(String serviceId, Integer pollingIndex,
                                                   Map<String, Object> sample,
                                                   ModbusHistoryPolicy.Decision decision, Date now) {
        List<ModbusServiceHistory> rows = new ArrayList<>(
                decision.changedFields().size() + decision.keepaliveFields().size());
        for (String field : decision.changedFields()) {
            rows.add(row(serviceId, pollingIndex, field, sample.get(field), now, false));
        }
        for (String field : decision.keepaliveFields()) {
            rows.add(row(serviceId, pollingIndex, field, sample.get(field), now, true));
        }
        return rows;
    }

    private static ModbusServiceHistory row(String serviceId, Integer pollingIndex, String field,
                                            Object value, Date at, boolean keepalive) {
        ModbusServiceHistory row = new ModbusServiceHistory();
        row.serviceId = serviceId;
        row.pollingIndex = pollingIndex;
        row.field = field;
        row.value = value;
        row.at = at;
        row.keepalive = keepalive;
        return row;
    }

    private static ModbusServiceFailure failureOf(String serviceId, Integer pollingIndex,
                                                  Throwable error, String message, Date at) {
        ModbusServiceFailure failure = new ModbusServiceFailure();
        failure.serviceId = serviceId;
        failure.pollingIndex = pollingIndex;
        failure.type = typeOf(error).name();
        failure.remoteCode = error instanceof ModbusInvokeException e ? e.remoteCode() : null;
        failure.message = message;
        failure.at = at;
        return failure;
    }

    /**
     * 失败分类：本模块的失败点已经把分类带在异常上了，直接透传。
     *
     * <p>其余异常归 {@link ModbusFailureType#UNKNOWN} —— 宁可说「不知道」，也不猜一个
     * 会把人带偏方向的分类：把编解码异常猜成「设备离线」，排查就从现场跑到代码上去了。</p>
     */
    private static ModbusFailureType typeOf(Throwable error) {
        return error instanceof ModbusInvokeException e ? e.type() : ModbusFailureType.UNKNOWN;
    }

    /**
     * 失败原因文本：异常没带 message 时退回类名（否则 NPE 之类在库里就是一串 null，什么也说明不了），
     * 超长截断。
     */
    private static String messageOf(Throwable error) {
        if (error == null) {
            return "unknown failure";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() <= MAX_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }

    /**
     * 新的影子文档：{@code previous} 存在时带上它的 {@code _id}（替换文档改了 {@code _id} 会被 Mongo 拒）。
     *
     * <p>三个调用点的差别只在这几个入参上 —— 成功传新样本、清掉故障态；失败传上一次成功的值与时刻、
     * 换上新故障。写成一个构造器是为了让「哪些字段活着传到下一次」一眼可见。</p>
     */
    private static ModbusServiceShadow shadowOf(ModbusServiceShadow previous, String serviceId, Integer pollingIndex,
                                                Map<String, Object> fields, Date recordedAt,
                                                String lastError, Date errorAt) {
        ModbusServiceShadow shadow = new ModbusServiceShadow();
        if (previous != null) {
            shadow.id = previous.id;
        }
        shadow.serviceId = serviceId;
        shadow.pollingIndex = pollingIndex;
        shadow.fields = fields;
        shadow.recordedAt = recordedAt;
        shadow.lastError = lastError;
        shadow.errorAt = errorAt;
        return shadow;
    }

    /** 调用成功、但没有读值（写方法）时清故障态：值与时刻原样保留。 */
    private static ModbusServiceShadow clearedShadow(ModbusServiceShadow previous, String serviceId,
                                                     Integer pollingIndex) {
        return shadowOf(previous, serviceId, pollingIndex,
                previous.fields, previous.recordedAt, null, null);
    }
}
