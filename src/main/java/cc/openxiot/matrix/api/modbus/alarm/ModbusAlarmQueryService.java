package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarm;
import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarmRepository;
import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarmRepository.RangeFilter;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 告警的读取（见 Modbus-Alarm.md 第九节）：一条按区间 + 筛选条件的清单。
 *
 * <p>与 {@link ModbusAlarmService} 分开：那边管写（采集侧评估、用户点处理），判定口径与副作用纪律
 * 是它的核心约束，混进查询代码会被淹掉；这边只管读，调用方只有 Resource。</p>
 *
 * <p><b>空间级查询是「逐服务各查一次再内存归并」</b>，与故障清单同口径：告警集合的索引是
 * {@code (serviceId, transactionIndex, at)}，跨服务查等于丢掉索引前缀做全表扫；而一个空间下的服务
 * 只有十几条。多取一条（{@code limit + 1}）就足以判出「还有更早的没取回来」，论证见
 * {@link #alarmsOfServices}。</p>
 *
 * <p>参数校验放在这里而不是 Resource：Resource 只做鉴权与编码，而校验是能被单测覆盖的部分。</p>
 */
@ApplicationScoped
public class ModbusAlarmQueryService {

    /**
     * 条数上限的缺省值与封顶值。
     *
     * <p><b>不复用故障那两个常量</b>（{@code ModbusTransactionHistoryService} 的 200 / 1000，数值碰巧相同）：
     * 两个接口的规模各自会变 —— 告警是「一次越限一条」的边沿记录，故障是去重后的持续状态，
     * 哪天其中一个要调，共用常量会让另一次改动也跟着动。跨用途复用比两行重复更糟。</p>
     */
    public static final int DEFAULT_ALARM_LIMIT = 200;
    public static final int MAX_ALARM_LIMIT = 1_000;

    @Inject
    ModbusServiceAlarmRepository alarmRepo;

    /**
     * 告警清单的结果。
     *
     * @param from      规范化之后的区间起点（必填，原样回给前端）
     * @param to        规范化之后的区间终点（缺省时补的是「现在」，必须回给前端 —— 不回的话
     *                  前端不知道它拿到的「到」是几点）
     * @param limit     实际生效的条数上限（封顶之后的值）
     * @param items     明细，按 {@code at} 倒序（最近的在前）
     * @param truncated true = 还有更早的没取回来（前端要提示「时间范围太宽」）
     */
    public record AlarmResult(Date from, Date to, int limit, List<ModbusServiceAlarm> items,
                              boolean truncated) {
    }

    /**
     * 按 id 取一条（{@code ModbusAlarmResource.handle} 用）：它要先知道这条告警属于哪个服务，
     * 才能判「它落在传入空间的子树内吗」—— 拿别人的 id 就能点，是这个接口最要紧的一条不变量。
     *
     * @param id 非法 id 由调用方先挡（Panache 的 {@code findById(String)} 在构造 ObjectId 时就抛）
     */
    public Uni<ModbusServiceAlarm> findById(ObjectId id) {
        return alarmRepo.findById(id);
    }

    /**
     * 某个服务（可选某个方法、某个出值、某级别、某个恢复 / 处理状态）在 {@code [from, to]} 上的告警。
     *
     * @param open    {@code true} 只看未恢复、{@code false} 只看已恢复、{@code null} 都要
     * @param handled 同上，针对「是否已处理」
     */
    public Uni<AlarmResult> alarms(String serviceId, Integer transactionIndex, String field, String level,
                                   Boolean open, Boolean handled, Date from, Date to, Integer limit) {
        if (isBlank(serviceId)) {
            return Uni.createFrom().failure(new IllegalArgumentException("serviceId is required"));
        }
        // 单服务只是批量那条路的一个元素：排序 / 截断的口径只有一份，免得两条路各写一遍、日后改漏一边
        return alarmsOfServices(List.of(serviceId), transactionIndex, field, level, open, handled,
                from, to, limit);
    }

    /**
     * **一批服务**在同一区间上的告警，合并成一条时间倒序的清单
     * （{@code ModbusAlarmResource.many} 不传 {@code serviceId} 时走这里）。
     *
     * <p><b>逐服务多取一条（{@code limit + 1}）不影响全局结果</b>：全局前 {@code limit} 条必然落在
     * 「该服务的前 {@code limit + 1} 条」里，所以按服务先截一刀再合并，得到的仍是真正的全局前
     * {@code limit} 条；{@code truncated} 也据此判 —— 合并后仍多于 {@code limit}，
     * 就说明还有更早的没取回来（与 {@code ModbusTransactionHistoryService.failuresOfServices} 同一论证）。</p>
     *
     * @param serviceIds 服务 id 清单；空清单 = 这个空间下一个服务都没有，给空结果而不是报错
     */
    public Uni<AlarmResult> alarmsOfServices(List<String> serviceIds, Integer transactionIndex, String field,
                                             String level, Boolean open, Boolean handled,
                                             Date from, Date to, Integer limit) {
        Date end = to == null ? new Date() : to;
        String err = checkRange(from, end);
        if (err == null) {
            err = checkLevel(level);
        }
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        int size = normalizeLimit(limit);
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(new AlarmResult(from, end, size, List.of(), false));
        }
        RangeFilter filter = new RangeFilter(transactionIndex, blankToNull(field), blankToNull(level),
                open, handled, from, end);
        List<Uni<List<ModbusServiceAlarm>>> queries = serviceIds.stream()
                .map(id -> alarmRepo.findRange(id, filter, size + 1))
                .toList();

        // 并发取：服务之间没有依赖，串行只会把总耗时乘上服务数
        return Uni.combine().all().unis(queries)
                .with(groups -> merge(from, end, size, flatten(groups)));
    }

    /**
     * 合并各服务的结果：按 {@code at} 倒序，取前 {@code limit} 条；多出来的就说明还有更早的没取回来。
     *
     * <p>抽成静态方法是为了能单测：这个仓库跑不了要连库的测试，而「合并之后对不对」恰恰是最该被
     * 断言的部分（见 {@code ModbusAlarmQueryServiceTest}）。</p>
     *
     * <p>{@code at} 理论上不会缺（写入时必带），但真缺了也得排得出结果：按 0 算，倒序里落在最后 ——
     * 与故障合并同一条兜底。</p>
     */
    static AlarmResult merge(Date from, Date to, int limit, List<ModbusServiceAlarm> rows) {
        List<ModbusServiceAlarm> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong(
                (ModbusServiceAlarm alarm) -> alarm.at == null ? 0L : alarm.at.getTime()).reversed());
        boolean truncated = sorted.size() > limit;
        return new AlarmResult(from, to, limit,
                List.copyOf(truncated ? sorted.subList(0, limit) : sorted), truncated);
    }

    /** 把 combine 的「每个服务一组」摊平成一个清单（Iterable 版 combine 的泛型被擦成了 List<?>）。 */
    private static List<ModbusServiceAlarm> flatten(List<?> groups) {
        List<ModbusServiceAlarm> merged = new ArrayList<>();
        for (Object group : groups) {
            if (group instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof ModbusServiceAlarm alarm) {
                        merged.add(alarm);
                    }
                }
            }
        }
        return merged;
    }

    // ===== 参数 =====

    private static String checkRange(Date from, Date end) {
        if (from == null) {
            return "from is required";
        }
        // to 缺省即「到现在」，所以这里比的是补完缺省之后的区间：
        // 反着或零宽的区间只会查出空结果，不如直接说清楚
        if (!end.after(from)) {
            return "from must be before to";
        }
        return null;
    }

    private static String checkLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        // 白名单只有一份（ModbusAlarmPolicy），校验器与判定器共用
        if (!ModbusAlarmPolicy.LEVELS.contains(level)) {
            // 不合法就报错而不是返回空列表：拼错的级别和「这段时间没这个级别的告警」在界面上长得一样
            return "unknown alarm level: " + level;
        }
        return null;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_ALARM_LIMIT;
        }
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_ALARM_LIMIT);
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
