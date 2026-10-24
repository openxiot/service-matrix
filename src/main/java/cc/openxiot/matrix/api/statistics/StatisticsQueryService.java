package cc.openxiot.matrix.api.statistics;

import cc.openxiot.matrix.api.device.DeviceService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.alarm.ModbusAlarm;
import cc.openxiot.matrix.db.modbus.alarm.ModbusAlarmRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusPollingHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusPollingHistoryRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 数据看板（首页）的聚合统计：一个空间及其**子树**内的设备、服务、告警、故障各一份数字。
 *
 * <p>为什么要有这个接口，而不是让前端拿现成的清单接口自己算：现成的清单接口都带 limit
 * （告警 200 / 1000、故障 1000），且两份 {@code summary} **只统计本批 items** ——
 * 拿它们算「今日告警 / 告警曲线 / 类型分布」得到的是**被截断的下限**，而卡片上完全看不出来。
 * 这里不打 limit，量级由 {@link #MAX_WINDOW}（窗口上限）兜住。</p>
 *
 * <h3>范围与口径</h3>
 *
 * <p><b>范围</b>：先取整棵子树，再「逐空间各查一次」，与 {@code SpaceResource.getGraph} 同一条路
 * （服务的「按空间查」索引在 {@code device.space.spaceId} 上，一个项目下的空间只有几个）。</p>
 *
 * <p><b>服务</b>这一次同时给出三样东西：总数、按点表分组的条数、以及告警与故障要逐服务查的 id 清单。
 * 三者出自**同一次读取**，故「按点表分组的条数之和 == 服务总数」是构造出来的，不靠约定 ——
 * 这也是这里不复用 {@code ModbusServiceService.subtreeServiceIds} 的原因（它只带 id 与名字，
 * 点表得再查一次，两次读之间没有共同的真值）。过滤条件与它逐字相同
 * （{@code device.space.spaceId} 逐空间取再拍平去重），所以「服务总量」与告警页看到的范围一致。</p>
 *
 * <p><b>告警 / 故障</b>：逐个 serviceId 各查一次再在内存里归并，**不用
 * {@code Filters.in("serviceId", …)}** —— 两个集合的索引都是 {@code (serviceId, pollIndex, at)}，
 * 跨服务查会丢掉索引前缀（与 {@code ModbusPollHistoryService} 的说明同一条）。</p>
 *
 * <p><b>不做聚合管道</b>：全后端的 {@code Aggregates} / {@code $group} 零命中，分桶与归并一律在内存里做
 * （与 {@code ModbusHistoryWindow} 的降采样、{@code ModbusAlarmCodec.encodeSummary} 的计数同一路线），
 * 免得把「DocumentDB 支持哪些算子」这件事引进依赖。</p>
 *
 * <p>校验（{@link #checkRange}）与三份归并（{@link #devicesOf} / {@link #servicesOf} /
 * {@link #hourly} / {@link #tally}）都是**静态纯函数**：这个模块跑不了要连库的测试，
 * 能被单测覆盖的恰好就是「算得对不对」这一部分。</p>
 */
@ApplicationScoped
public class StatisticsQueryService {

    /** 一个整点桶的宽度。 */
    public static final long HOUR = 3_600_000L;

    /**
     * 窗口上限：31 天。
     *
     * <p>本接口**不打 limit**（静默截断正是它要摆脱的东西），窗口就是唯一的量级闸门 ——
     * 与 {@code ModbusRegistryHistoryService.MAX_ROWS} 那条「时间范围太宽」同一思路。</p>
     */
    public static final long MAX_WINDOW = 31L * 24 * HOUR;

    /** 分组键缺失时归到哪个桶（与 {@code ModbusAlarmCodec.encodeSummary} 同一个桶名）。 */
    public static final String UNKNOWN = "UNKNOWN";

    @Inject
    ModbusServiceRepository serviceRepo;

    @Inject
    ModbusAlarmRepository alarmRepo;

    @Inject
    ModbusPollingHistoryRepository pollingHistoryRepo;

    @Inject
    SpaceService spaceService;

    @Inject
    DeviceService deviceService;

    /* ----------------------------------------------------------------------------------------------
     * 结果
     * ----------------------------------------------------------------------------------------------*/

    /**
     * 看板一屏所需的全部数字。
     *
     * @param from 区间起点（必填，原样回给前端）
     * @param to   区间终点（缺省时补的是「现在」，必须回给前端 —— 不回的话前端不知道曲线的右端是几点）
     */
    public record Overview(Date from, Date to, DeviceStats devices, ServiceStats services,
                           AlarmStats alarms, FailureStats failures) {
    }

    /**
     * 设备：总数、在线数、按类型的条数。
     *
     * @param online 「在线设备」的数字，判据就是 {@code DeviceEntity.online}
     */
    public record DeviceStats(int total, int online, List<Count> byType) {
    }

    /**
     * 服务：总数、按所配点表的条数。
     *
     * <p>分组键是服务内嵌快照的源点表 id（{@code configSnapshot.id}，**不是**显示名）：
     * 拼「厂家 型号」是前端的事，首页那一列与服务清单页那一列因此是同一个函数。
     * 没有快照的服务落在 {@code ""} 键上。</p>
     */
    public record ServiceStats(int total, List<Count> byConfig) {
    }

    /**
     * 告警：窗口内的总数、按告警文本的条数、按整点的时间线。
     *
     * <p>分组键是**用户自己填的告警文本**，原样给出、**永不翻译**。窗口内每个整点桶都在
     * （密集零填充，见 {@link #hourly}），故前端不必猜空缺 —— 「今日」由前端从 {@code hourly}
     * 里取桶起点在今天 00:00 之后的那些相加（今日恒被 24 小时窗口盖住）。</p>
     */
    public record AlarmStats(int total, List<Count> byText, List<Bucket> hourly) {
    }

    /**
     * 故障：窗口内的失败调用总数与按整点的时间线。
     *
     * <p>注意口径（较旧模型变了）：故障读数从操作日志过滤（每次调用一条、写时不按 message 去重），
     * 所以这里的数字是「失败了多少次调用」，不再是旧 {@code service-failures} 的「有几种失败」——
     * 失败分类/远端码仍可逐条给到，聚合统计按调用计。</p>
     */
    public record FailureStats(int total, List<Bucket> hourly) {
    }

    /** 一个分组键的条数。 */
    public record Count(String key, int count) {
    }

    /** 一个整点桶：{@code at} 是桶**起点**的 epoch 毫秒（UTC 整点，与本地时区无关）。 */
    public record Bucket(long at, int count) {
    }

    /* ----------------------------------------------------------------------------------------------
     * 取数
     * ----------------------------------------------------------------------------------------------*/

    /**
     * 一屏的全部数字。
     *
     * @param spaceId 项目根空间（鉴权已在资源层判过）；子树含空间本身
     * @param from    区间起点，必填
     * @param to      区间终点，null = 现在
     */
    public Uni<Overview> overview(String spaceId, Date from, Date to) {
        Date end = to == null ? new Date() : to;
        String err = checkRange(from, end);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return spaceService.getDescendants(spaceId)
                .chain(spaces -> {
                    List<String> spaceIds = spaces.stream().map(space -> space.id.toString()).toList();
                    Uni<List<DeviceEntity>> devices = bySpace(spaceIds, deviceService::getBySpaceId);
                    Uni<List<ModbusService>> services = bySpace(spaceIds, serviceRepo::findConfigKeysBySpaceId);
                    // 两轮：服务的 id 清单是后两件事的查询范围，只能等它回来
                    return Uni.combine().all().unis(devices, services).asTuple()
                            .chain(tuple -> {
                                List<String> ids = serviceIds(tuple.getItem2());
                                Uni<List<ModbusAlarm>> alarms = mergeTimelines(ids.stream()
                                        .map(id -> alarmRepo.findTimeline(id, from, end)).toList());
                                Uni<List<ModbusPollingHistory>> failures = mergeTimelines(ids.stream()
                                        .map(id -> pollingHistoryRepo.findFailureTimeline(id, from, end)).toList());
                                return Uni.combine().all().unis(alarms, failures).asTuple()
                                        .map(rows -> new Overview(from, end,
                                                devicesOf(tuple.getItem1()),
                                                servicesOf(tuple.getItem2()),
                                                new AlarmStats(rows.getItem1().size(),
                                                        tally(texts(rows.getItem1())),
                                                        hourly(alarmTimes(rows.getItem1()), from, end)),
                                                new FailureStats(rows.getItem2().size(),
                                                        hourly(failureTimes(rows.getItem2()), from, end))));
                            });
                });
    }

    /**
     * 服务的 id 清单：一次读取顺便去重（{@code ModbusServiceService.subtreeServiceIds} 的 id 部分）。
     *
     * <p>服务文档的 {@code _id} 即使只投影 {@code configSnapshot._id} 也由 Mongo 默认带出（与
     * {@code ModbusServiceRepository.findBriefBySpaceId} 同一条）。逐空间取不会取到重复的，
     * 去重是保险：重复的 id 会让告警多查一次、行被数两遍。</p>
     */
    private static List<String> serviceIds(List<ModbusService> services) {
        return services.stream()
                .filter(service -> service != null && service.id != null)
                .map(service -> service.id.toHexString())
                .distinct()
                .toList();
    }

    /**
     * 逐个服务取一条时间线再拍平：服务之间没有依赖，串行只会把总耗时乘上服务数。
     *
     * <p>空清单直接给空结果 —— 这个空间下一个服务都没有就不必查库（照
     * {@code ModbusAlarmReadService.alarmsOfServices} 的兜底）。</p>
     */
    private static <T> Uni<List<T>> mergeTimelines(List<Uni<List<T>>> queries) {
        if (queries.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return Uni.join()
                .all(queries)
                .andFailFast()
                .map(groups -> groups.stream().flatMap(List::stream).toList());
    }

    /** 按空间逐个并行取，再拍平成一张表（与 {@code SpaceResource.bySpace} 同一条）。 */
    private static <T> Uni<List<T>> bySpace(List<String> spaceIds, Function<String, Uni<List<T>>> loader) {
        if (spaceIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return Uni.join()
                .all(spaceIds.stream().map(loader).toList())
                .andFailFast()
                .map(lists -> lists.stream().flatMap(List::stream).toList());
    }

    /* ----------------------------------------------------------------------------------------------
     * 归并（纯函数，可单测）
     * ----------------------------------------------------------------------------------------------*/

    /** 设备：总数、在线数、按类型（URN 第 4 段）的条数。 */
    static DeviceStats devicesOf(List<DeviceEntity> rows) {
        List<DeviceEntity> devices = rows == null ? List.of()
                : rows.stream().filter(device -> device != null).toList();
        int online = 0;
        List<String> types = new ArrayList<>();
        for (DeviceEntity device : devices) {
            if (device.online) {
                online++;
            }
            types.add(textKey(urnType(device.type)));
        }
        return new DeviceStats(devices.size(), online, tally(types));
    }

    /** 服务：总数、按源点表 id（{@code configSnapshot.id}）的条数。两者同源，故分组的条数之和恒等于总数。 */
    static ServiceStats servicesOf(List<ModbusService> rows) {
        List<String> configIds = new ArrayList<>();
        if (rows != null) {
            for (ModbusService service : rows) {
                if (service != null) {
                    configIds.add(configKey(service.configSnapshot));
                }
            }
        }
        return new ServiceStats(configIds.size(), tally(configIds));
    }

    /**
     * 整点分桶，**窗口内每个桶都在**（没有发生的整点给 0）。
     *
     * <p>密集零填充是有意的：漏桶与零桶在折线图上长得一样，前端要是自己补，
     * 「哪段没有数据」就成了前端的猜测。桶按 {@code at} 整除到整点分（UTC 整点，与本地时区无关 ——
     * 本地偏移是整小时后，本地整点与 UTC 整点是同一批时刻；非整小时时区下曲线仍按绝对时间等距，
     * 只是标签的分钟位不是 00）。</p>
     *
     * <p>落在窗口外的时刻**忽略**（查询已经过滤过，这里是纯函数自己的边界）——
     * 反正数组开在 {@code [hourStart(from), hourStart(to)]} 上，越界的下标只会被丢掉。</p>
     */
    static List<Bucket> hourly(List<Date> times, Date from, Date to) {
        long first = hourStart(from.getTime());
        long last = hourStart(to.getTime());
        int size = (int) ((last - first) / HOUR) + 1;
        int[] counts = new int[size];
        if (times != null) {
            for (Date at : times) {
                if (at == null) {
                    continue;
                }
                int index = (int) ((hourStart(at.getTime()) - first) / HOUR);
                if (index >= 0 && index < size) {
                    counts[index]++;
                }
            }
        }
        List<Bucket> buckets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            buckets.add(new Bucket(first + i * HOUR, counts[i]));
        }
        return buckets;
    }

    /**
     * 一组分组键的条数，按**条数降序、同数按键名升序**排好（同 {@code encodeSummary}），前端拿到就画。
     *
     * <p>键由调用方先归好（{@link #textKey} / {@link #configKey}）：这一层不管「缺失算哪个桶」，
     * 那是三种分组各自的口径。</p>
     */
    static List<Count> tally(List<String> keys) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (keys != null) {
            for (String key : keys) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));
        return entries.stream().map(entry -> new Count(entry.getKey(), entry.getValue())).toList();
    }

    /** 一组告警的触发时刻（{@code at} 理论上不会缺，缺了也会被 {@link #hourly} 丢掉）。 */
    private static List<Date> alarmTimes(List<ModbusAlarm> alarms) {
        List<Date> times = new ArrayList<>(alarms.size());
        for (ModbusAlarm alarm : alarms) {
            if (alarm != null) {
                times.add(alarm.at);
            }
        }
        return times;
    }

    /** 一组失败调用的发生时刻。 */
    private static List<Date> failureTimes(List<ModbusPollingHistory> failures) {
        List<Date> times = new ArrayList<>(failures.size());
        for (ModbusPollingHistory failure : failures) {
            if (failure != null) {
                times.add(failure.at);
            }
        }
        return times;
    }

    /**
     * 一组告警的告警文本（分组键）。
     *
     * <p>包可见是为了能单测「缺文本归 {@link #UNKNOWN} 桶」这一条 —— 它正是告警页 {@code byText}
     * 的桶名，两边不一致的话首页与告警页会对不上账。</p>
     */
    static List<String> texts(List<ModbusAlarm> alarms) {
        List<String> texts = new ArrayList<>(alarms.size());
        for (ModbusAlarm alarm : alarms) {
            if (alarm != null) {
                texts.add(textKey(alarm.text));
            }
        }
        return texts;
    }

    /* ----------------------------------------------------------------------------------------------
     * 参数与键
     * ----------------------------------------------------------------------------------------------*/

    /**
     * 窗口校验：{@code from} 必填、区间必须非空、不得超过 {@link #MAX_WINDOW}。
     *
     * <p>与 {@code ModbusAlarmReadService.checkRange} 的前两条逐字相同（连错误文案也一样：
     * 前端对这两个接口的报错是同一套呈现），第三条是本接口独有的量级闸门。</p>
     *
     * @return 错误文案；null = 通过
     */
    static String checkRange(Date from, Date end) {
        if (from == null) {
            return "from is required";
        }
        // to 缺省即「到现在」，所以这里比的是补完缺省之后的区间
        if (!end.after(from)) {
            return "from must be before to";
        }
        if (end.getTime() - from.getTime() > MAX_WINDOW) {
            return "time range too wide";
        }
        return null;
    }

    /** 时刻所在的整点桶起点（epoch 毫秒）。 */
    static long hourStart(long millis) {
        return Math.floorDiv(millis, HOUR) * HOUR;
    }

    /**
     * 设备类型的 URN 第 4 段（{@code urn:ns:device:dtu:…} → {@code dtu}）。
     *
     * <p>与前端 {@code UrnUtils.extractTypeName} 逐字同口径（含「不足 4 段时原样返回」），
     * 服务端别处没有 URN 解析器，故这里是那一条规则在后端的唯一落点。下发的是**这一段**而不是整条 URN，
     * 前端因此不需要 URN 知识。</p>
     */
    static String urnType(String urn) {
        if (urn == null) {
            return null;
        }
        String[] parts = urn.split(":");
        return parts.length > 3 ? parts[3] : urn;
    }

    /**
     * 显示类分组键（类型段、告警文本）：缺失归 {@link #UNKNOWN} 桶。
     *
     * <p>归桶是为了让计数与总数对得上，**不是替用户编一个名字** ——
     * 与 {@code ModbusAlarmCodec.encodeSummary} 的 {@code byText} 同一个桶、同一个理由。</p>
     */
    private static String textKey(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    /**
     * 点表的键：服务快照里的源点表 id；没有快照（或快照没有 id）归到空串。
     *
     * <p>点表 id 是**标识符**不是显示名，「没有」就是空串；拼不出名字该显示什么（首页用词典里的
     * {@code 未定义}）是前端的事。</p>
     */
    private static String configKey(ModbusConfig snapshot) {
        return snapshot == null || snapshot.id == null ? "" : snapshot.id.toHexString();
    }
}
