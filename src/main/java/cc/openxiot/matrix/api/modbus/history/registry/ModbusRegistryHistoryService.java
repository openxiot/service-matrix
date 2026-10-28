package cc.openxiot.matrix.api.modbus.history.registry;

import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.history.ModbusRegistryHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusRegistryHistoryRepository;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.modbus.fd.FieldDeviceRepository;
import cc.openxiot.matrix.api.modbus.history.ModbusRegistryHistorySample;
import cc.openxiot.matrix.api.modbus.history.ModbusRegistryHistoryWindow;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 区间曲线的读取（见 Modbus-History.md 第五节）。与 {@link ModbusHistoryWriteService} 分开：
 * 那边只管写，且「先明细后影子」的写入顺序是它的核心约束，混进查询代码会被淹掉。
 *
 * <p><b>区间读取的硬上限</b>：一次最多取回 {@link #MAX_ROWS} 条原始样本，超了**报错**而不是静默截断
 * —— 截断会把曲线的尾巴悄悄抹掉（前端看起来一切正常），而报错至少能让人把时间范围缩小。
 * 上限只挡「一个月 × 5 秒」这种量级；正常区间（几百到几千条）连边都碰不到，降采样在内存里做
 * （见 {@link ModbusRegistryHistoryWindow}：逻辑必须能单测，而这个仓库跑不了要连库的测试）。</p>
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

    /** 只用来按 {@code registryIndex} 解析寄存器（只读服务定义的点表快照，见 {@link #resolveRegisterByIndex}）。 */
    @Inject
    FieldDeviceRepository fdRepo;

    /**
     * 区间查询的结果：规范化之后的入参（Resource 要原样回给前端）加上整形后的点。
     *
     * @param field 寄存器名（展示用）：请求身份是 {@code registryIndex}，响应仍带名字
     */
    public record RangeResult(
            Date from,
            Date to,
            int maxPoints,
            String field,
            ModbusRegistryHistoryWindow.Window window
    ) {
    }

    /**
     * 某只寄存器在 {@code [from, to]} 上的曲线数据：carry-in + 原始点，点太多则降采样成桶。
     *
     * @param pollIndex     旧前端的请求参数，接受但忽略（逐寄存器后不再需要方法序号）
     * @param registryIndex 寄存器序号（1 起），本接口的身份参数
     * @param to            传 null 表示「到现在」（缺 {@code from} 没有同样明确的含义，所以 {@code from} 必填）
     */
    public Uni<RangeResult> range(
            String fdid,
            Integer pollIndex,
            Integer registryIndex,
            Date from,
            Date to,
            Integer maxPoints
    ) {
        Date end = to == null ? new Date() : to;
        String err = checkPoint(fdid, registryIndex);
        if (err == null) {
            err = checkRange(from, end);
        }
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        int points = normalizeMaxPoints(maxPoints);

        // 逐寄存器：registryIndex 是寄存器在点表快照里的身份（见 configSnapshot.registries）。
        // 解析不到（服务不存在 / 序号写错 / 不可读）报错，与现有 "fdid is required" 的报错风格一致。
        return fdRepo.findById(fdid)
                .map(service -> resolveRegisterByIndex(service, registryIndex))
                .chain(reg -> Uni.combine().all()
                        .unis(registryHistoryRepo.findCarryIn(fdid, reg.index, from),
                                registryHistoryRepo.findRange(fdid, reg.index, from, end, MAX_ROWS + 1))
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
                            ModbusRegistryHistorySample carryIn =
                                    carry != null && !Boolean.TRUE.equals(carry.failure)
                                            ? sampleOf(carry) : null;
                            return new RangeResult(from, end, points, reg.name,
                                    ModbusRegistryHistoryWindow.of(carryIn, samplesOf(events),
                                            from.getTime(), end.getTime(), points));
                        })));
    }

    // ===== 逐寄存器 =====

    /**
     * registryIndex → 服务点表快照里的寄存器。序号解析不到（服务没了 / 配置写错）、或那只寄存器
     * 不可读（只写 W）一律报错 —— 与「fdid is required」的报错风格一致。
     */
    private static ModbusRegistry resolveRegisterByIndex(FieldDevice fd, Integer registryIndex) {
        if (fd != null && fd.configSnapshot != null && fd.configSnapshot.registries != null) {
            for (ModbusRegistry reg : fd.configSnapshot.registries) {
                if (reg != null && Objects.equals(registryIndex, reg.index)) {
                    if ("R".equals(reg.access) || "RW".equals(reg.access)) {
                        return reg;
                    }
                    break;
                }
            }
        }
        throw new IllegalArgumentException("registry index not readable: " + registryIndex);
    }

    /** 区间内的事件 → 喂给 {@link ModbusRegistryHistoryWindow} 的临时样本（只留 {@code at}/{@code value}）；失败事件丢弃。 */
    private static List<ModbusRegistryHistorySample> samplesOf(List<ModbusRegistryHistory> events) {
        List<ModbusRegistryHistorySample> samples = new ArrayList<>();
        if (events != null) {
            for (ModbusRegistryHistory event : events) {
                if (event != null && !Boolean.TRUE.equals(event.failure)) {
                    samples.add(sampleOf(event));
                }
            }
        }
        return samples;
    }

    /** 寄存器成功事件 → 喂给 {@link ModbusRegistryHistoryWindow} 的临时样本（只取窗口化要用的 {@code at}/{@code value}）。
     *  用内存样本 {@link ModbusRegistryHistorySample} 当中间载体，与看板字段曲线那条路共用同一套已测的整形逻辑。 */
    private static ModbusRegistryHistorySample sampleOf(ModbusRegistryHistory event) {
        return new ModbusRegistryHistorySample(event.at, event.value);
    }

    // ===== 参数 =====

    private static String checkPoint(String fdid, Integer registryIndex) {
        if (isBlank(fdid)) {
            return "fdid is required";
        }
        if (registryIndex == null) {
            return "registryIndex is required";
        }
        if (registryIndex < 1) {
            return "registryIndex must be a positive integer";
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