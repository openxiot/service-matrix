package cc.openxiot.matrix.api.modbus.history.registry;

import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.history.ModbusRegistryHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusRegistryHistoryRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import cc.openxiot.matrix.api.modbus.history.ModbusHistorySample;
import cc.openxiot.matrix.api.modbus.history.ModbusHistoryWindow;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 区间曲线的读取（见 Modbus-History.md 第五节）。与 {@link ModbusHistoryService} 分开：
 * 那边只管写，且「先明细后影子」的写入顺序是它的核心约束，混进查询代码会被淹掉。
 *
 * <p><b>区间读取的硬上限</b>：一次最多取回 {@link #MAX_ROWS} 条原始样本，超了**报错**而不是静默截断
 * —— 截断会把曲线的尾巴悄悄抹掉（前端看起来一切正常），而报错至少能让人把时间范围缩小。
 * 上限只挡「一个月 × 5 秒」这种量级；正常区间（几百到几千条）连边都碰不到，降采样在内存里做
 * （见 {@link ModbusHistoryWindow}：逻辑必须能单测，而这个仓库跑不了要连库的测试）。</p>
 */
@ApplicationScoped
public class ModbusRegistryHistoryService {

    /** 一次区间查询允许取回的原始样本数上限；超过即报错，见类注释。 */
    public static final int MAX_ROWS = 20_000;

    /** 返回点数上限的缺省值与封顶值（超出即按封顶处理：这只是个性能提示，不值得让请求失败）。 */
    public static final int DEFAULT_MAX_POINTS = 500;
    public static final int MAX_MAX_POINTS = 2_000;

    @Inject
    ModbusRegistryHistoryRepository registryHistoryRepo;

    /** 只用来把 field 名解析成寄存器序号（只读服务定义的点表快照，见 {@link #resolveRegister}）。 */
    @Inject
    ModbusServiceRepository serviceRepo;

    /**
     * 区间查询的结果：规范化之后的入参（Resource 要原样回给前端）加上整形后的点。
     */
    public record RangeResult(
            Date from,
            Date to,
            int maxPoints,
            ModbusHistoryWindow.Window window
    ) {
    }

    /**
     * 某个点位在 {@code [from, to]} 上的曲线数据：carry-in + 原始点，点太多则降采样成桶。
     *
     * @param to 传 null 表示「到现在」（缺 {@code from} 没有同样明确的含义，所以 {@code from} 必填）
     */
    public Uni<RangeResult> range(
            String serviceId,
            Integer pollingIndex,
            String field,
            Date from,
            Date to,
            Integer maxPoints
    ) {
        Date end = to == null ? new Date() : to;
        String err = checkPoint(serviceId, field);
        if (err == null) {
            err = checkRange(from, end);
        }
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        int points = normalizeMaxPoints(maxPoints);

        // 逐寄存器：field 名 == 寄存器名（见 configSnapshot.registries），先按服务定义把它解析成
        // registerIndex。解析不到（服务不存在 / 字段写错）报错，与现有 "serviceId is required"
        // 的报错风格一致；pollingIndex 为兼容旧前端请求仍接受、仅忽略（逐寄存器后不再需要它）。
        return serviceRepo.findById(serviceId)
                .map(service -> resolveRegister(service, field))
                .chain(reg -> Uni.combine().all()
                        .unis(registryHistoryRepo.findCarryIn(serviceId, reg.index, from),
                                registryHistoryRepo.findRange(serviceId, reg.index, from, end, MAX_ROWS + 1))
                        .asTuple()
                        .map(Unchecked.function(tuple -> {
                            ModbusRegistryHistory carry = tuple.getItem1();
                            List<ModbusRegistryHistory> events = tuple.getItem2();
                            if (events.size() > MAX_ROWS) {
                                // 多取一条就是为了在这里判「超了」：报错比返回一段被悄悄截掉的曲线诚实
                                throw new IllegalArgumentException("time range too wide: more than " + MAX_ROWS
                                        + " samples, narrow from/to or use a coarser field");
                            }
                            // carry-in 必须是**成功**事件才有值（失败事件没落到值）：曲线从窗口起点的状态
                            // 由最后一次成功续上
                            ModbusHistorySample carryIn =
                                    carry != null && !Boolean.TRUE.equals(carry.failure)
                                            ? sampleOf(carry) : null;
                            return new RangeResult(from, end, points,
                                    ModbusHistoryWindow.of(carryIn, samplesOf(events),
                                            from.getTime(), end.getTime(), points));
                        })));
    }

    // ===== 逐寄存器 =====

    /**
     * field 名 → 服务点表快照里的寄存器。name != 寄存器名（服务没了 / 字段写错 / 纯命令老服务）
     * 一律报错 —— 与「serviceId is required」的报错风格一致，且与 {@link ModbusHistoryService#record}
     * 的「名字对上才写寄存器侧」同一条匹配规则。
     */
    private static ModbusRegistry resolveRegister(ModbusService service, String field) {
        if (service == null || service.configSnapshot == null
                || service.configSnapshot.registries == null) {
            throw new IllegalArgumentException("field not a readable register: " + field);
        }
        for (ModbusRegistry reg : service.configSnapshot.registries) {
            if (reg != null && field.equals(reg.name)) {
                return reg;
            }
        }
        throw new IllegalArgumentException("field not a readable register: " + field);
    }

    /** 区间内的事件 → 喂给 {@link ModbusHistoryWindow} 的临时样本（只留 {@code at}/{@code value}）；失败事件丢弃。 */
    private static List<ModbusHistorySample> samplesOf(List<ModbusRegistryHistory> events) {
        List<ModbusHistorySample> samples = new ArrayList<>();
        if (events != null) {
            for (ModbusRegistryHistory event : events) {
                if (event != null && !Boolean.TRUE.equals(event.failure)) {
                    samples.add(sampleOf(event));
                }
            }
        }
        return samples;
    }

    /** 寄存器成功事件 → 喂给 {@link ModbusHistoryWindow} 的临时样本（只取窗口化要用的 {@code at}/{@code value}）。
     *  用内存样本 {@link ModbusHistorySample} 当中间载体，与看板字段曲线那条路共用同一套已测的整形逻辑。 */
    private static ModbusHistorySample sampleOf(ModbusRegistryHistory event) {
        return new ModbusHistorySample(event.at, event.value);
    }

    // ===== 参数 =====

    private static String checkPoint(String serviceId, String field) {
        if (isBlank(serviceId)) {
            return "serviceId is required";
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

    private static int normalizeMaxPoints(Integer maxPoints) {
        if (maxPoints == null) {
            return DEFAULT_MAX_POINTS;
        }
        if (maxPoints < 1) {
            return 1;
        }
        return Math.min(maxPoints, MAX_MAX_POINTS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}