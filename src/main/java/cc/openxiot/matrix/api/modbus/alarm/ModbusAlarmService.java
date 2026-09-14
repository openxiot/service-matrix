package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarm;
import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarmRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阈值告警的评估：一次采集之后，「该开哪些、该关哪些」的落库（见 Modbus-Alarm.md）。
 *
 * <p>与 {@code ModbusHistoryService} 的关系是**并列的两件副作用**，不是串在成功路径上：
 * 挂了新的历史行、也挂了告警行，两件事各有各的失败重试（下一个仍越限的样本会重试插入、
 * 下一个仍正常的样本会重试关闭）—— 因为它们各自对库幂等，所以这个特性比故障记录简单的地方
 * 就在于**不需要「先明细后影子」那套顺序不变量**。</p>
 *
 * <p>但**后果等级不同**：历史多一行是信息，告警多一条是页面上同一件事出现两次、
 * 而未处理计数永远不对（用户处理了 A，B 还挂着）。所以插入走
 * {@link ModbusServiceAlarmRepository#insertOpen}（唯一部分索引 + 重复键吞掉），
 * 关闭走按整个开行标识的 {@code updateMany}。这些口径都在仓储里，本类只负责「判什么、什么时候判」。</p>
 *
 * <p>失败由调用方处置：{@code ModbusServiceService} 只记日志，既不改写 invoke 的成功响应、
 * 也不改写它的失败响应 —— 值已经拿到了，告警是副作用。</p>
 */
@ApplicationScoped
public class ModbusAlarmService {

    /**
     * 「判不了」的日志每条最多报一次（进程内），超过这个规模就把记忆清空重来。
     *
     * <p>为什么需要这层：判不了的形态之一是**定义与运行期的值对不上**（老数据 / 设备固件与点表不符），
     * 那是持续状态 —— 5 秒一个周期，一天就是一万七千行，WARN 刷到这个程度只会训练人忽略它。
     * 说一次、说清楚，比每周期重复一遍有用。清空重来是给「坏定义比这个数还多」留的退化路径：
     * 那种情况下多报几轮是应该的。</p>
     */
    private static final int MAX_REPORTED_SKIPS = 256;

    @Inject
    ModbusServiceAlarmRepository alarmRepo;

    @Inject
    Logger logger;

    /** 建索引开关，与点表 / 服务 / 历史共用同一个。 */
    @ConfigProperty(name = "modbus.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    /** 已经报过的「判不了」键（{@code serviceId#functionIndex#field}），进程内去重。 */
    private final Set<String> reportedSkips = ConcurrentHashMap.newKeySet();

    void init(@Observes StartupEvent event) {
        if (!indexEnabled) {
            return;
        }
        alarmRepo.ensureIndexes().await().indefinitely();
    }

    /**
     * 评估一次采集结果，把该开的开出来、该关的关掉。
     *
     * @param values 本次采集的「出值名 → 值」（{@code ModbusResponseParser} 的产物）；写方法为空对象
     * @return 完成信号；**失败照常往上抛**，由调用方决定记日志还是咽下去
     */
    public Uni<Void> evaluate(ModbusService service, ModbusServiceFunction function, JsonObject values) {
        // 第一层闸门：没配告警的服务（也就是绝大多数）在这里返回，**一次数据库操作都不做**
        if (service == null || service.id == null || function == null || function.index == null
                || !ModbusAlarmPolicy.hasEnabledAlarm(function)) {
            return Uni.createFrom().voidItem();
        }
        String serviceId = service.id.toHexString();
        // 拷一份：JsonObject 内部的 map 是活的（与 ModbusHistoryService 同一条理由）
        Map<String, Object> sample = values == null
                ? Map.of()
                : new LinkedHashMap<>(values.getMap());
        Date now = new Date();

        // 一个方法配了 3 个字段也只查这一次：拿到的是「这个方法当前哪些出值开着」这张集合，
        // 它**就是**状态本身（见 Modbus-Alarm.md 第三节）
        return alarmRepo.listOpenFields(serviceId, function.index)
                .chain(open -> apply(serviceId, function, sample, new HashSet<>(open), now));
    }

    /**
     * 用户点「处理」：给这条告警写回执（处理人 + 处理时刻），返回**更新后**的那一条，
     * 前端据此就地替换该行、不整页刷新。
     *
     * <p><b>写侧只有这一处用户动作</b>，与采集侧的评估共用同一个仓储 —— 两者动的是**互不相交的字段**
     * （评估写 {@code recoveredAt} / {@code closeType} / {@code openKey}，这里写 {@code handled} /
     * {@code handledBy}），所以能同时发生而互不覆盖。仓储那条 {@code updateOne} 的过滤条件带
     * {@code {handled: {$ne: true}}}，于是重复点击只生效一次，而 {@code handledBy} 记的是
     * **第一个**处理的人（后点的不会把前一个顶掉）。</p>
     *
     * <p><b>「已经处理过了」算成功，不算错</b>：用户要的结果是「这条被处理了」，而它已经成立 ——
     * 报错会让页面为一件恰好如愿的事弹一条红字，而真正出问题（两个人同时点、或页面陈旧）的场景
     * 反而看不出区别。所以 CAS 改动为 0 时再读一次，行在就照常返回它（并记一条日志说明是谁先处理了），
     * 行不在了才报 not found。这与 {@code SpaceService} 里「删除 0 条按 not found 失败」不同：
     * 那里 0 条只有一种解释，这里有两种，读那一次就是为了分辨它们。</p>
     *
     * @param who 处理人，取它的 {@code timestamp} 当处理时刻（与 {@code creator} / {@code updater} 同口径，
     *            不另开字段）
     */
    public Uni<ModbusServiceAlarm> handle(ObjectId id, Person who) {
        if (id == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("alarm id is required"));
        }
        if (who == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("handler is required"));
        }
        String hex = id.toHexString();
        return alarmRepo.handle(id, who)
                .chain(changed -> alarmRepo.findById(id)
                        .onItem().ifNull().failWith(() ->
                                new IllegalArgumentException("modbus alarm not found: " + hex))
                        .invoke(ignored -> {
                            if (changed == null || changed == 0L) {
                                logger.infov("modbus alarm was already handled, id: {0}", hex);
                            }
                        }));
    }

    /** 判定 → 执行。三件事（开 / 按值关 / 按定义关）互不依赖，并发发出去。 */
    private Uni<Void> apply(String serviceId, ModbusServiceFunction function, Map<String, Object> sample,
                            Set<String> open, Date now) {
        ModbusAlarmPolicy.Decision decision = ModbusAlarmPolicy.decide(function, sample, open);
        reportSkips(serviceId, function, decision.skips());
        if (decision.isEmpty()) {
            // 稳态（值没越限、开着的那条还开着）走这条：一次写操作都没有
            return Uni.createFrom().voidItem();
        }

        List<Uni<Void>> ops = new ArrayList<>();
        for (ModbusAlarmPolicy.Open hit : decision.opens()) {
            ops.add(alarmRepo.insertOpen(rowOf(serviceId, function.index, hit, now)).replaceWithVoid());
        }
        if (!decision.valueCloses().isEmpty()) {
            ops.add(alarmRepo.closeByKeys(serviceId, function.index, decision.valueCloses(),
                    ModbusServiceAlarm.CLOSE_VALUE, now).replaceWithVoid());
        }
        if (!decision.definitionCloses().isEmpty()) {
            ops.add(alarmRepo.closeByKeys(serviceId, function.index, decision.definitionCloses(),
                    ModbusServiceAlarm.CLOSE_DEFINITION, now).replaceWithVoid());
        }
        // 一次评估里的所有动作共用同一个时刻：行的 at 与 recoveredAt 对得上，不至于出现
        // 「关了又开、开的时间还早于关的时间」这种读起来荒谬的历史
        return Uni.combine().all().unis(ops).discardItems();
    }

    /**
     * 判不了的出值：说一声，但每条只说一次（见 {@link #MAX_REPORTED_SKIPS}）。
     *
     * <p>不静默的理由：判不了意味着**这条告警配置当前不工作** —— 而「配了却不工作、还没有任何声音」
     * 正是这个特性要消灭的失效模式。日志带上定义与当时的值，够定位是定义漂移还是设备应答不符。</p>
     */
    private void reportSkips(String serviceId, ModbusServiceFunction function, List<ModbusAlarmPolicy.Skip> skips) {
        for (ModbusAlarmPolicy.Skip skip : skips) {
            // 去重键就是开行标识：同一个出值只报一次，字段改了名算新的
            String key = ModbusServiceAlarm.openKeyOf(serviceId, function.index, skip.field());
            if (!firstTime(reportedSkips, key, MAX_REPORTED_SKIPS)) {
                continue;
            }
            logger.warnv("modbus alarm is not comparable, service: {0}, function: {1}, field: {2}, "
                            + "value: {3}, compare: {4}, threshold: {5}, state: {6}",
                    serviceId, function.index, skip.field(), skip.sample(),
                    skip.alarm().compare, skip.alarm().threshold, skip.alarm().state);
        }
    }

    /**
     * 这个键是不是第一次见；是则记下并返回 true。
     *
     * <p>抽成静态是为了能单测：去重的边界（同一键第二次不算、记满后清空重来）不该靠读代码确认。
     * 不是线程安全的通用工具 —— 只服务上面那个并发集合。</p>
     */
    static boolean firstTime(Set<String> reported, String key, int cap) {
        if (reported.size() >= cap) {
            reported.clear();
        }
        return reported.add(key);
    }

    /** 触发那一刻的定义快照进告警行：定义是活的、行是不可变的历史（见 Modbus-Alarm.md 第八节）。 */
    private static ModbusServiceAlarm rowOf(String serviceId, Integer functionIndex,
                                            ModbusAlarmPolicy.Open hit, Date at) {
        ModbusServiceAlarm row = new ModbusServiceAlarm();
        row.serviceId = serviceId;
        row.functionIndex = functionIndex;
        row.field = hit.field();
        row.compare = hit.alarm().compare;
        row.threshold = hit.alarm().threshold;
        row.state = hit.alarm().state;
        row.level = hit.alarm().level;
        row.text = hit.alarm().text;
        row.unit = hit.unit();
        row.at = at;
        // 显式写 false（而不是留 null）：null 与 false 都算未处理，但页面上要显示一个确定的状态
        row.handled = false;
        row.sample = hit.sample();
        row.openKey = ModbusServiceAlarm.openKeyOf(serviceId, functionIndex, hit.field());
        return row;
    }
}
