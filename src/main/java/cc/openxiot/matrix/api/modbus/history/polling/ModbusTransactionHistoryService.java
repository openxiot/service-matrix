package cc.openxiot.matrix.api.modbus.history.polling;

import cc.openxiot.matrix.typedef.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.db.modbus.history.ModbusTransactionHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusTransactionHistoryRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 故障清单的读取（见 Modbus-History.md 第五节）。与 {@link ModbusHistoryService} 分开：
 * 那边只管写，且「先明细后影子」的写入顺序是它的核心约束，混进查询代码会被淹掉。
 *
 * <p>Grep 请求按数据源拆成两个 service：本类是 polling 侧的故障列表；区间曲线在
 * {@code ModbusRegistryHistoryService}。</p>
 */
@ApplicationScoped
public class ModbusTransactionHistoryService {

    /** 故障列表的条数缺省值与封顶值。 */
    public static final int DEFAULT_FAILURE_LIMIT = 200;
    public static final int MAX_FAILURE_LIMIT = 1_000;

    @Inject
    ModbusTransactionHistoryRepository pollingHistoryRepo;

    /**
     * 故障列表的结果。
     *
     * @param from      规范化之后的区间起点（{@code to} 缺省时补的是「现在」，要回给前端）
     * @param to        规范化之后的区间终点
     * @param limit     实际生效的条数上限
     * @param items     明细，按时间倒序（最近的在前）
     * @param truncated true = 还有更早的故障没取回来（前端要提示「时间范围太宽」）
     */
    public record FailureResult(
            Date from,
            Date to,
            int limit,
            List<ModbusTransactionHistory> items,
            boolean truncated
    ) {
    }

    /**
     * 某个服务（可选某个方法、某类故障）在 {@code [from, to]} 上的故障记录，按时间倒序。
     *
     * @param pollingIndex 传 null = 该服务下所有方法
     * @param type          传 null = 不限分类；给了就必须是 {@link ModbusFailureType} 里的名字
     */
    public Uni<FailureResult> failures(
            String serviceId,
            Integer pollingIndex,
            String type,
            Date from,
            Date to,
            Integer limit
    ) {
        if (isBlank(serviceId)) {
            return Uni.createFrom().failure(new IllegalArgumentException("serviceId is required"));
        }
        // 单服务只是批量那条路的一个元素：排序 / 截断的口径只有一份，免得两条路各写一遍、日后改漏一边
        return failuresOfServices(List.of(serviceId), pollingIndex, type, from, to, limit);
    }

    /**
     * **一批服务**在同一区间上的故障记录，合并成一条时间倒序的清单（空间级查询：
     * {@code ModbusTransactionHistoryResource.failures} 不传 {@code serviceId} 时走这里）。
     *
     * <p>为什么是「逐服务各查一次再合并」而不是一条跨服务的查询：故障集合的索引是
     * {@code (serviceId, pollingIndex, at)}，跨服务查等于丢掉索引前缀做全表扫；而一个空间下的服务
     * 只有十几条，各查一次的量级完全可控。操作日志按 limit 截到「该服务最近的 limit+1 条」，
     * 合并出来的行数本就不大 —— 所以合并与排序放在内存里做，
     * 与「不传 pollingIndex 时排序靠内存」是同一个判断。</p>
     *
     * <p><b>逐服务多取一条（limit + 1）不影响全局结果</b>：全局前 limit 条必然落在「该服务的前
     * limit + 1 条」里，所以按服务先截一刀再合并，得到的仍是真正的全局前 limit 条；
     * {@code truncated} 也据此判 —— 合并后仍多于 limit，就说明还有更早的没取回来。</p>
     *
     * @param serviceIds 服务 id 清单；空清单 = 这个空间下一个服务都没有，给空结果而不是报错
     */
    public Uni<FailureResult> failuresOfServices(
            List<String> serviceIds,
            Integer pollingIndex,
            String type,
            Date from,
            Date to,
            Integer limit
    ) {
        Date end = to == null ? new Date() : to;
        String err = checkRange(from, end);
        if (err == null) {
            err = checkType(type);
        }
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        int size = normalizeLimit(limit);
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(new FailureResult(from, end, size, List.of(), false));
        }
        String failureType = isBlank(type) ? null : type;
        List<Uni<List<ModbusTransactionHistory>>> queries = serviceIds.stream()
                .map(id -> pollingHistoryRepo.findFailureRange(id, pollingIndex, failureType, from, end, size + 1))
                .toList();

        // 并发取：服务之间没有依赖，串行只会把总耗时乘上服务数
        return Uni.combine().all().unis(queries)
                .with(groups -> merge(from, end, size, flatten(groups)));
    }

    /**
     * 合并各服务的结果：按 {@code at} 倒序（同一时刻的保持传入顺序 —— 排序是稳定的，于是按服务清单的顺序排），
     * 取前 {@code limit} 条；多出来的就说明还有更早的没取回来。
     *
     * <p>抽成静态方法是为了能单测：这个仓库跑不了要连库的测试，而「合并之后对不对」恰恰是最该
     * 被断言的部分（见 {@code ModbusTransactionHistoryServiceTest}）。</p>
     */
    static FailureResult merge(Date from, Date to, int limit, List<ModbusTransactionHistory> rows) {
        List<ModbusTransactionHistory> sorted = new ArrayList<>(rows);
        // at 理论上不会缺（写入时必带），但真缺了也得排得出结果：按 0 算，倒序里落在最后
        sorted.sort(Comparator.comparingLong(
                (ModbusTransactionHistory row) -> row.at == null ? 0L : row.at.getTime()).reversed());
        boolean truncated = sorted.size() > limit;
        return new FailureResult(from, to, limit,
                List.copyOf(truncated ? sorted.subList(0, limit) : sorted), truncated);
    }

    /** 把 combine 的「每个服务一组」摊平成一个清单（Iterable 版 combine 的泛型被擦成了 List<?>）。 */
    private static List<ModbusTransactionHistory> flatten(List<?> groups) {
        List<ModbusTransactionHistory> merged = new ArrayList<>();
        for (Object group : groups) {
            if (group instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof ModbusTransactionHistory history) {
                        merged.add(history);
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