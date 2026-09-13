package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.api.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceFailure;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceFailureRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistoryRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadowRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Date;
import java.util.List;

/**
 * 采集历史的读取（见 Modbus-History.md 第五节）：当前值、区间曲线、故障列表。
 *
 * <p>与 {@link ModbusHistoryService} 分开：那边只管写，且「先明细后影子」的写入顺序是它的核心约束，
 * 混进查询代码会被淹掉；这边的调用方只有 Resource，没有写侧那套顺序要求。</p>
 *
 * <p><b>区间读取的硬上限</b>：一次最多取回 {@link #MAX_ROWS} 条原始样本，超了**报错**而不是静默截断
 * —— 截断会把曲线的尾巴悄悄抹掉（前端看起来一切正常），而报错至少能让人把时间范围缩小。
 * 上限只挡「一个月 × 5 秒」这种量级；正常区间（几百到几千条）连边都碰不到，降采样在内存里做
 * （见 {@link ModbusHistoryWindow}：逻辑必须能单测，而这个仓库跑不了要连库的测试）。</p>
 *
 * <p>参数校验放在这里而不是 Resource：Resource 只做鉴权与编码，而校验是能被单测覆盖的部分。</p>
 */
@ApplicationScoped
public class ModbusHistoryQueryService {

    /** 一次区间查询允许取回的原始样本数上限；超过即报错，见类注释。 */
    public static final int MAX_ROWS = 20_000;

    /** 返回点数上限的缺省值与封顶值（超出即按封顶处理：这只是个性能提示，不值得让请求失败）。 */
    public static final int DEFAULT_MAX_POINTS = 500;
    public static final int MAX_MAX_POINTS = 2_000;

    /** 故障列表的条数缺省值与封顶值。 */
    public static final int DEFAULT_FAILURE_LIMIT = 200;
    public static final int MAX_FAILURE_LIMIT = 1_000;

    @Inject
    ModbusServiceShadowRepository shadowRepo;

    @Inject
    ModbusServiceHistoryRepository historyRepo;

    @Inject
    ModbusServiceFailureRepository failureRepo;

    /**
     * 区间查询的结果：规范化之后的入参（Resource 要原样回给前端）加上整形后的点。
     */
    public record RangeResult(Date from, Date to, int maxPoints, ModbusHistoryWindow.Window window) {
    }

    /**
     * 故障列表的结果。
     *
     * @param from      规范化之后的区间起点（{@code to} 缺省时补的是「现在」，要回给前端）
     * @param to        规范化之后的区间终点
     * @param limit     实际生效的条数上限
     * @param items     明细，按时间倒序（最近的在前）
     * @param truncated true = 还有更早的故障没取回来（前端要提示「时间范围太宽」）
     */
    public record FailureResult(Date from, Date to, int limit, List<ModbusServiceFailure> items,
                                boolean truncated) {
    }

    /**
     * 一个服务下所有方法的当前值。
     *
     * <p>没有影子的方法**不会出现在结果里** —— 那表示它从没被调用过（或从没成功过），
     * 与「有值但很旧」是两回事，前端要能分开渲染。要完整的 methods 清单得读服务定义。</p>
     */
    public Uni<List<ModbusServiceShadow>> currentValues(String serviceId) {
        if (isBlank(serviceId)) {
            return Uni.createFrom().failure(new IllegalArgumentException("serviceId is required"));
        }
        return shadowRepo.listByServiceId(serviceId);
    }

    /**
     * 某个点位在 {@code [from, to]} 上的曲线数据：carry-in + 原始点，点太多则降采样成桶。
     *
     * @param to 传 null 表示「到现在」（缺 {@code from} 没有同样明确的含义，所以 {@code from} 必填）
     */
    public Uni<RangeResult> range(String serviceId, Integer functionIndex, String field,
                                  Date from, Date to, Integer maxPoints) {
        Date end = to == null ? new Date() : to;
        String err = checkPoint(serviceId, functionIndex, field);
        if (err == null) {
            err = checkRange(from, end);
        }
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        int points = normalizeMaxPoints(maxPoints);

        return Uni.combine().all()
                .unis(historyRepo.findCarryIn(serviceId, functionIndex, field, from),
                        historyRepo.findRange(serviceId, functionIndex, field, from, end, MAX_ROWS + 1))
                .asTuple()
                .map(tuple -> {
                    List<ModbusServiceHistory> rows = tuple.getItem2();
                    if (rows.size() > MAX_ROWS) {
                        // 多取一条就是为了在这里判「超了」：报错比返回一段被悄悄截掉的曲线诚实
                        throw new IllegalArgumentException("time range too wide: more than " + MAX_ROWS
                                + " samples, narrow from/to or use a coarser field");
                    }
                    return new RangeResult(from, end, points,
                            ModbusHistoryWindow.of(tuple.getItem1(), rows, from.getTime(), end.getTime(), points));
                });
    }

    /**
     * 某个服务（可选某个方法、某类故障）在 {@code [from, to]} 上的故障记录，按时间倒序。
     *
     * @param functionIndex 传 null = 该服务下所有方法
     * @param type          传 null = 不限分类；给了就必须是 {@link ModbusFailureType} 里的名字
     */
    public Uni<FailureResult> failures(String serviceId, Integer functionIndex, String type,
                                       Date from, Date to, Integer limit) {
        Date end = to == null ? new Date() : to;
        String err = isBlank(serviceId) ? "serviceId is required" : checkRange(from, end);
        if (err == null) {
            err = checkType(type);
        }
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        int size = normalizeLimit(limit);
        String failureType = isBlank(type) ? null : type;

        // 同样多取一条判截断：故障列表是倒序的，截掉的是更早的那些
        return failureRepo.findRange(serviceId, functionIndex, failureType, from, end, size + 1)
                .map(rows -> rows.size() > size
                        ? new FailureResult(from, end, size, List.copyOf(rows.subList(0, size)), true)
                        : new FailureResult(from, end, size, List.copyOf(rows), false));
    }

    // ===== 参数 =====

    private static String checkPoint(String serviceId, Integer functionIndex, String field) {
        if (isBlank(serviceId)) {
            return "serviceId is required";
        }
        if (functionIndex == null || functionIndex < 1) {
            return "functionIndex is required";
        }
        if (isBlank(field)) {
            return "field is required";
        }
        return null;
    }

    private static String checkRange(Date from, Date end) {
        if (from == null) {
            return "from is required";
        }
        // to 缺省即「到现在」，所以这里比的是补完缺省之后的区间：
        // 反着或零宽的区间只会查出空结果（零宽的还会让分桶退化），不如直接说清楚
        if (!end.after(from)) {
            return "from must be before to";
        }
        return null;
    }

    private static String checkType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            ModbusFailureType.valueOf(type);
            return null;
        } catch (IllegalArgumentException e) {
            // 不合法就报错而不是返回空列表：拼错的分类和「这段时间没这类故障」在界面上长得一样
            return "unknown failure type: " + type;
        }
    }

    private static int normalizeMaxPoints(Integer maxPoints) {
        if (maxPoints == null) {
            return DEFAULT_MAX_POINTS;
        }
        if (maxPoints < 1) {
            return 1;
        }
        return Math.min(maxPoints, MAX_MAX_POINTS);
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_FAILURE_LIMIT;
        }
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_FAILURE_LIMIT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
