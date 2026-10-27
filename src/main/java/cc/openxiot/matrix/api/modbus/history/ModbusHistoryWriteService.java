package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.api.modbus.polling.ModbusPollingBaseline;
import cc.openxiot.matrix.typedef.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.typedef.modbus.failure.ModbusInvokeException;
import cc.openxiot.matrix.api.modbus.polling.ModbusShadowPlan;
import cc.openxiot.matrix.db.modbus.alarm.ModbusAlarmRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.history.ModbusPollingHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusPollingHistoryRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusRegistryHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusRegistryHistoryRepository;
import cc.openxiot.matrix.db.modbus.shadow.ModbusShadow;
import cc.openxiot.matrix.db.modbus.shadow.ModbusShadowRepository;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.modbus.fd.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.fd.poll.response.field.ModbusResponseField;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
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
public class ModbusHistoryWriteService {

    /**
     * 失败原因文本的长度上限：上游描述可能很长，而越长越容易让「同一条故障」被判成不同的两条。
     */
    private static final int MAX_MESSAGE_LENGTH = 500;

    /**
     * 寄存器中心的新存储：逐寄存器的历史事件与当前值（影子）。已停写的按方法历史
     * {@code service-histories}（{@code ModbusServiceHistory}）不再写入、也在这里删了仓库。
     */
    @Inject
    ModbusRegistryHistoryRepository registryHistoryRepo;

    @Inject
    ModbusShadowRepository registryShadowRepo;

    /**
     * 操作日志：每次调用一条（见 {@link ModbusPollingHistory}）。写失败静默，不拖累调用。
     */
    @Inject
    ModbusPollingHistoryRepository pollingHistoryRepo;

    /**
     * 只为级联删除而注入：告警行与历史同命 —— 服务没了，它再也没人能处理（见 {@link #purge}）。
     */
    @Inject
    ModbusAlarmRepository alarmRepo;

    @Inject
    Logger logger;

    /**
     * 建索引开关，与点表 / 服务共用同一个（见 FieldDeviceService）。
     */
    @ConfigProperty(name = "modbus.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    /**
     * 死区（仅对数值生效）：0 = 精确比较。见 Modbus-History.md 第八节。
     */
    @ConfigProperty(name = "modbus.history.deadband", defaultValue = "0")
    double deadband;

    /**
     * 保活打点周期（秒）：值没变也强制落一条；{@code <= 0} = 关闭保活。
     */
    @ConfigProperty(name = "modbus.history.keep-alive-seconds", defaultValue = "600")
    long keepAliveSeconds;

    /**
     * 历史明细的保留天数：{@code > 0} 建 {@code at} 上的 TTL 索引，{@code <= 0} = 关
     * （见 Modbus-History.md 第六节）。
     *
     * <p><b>缺省就是开着的</b>（90 天）：不设 env 也会生效，部署起来就开始删 90 天前的明细。
     * 想先观察就显式设 0。落到**操作日志**（{@code service-polling-histories}，按采集频率线性增长）：
     * 已停写的按方法历史 {@code service-histories} 不再新增、它的 TTL 一并删了；影子不设
     * （理由见文档同一节）。</p>
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
        pollingHistoryRepo.ensureIndexes(retentionSeconds)
                .await().indefinitely();
    }

    /**
     * 记一次采集结果，**基准从库里读**（手动 invoke 走这条）。写方法（没有读值）、
     * 未落库的服务定义等一律静默跳过。
     *
     * <p>失败（数据库不可用等）由调用方决定怎么处置：{@code FieldDeviceService} 只记日志、
     * 不让 invoke 跟着失败 —— 值已经拿到了，历史只是副作用。</p>
     *
     * @return 新的比对基准（= 写后的影子文档）；null 表示**这次什么都没写、没有新基准**，
     * 调用方据此保留它原有的基准而不是清空（没写就说明库里的状态没变，旧基准仍然有效）
     */
    public Uni<ModbusPollingBaseline> record(FieldDevice fd, ModbusPoll poll, JsonObject values) {
        if (!recordable(fd, poll)) {
            return Uni.createFrom().nullItem();
        }
        // 手动 invoke / 缓存未命中的兜底：从**逐寄存器影子**重建本读块的比对基准，
        // 不再读已停写的 service-last-polling（见 ModbusPollingBaseline）。
        String fdid = fd.id.toHexString();
        return registryShadowRepo.listByFdid(fdid)
                .map(shadows -> blockBaseline(fd, poll, shadows))
                .chain(baseline -> record(fd, poll, values, baseline));
    }

    /**
     * 记一次采集结果，**基准由调用方给**（轮询器用它自己的内存基准，省掉上面的读库）。
     *
     * <p>只有这一处实现，两个入口的差别仅在于基准从哪来 —— 落库规则（变位 / 死区 / 保活 /
     * 恢复落全字段 / 先明细后影子）一份都不复制。</p>
     *
     * <p><b>{@code baseline} 是权威的</b>，本方法不读影子。所以传 null 表示**库里确实没有影子**
     * （真·首次采集，会 {@code insert}）—— 而不是「我不知道」。拿不准就传库里读出来的那份；
     * 轮询器的判定见 {@code ModbusPollingBaselines.usable}：故障态与没缓存都回退到读库。</p>
     */
    public Uni<ModbusPollingBaseline> record(
            FieldDevice fd,
            ModbusPoll poll,
            JsonObject values,
            ModbusPollingBaseline baseline
    ) {
        if (!recordable(fd, poll)) {
            return Uni.createFrom().nullItem();
        }
        // 拷一份：JsonObject 内部的 map 是活的，直接塞进会带跑后续改动。
        Map<String, Object> sample = values == null
                ? Map.of()
                : new LinkedHashMap<>(values.getMap());
        String fdid = fd.id.toHexString();
        Date now = new Date();
        boolean failedBefore = baseline != null && baseline.lastError() != null;

        // 操作日志：每次调用一条（成功侧）。**它跟「值变没变」无关** —— 变化与否是值历史/影子的取舍
        //（那两条仍各自按变化/保活判定），操作日志记的是「这轮调用执行过、成了」。
        ModbusPollingHistory log = pollingLog(fdid, poll.index,
                sample.isEmpty() ? "write" : "read", true, now, sample.isEmpty() ? null : sample,
                null, null, null);
        Uni<ModbusPollingBaseline> result;
        if (sample.isEmpty()) {
            // 写方法（response 为空）：没有读值可记。但「这次调用成功了」本身要落下来 ——
            // 否则上次失败留在影子上的故障态永远清不掉（故障态在寄存器影子 / 内存基准里）。
            result = failedBefore
                    ? Uni.createFrom().item(new ModbusPollingBaseline(
                    baseline.valueByField(), baseline.recordedAt(), null))
                    : Uni.createFrom().nullItem();
        } else {
            // 上次调用失败过：把这次成功当「首次采集」处理（基准置空 → 每个字段都落一条）。
            // 否则值恰好没变时，恢复的那一刻一个样本都不落，曲线上就分不清「已恢复」和「仍在故障」。
            boolean fresh = baseline == null || failedBefore;
            ModbusHistoryPolicy.Decision decision = ModbusHistoryPolicy.decide(
                    fresh ? null : baseline.valueByField(),
                    fresh ? null : baseline.recordedAt(),
                    sample, now, deadband, keepAliveSeconds * 1000L);
            if (!decision.hasRows()) {
                // 绝大多数采集走这条：只记操作日志，值历史/影子一个操作都没有
                //（见 Modbus-History-TODO.md 第四步的实测）。
                result = Uni.createFrom().nullItem();
            } else {
                // 逐寄存器：追加历史事件 + 更新当前值（影子）。range 已改读逐寄存器，按方法的
                // ModbusServiceHistory 不再写入。失败不阻断 invoke —— 写不上只影响寄存器读侧，
                // 值本身已经拿到了
                result = registryEffects(fd, sample, decision, now)
                        .map(ignored -> new ModbusPollingBaseline(sample, now, null));
            }
        }
        return pollingHistoryRepo.insert(log).chain(ignored -> result);
    }

    /**
     * 记一次 function 执行失败，**基准从库里读**。同一条故障持续存在时**一个数据库操作都不做**。
     *
     * <p>失败（数据库不可用等）由调用方处置，与 {@link #record} 同口径：{@code FieldDeviceService}
     * 只记日志，不改写对调用方的错误响应 —— 该报的错照旧往下报。</p>
     *
     * @return 新的基准（这里就是故障态本身）；null = 没有写、基准不变
     */
    public Uni<ModbusPollingBaseline> recordFailure(
            FieldDevice fd,
            ModbusPoll poll,
            Throwable error
    ) {
        if (!recordable(fd, poll)) {
            return Uni.createFrom().nullItem();
        }
        // 手动 invoke / 缓存未命中的兜底：从逐寄存器影子重建本读块的基准（见 record 3 参）。
        String fdid = fd.id.toHexString();
        return registryShadowRepo.listByFdid(fdid)
                .map(shadows -> blockBaseline(fd, poll, shadows))
                .chain(baseline -> recordFailure(fd, poll, error, baseline));
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
     * {@code ModbusPollingBaselines.usable} 会判它不可用 → 下一次采集回退到读库。
     * 「故障态不吃内存基准」这条口径就是这样自然成立的，不需要额外的失效逻辑。</p>
     */
    public Uni<ModbusPollingBaseline> recordFailure(
            FieldDevice fd,
            ModbusPoll poll,
            Throwable error,
            ModbusPollingBaseline baseline
    ) {
        if (!recordable(fd, poll)) {
            return Uni.createFrom().nullItem();
        }
        String fdid = fd.id.toHexString();
        String message = messageOf(error);
        Date now = new Date();

        // 操作日志：每次调用一条（失败侧）。分类 / 远端码 / 异常文本都在这儿，失败统计据此过滤。
        // **重复失败也照记**：操作日志记的是操作本身（这次调用又失败了），去重是值历史/影子的取舍。
        ModbusPollingHistory log = pollingLog(fdid, poll.index, "read", false, now, null,
                typeOf(error).name(), remoteCodeOf(error), message);
        Uni<ModbusPollingBaseline> result;
        if (baseline != null && message.equals(baseline.lastError())) {
            // 同一个故障还在持续（DTU 离线是最典型的形态）：寄存器影子/故障态不再重复落。
            // 操作日志仍照记（每调用一条）；基准不变。判定用 message 而不是分类：
            // 分类相同时 message 未必相同（异常码不同），反过来 message 相同则分类必然相同。
            result = Uni.createFrom().nullItem();
        } else {
            // 故障态基准：值/时刻原样保留最后一次成功的采集，只换上新故障消息。
            ModbusPollingBaseline failed = new ModbusPollingBaseline(
                    baseline == null ? null : baseline.valueByField(),
                    baseline == null ? null : baseline.recordedAt(),
                    message);
            result = registryFailureEffects(fd, poll, error, now)
                    // 逐寄存器：记失败事件（曲线竖线）+ 更新当前值的故障态（rollbackValve 在链内懒执行）
                    .replaceWith(failed);
        }
        return pollingHistoryRepo.insert(log).chain(ignored -> result);
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
     * {@code FieldDeviceService} 的选择是只记日志，不让业务操作跟着失败。</p>
     */
    public Uni<Void> purge(List<String> fdids) {
        if (fdids == null || fdids.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.combine().all()
                .unis(registryHistoryRepo.deleteByFdids(fdids),
                        registryShadowRepo.deleteByFdids(fdids),
                        pollingHistoryRepo.deleteByFdids(fdids),
                        alarmRepo.deleteByFdids(fdids))
                .asTuple()
                .replaceWithVoid();
    }

    // ===== 内部 =====

    /**
     * 能不能记：服务已落库、方法有序号。否则静默跳过 —— 也不产生基准。
     */
    private static boolean recordable(FieldDevice fd, ModbusPoll poll) {
        return fd != null && fd.id != null && poll != null && poll.index != null;
    }

    /**
     * 寄存器历史 + 当前值的**成功侧**写入（expand 路径）：对本次判为「变了 / 保活」的每个字段，
     * 若能解析到一只可读寄存器，就追加一条寄存器历史事件并 upsert 它的当前值（清故障态）。
     * 失败静默（不拖累 invoke）。
     */
    private Uni<Void> registryEffects(
            FieldDevice fd,
            Map<String, Object> sample,
            ModbusHistoryPolicy.Decision decision, Date now
    ) {
        String fdid = fd.id.toHexString();
        List<ModbusRegistryHistory> rows = new ArrayList<>();
        List<ModbusShadow> shadows = new ArrayList<>();
        for (String field : allFields(decision)) {
            ModbusRegistry reg = registerOf(fd, field);
            if (reg == null || reg.index == null) {
                continue;
            }
            rows.add(registryEvent(fdid, reg, now, sample.get(field), null));
            shadows.add(registryShadow(fdid, reg, now, sample.get(field), null, null));
        }
        return writeRegistry(rows, shadows, false);
    }

    /**
     * 寄存器历史 + 当前值的**失败侧**写入：该读方法应答里的每个可读寄存器各记一条失败 + 更新故障态。
     */
    private Uni<Void> registryFailureEffects(
            FieldDevice fd,
            ModbusPoll poll,
            Throwable error, Date now
    ) {
        String fdid = fd.id.toHexString();
        String message = messageOf(error);
        List<ModbusRegistryHistory> rows = new ArrayList<>();
        List<ModbusShadow> shadows = new ArrayList<>();
        for (ModbusResponseField field : ModbusPoll.responseFields(poll)) {
            if (field == null) {
                continue;
            }
            ModbusRegistry reg = registerOf(fd, field.field);
            if (reg == null || reg.index == null) {
                continue;
            }
            rows.add(registryEvent(fdid, reg, now, null, message));
            // errorAt = now：这条故障首次出现的时刻；同故障持续时被 recordFailure 去重跳过、不会刷新
            //（与 ModbusShadow.errorAt 的文档「首次出现、持续期间不刷新」一致）
            shadows.add(registryShadow(fdid, reg, now, null, message, now));
        }
        return writeRegistry(rows, shadows, true);
    }

    /**
     * 一次成功的读事件（值）或失败事件（标记 + 原因）。
     */
    private static ModbusRegistryHistory registryEvent(
            String fdid,
            ModbusRegistry reg,
            Date at,
            Object value,
            String message
    ) {
        ModbusRegistryHistory e = new ModbusRegistryHistory();
        e.fdid = fdid;
        e.registerIndex = reg.index;
        e.name = reg.name;
        e.unit = reg.unit;
        e.at = at;
        e.failure = message != null;
        e.value = value;
        e.message = message;
        return e;
    }

    /**
     * 寄存器当前值文档：成功侧带值 / 失败侧只带故障（value 留给 upsertFailure 不动）。
     */
    private static ModbusShadow registryShadow(
            String fdid,
            ModbusRegistry reg,
            Date at,
            Object value,
            String lastError,
            Date errorAt
    ) {
        ModbusShadow sh = new ModbusShadow();
        sh.fdid = fdid;
        sh.registerIndex = reg.index;
        sh.value = value;
        sh.recordedAt = at;
        sh.lastError = lastError;
        sh.errorAt = errorAt;
        return sh;
    }

    private Uni<Void> writeRegistry(
            List<ModbusRegistryHistory> rows,
            List<ModbusShadow> shadows,
            boolean failure
    ) {
        if (rows.isEmpty() && shadows.isEmpty()) {
            // 没有可解析到寄存器的字段（写方法 / 位名 / 未配寄存器点表的旧服务）：不写寄存器侧
            return Uni.createFrom().voidItem();
        }
        List<Uni<Void>> shadowWrites = failure
                ? shadows.stream().map(registryShadowRepo::upsertFailure).toList()
                : shadows.stream().map(registryShadowRepo::upsertValue).toList();
        return registryHistoryRepo.insertAll(rows)
                .chain(ignored -> Uni.combine().all().unis(shadowWrites).discardItems())
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * 判为「变了 / 保活」的字段集合（去重、保持顺序）。
     */
    private static List<String> allFields(ModbusHistoryPolicy.Decision decision) {
        List<String> fields = new ArrayList<>(decision.changedFields());
        for (String field : decision.keepaliveFields()) {
            if (!fields.contains(field)) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * 字段名 → 点表快照里的寄存器；名字对不上（位名、写方法应答）给 null，不写寄存器侧。
     */
    private static ModbusRegistry registerOf(FieldDevice fd, String fieldName) {
        if (fd == null || fd.configSnapshot == null
                || fd.configSnapshot.registries == null || fieldName == null) {
            return null;
        }
        for (ModbusRegistry reg : fd.configSnapshot.registries) {
            if (reg != null && fieldName.equals(reg.name)) {
                return reg;
            }
        }
        return null;
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
     * 远端原始码（只有 SLAVE_EXCEPTION / DEVICE_ERROR 带；其余 null，与失败记录同口径）。
     */
    private static Integer remoteCodeOf(Throwable error) {
        return error instanceof ModbusInvokeException e ? e.remoteCode() : null;
    }

    /**
     * 一条操作日志文档。成功侧带响应快照、失败侧带分类/远端码/文本。
     */
    private static ModbusPollingHistory pollingLog(
            String fdid,
            Integer pollIndex,
            String operation,
            boolean ok,
            Date at,
            Map<String, Object> fields,
            String type,
            Integer remoteCode,
            String message
    ) {
        ModbusPollingHistory row = new ModbusPollingHistory();
        row.fdid = fdid;
        row.pollIndex = pollIndex;
        row.operation = operation;
        row.ok = ok;
        row.at = at;
        row.fields = fields;
        row.type = type;
        row.remoteCode = remoteCode;
        row.message = message;
        return row;
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
     * 从逐寄存器影子重建**本读块**的比对基准（不再读已停写的 service-last-polling）。
     *
     * <p>块的字段 = 该读方法应答里定义的字段（名 == 寄存器名，见 {@code ModbusResponseParser}）。
     * 名称/单位以服务定义 {@code configSnapshot.registries} 为准：按 {@code reg.index} 匹配影子
     * {@code registerIndex}、键用 {@code reg.name}。每个字段取它寄存器影子的值 / 时刻 / 故障态；
     * 字段还没采到（无影子）说明基准里没有它，{@code ModbusHistoryPolicy.decide} 自会把它当
     * 「变化」处理。</p>
     *
     * <p>service 未落库（configSnapshot 为 null）或整个块一只影子都没有时返回 null ——
     * 那才是真·首次采集（会 {@code insert} 全新影子）。</p>
     */
    private static ModbusPollingBaseline blockBaseline(
            FieldDevice fd,
            ModbusPoll poll,
            List<ModbusShadow> shadows
    ) {
        Map<Integer, ModbusShadow> byIndex = new HashMap<>();
        if (shadows != null) {
            for (ModbusShadow shadow : shadows) {
                if (shadow != null && shadow.registerIndex != null) {
                    byIndex.put(shadow.registerIndex, shadow);
                }
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Date last = null;
        String lastError = null;
        if (fd != null && fd.configSnapshot != null && fd.configSnapshot.registries != null) {
            Set<String> defined = ModbusShadowPlan.definedFields(ModbusPoll.responseFields(poll));
            for (ModbusRegistry reg : fd.configSnapshot.registries) {
                if (reg == null || reg.index == null || !defined.contains(reg.name)) {
                    continue;
                }
                ModbusShadow shadow = byIndex.get(reg.index);
                if (shadow == null) {
                    continue;
                }
                if (shadow.value != null) {
                    values.put(reg.name, shadow.value);
                }
                if (shadow.recordedAt != null && (last == null || shadow.recordedAt.after(last))) {
                    last = shadow.recordedAt;
                }
                if (shadow.lastError != null) {
                    lastError = shadow.lastError;
                }
            }
        }
        if (values.isEmpty() && last == null && lastError == null) {
            return null;
        }
        return new ModbusPollingBaseline(values.isEmpty() ? null : values, last, lastError);
    }
}
