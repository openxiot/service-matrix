package cc.openxiot.matrix.api.dashboard.widget;

import cc.openxiot.matrix.api.device.manipulation.ManipulationService;
import cc.openxiot.matrix.api.modbus.history.ModbusHistoryQueryService;
import cc.openxiot.matrix.api.modbus.history.ModbusHistoryRangeCodec;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService;
import cc.openxiot.matrix.db.dashboard.DashboardWidgetView;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceFailure;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cn.geekcity.xiot.spec.status.Status;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 看板的批量取数：把一屏卡片需要的数字一次性取回来，并**逐卡片**返回成功或失败。
 *
 * <p>数据源按卡片类型分几路，各自**按去重键批量取**（§8）：统计卡 / 分布图 / 告警曲线走
 * {@link StatisticsQueryService#overview}（按窗口分组），服务卡走
 * {@link ModbusHistoryQueryService#currentValues}（按 serviceId 去重），设备卡走
 * {@link ManipulationService#getShadows}（按 did 去重、**一屏一次调用**），
 * 字段曲线走 {@link ModbusHistoryQueryService#range}（按「点位 × 窗口 × 点数」去重）
 * 与其配套的 {@link ModbusHistoryQueryService#failures}（按「方法 × 窗口」去重）。</p>
 *
 * <h3>取数分组：**只给真正读它的卡片取**</h3>
 * <p>{@code overview} 是按窗口分组的，所以「哪些卡片需要 overview」决定了要发几次查询。
 * 服务卡与设备卡的时间字段都来自各自的影子（{@code recordedAt}），与窗口无关；
 * 让它们也带一个兜底窗口进去，只会凭空多出一组查询。</p>
 *
 * <h3>取数的去重：**按窗口分组**，不是「取最大窗口调一次」</h3>
 * <p>§6.6 原稿写的是「算出最大窗口，只调一次」。实现时发现那样会算错数：{@code overview} 返回的
 * {@code alarms.total} / {@code alarms.hourly} 是**窗口内**的聚合，一张要 24h 的卡片拿到 7 天的
 * 聚合结果就是错的，除非把 {@code hourly} 整段下发给前端让它自己切 —— 而那意味着
 * 每张卡片各带一份最多 744 个桶的数组，响应体积按卡片数翻倍。</p>
 * <p>所以改成：**按解析后的窗口分组，每个不同的窗口调一次 overview**。效果是
 * 预置布局那 8 张卡（窗口全是 24h）仍然只调一次，而窗口不同的卡片各拿各的正确数字，
 * 也不会出现两组不相交窗口拼起来超过 31 天把整个 render 打挂的情况。并发的请求数
 * 至多等于「不同窗口的个数」，而它 ≤ 卡片数，卡片数上限 30。</p>
 *
 * <h3>「最近 N 小时」由服务端解析成绝对时刻</h3>
 * <p>{@code window} 的 {@code last} 形态在库里存的是语义（{@code {kind: "last", hours: 24}}），
 * 渲染时才按服务端的「现在」解析成 {@code [from, to]}，并把解析结果回给前端。
 * 这一点是必须的：预置布局要是存绝对毫秒，用户第二天打开看到的还是昨天的区间。
 * 因此前端**不需要**在调 render 前做换算（§5.4 那句「由前端算成 epoch 毫秒」指的是
 * 编辑器里手动选了绝对区间的情形，即 {@code range} 形态）。</p>
 */
@ApplicationScoped
public class DashboardWidgetDataService {

    @Inject
    StatisticsQueryService statisticsQueryService;

    @Inject
    DashboardWidgetOwnershipValidator ownershipValidator;

    @Inject
    ModbusHistoryQueryService historyQueryService;

    /**
     * 只用来取服务定义（每个字段的 {@code unit} 与「是不是位」）。
     *
     * <p>顺手取自归属校验本来就要读的那份定义，而不是让卡片组件自己去解析产品/点表规格：
     * 服务端手里已经有一份，前端再解一遍就要多一次异步、多一份备忘、多一个请求。</p>
     */
    @Inject
    ModbusServiceRepository serviceRepo;

    /**
     * 设备卡的数据源：设备影子（最后一次上报的值）。
     *
     * <p>与 {@link #serviceRepo} 同一个理由顺带取回：卡片要设备型号（{@code type}）才能让前端
     * 去产品规格里查出这个属性的名字与单位，而型号在设备实体上。</p>
     */
    @Inject
    ManipulationService manipulationService;

    @Inject
    DeviceRepository deviceRepo;

    /** P1 接不上的卡片类型/维度，回给前端的原因。 */
    private static final String UNSUPPORTED_TYPE = "widget type is not supported yet: ";
    private static final String UNSUPPORTED_DIMENSION = "dimension is not supported yet: ";

    /**
     * 没有任何窗口可用时（卡片不带 {@code window}，或 window 写坏了）的兜底窗口长度。
     *
     * <p>{@code overview} 必须有 {@code from}，而「设备总量」这类卡片根本不关心时间。
     * 时间相关的字段（告警、故障）在 §6.4 的约束下**必然带窗口**
     * （{@code alarms.window} / {@code alarms.today} / {@code failures.total} 都要求 window），
     * 所以不会有卡片去读这个兜底窗口的时间部分。</p>
     */
    private static final long FALLBACK_WINDOW_MS = 3_600_000L;

    /** 一屏的取数结果：每张卡片一条，顺序与请求里的卡片一致。 */
    public record WidgetData(String spaceId, Date from, Date to, List<Item> widgets) {
    }

    /**
     * 一张卡片的结果。{@code success} 为假时只有 {@code message}，没有 {@code data} ——
     * 前端据此把这张卡渲染成错误态，其余卡片不受影响（§6.6「一张卡失败只黑一张」）。
     */
    public record Item(String id, boolean success, JsonObject data, String message) {

        static Item ok(String id, JsonObject data) {
            return new Item(id, true, data, null);
        }

        static Item failed(String id, String message) {
            return new Item(id, false, null, message);
        }
    }

    /**
     * 取一屏数据。{@code widgets} 由调用方决定来源：已存布局，或请求体里**尚未保存的草稿**
     * （编辑器预览用；成员可用，见 §6.2）。
     */
    public Uni<WidgetData> render(SpaceEntity space, List<? extends DashboardWidgetView> widgets) {
        String spaceId = space.rootSpaceId();
        // 整份请求共用一个「现在」：各卡片的窗口若各取各的 now，`last 24h` 之间会差几毫秒，
        // 于是本该共用一次查询的卡片被拆成多次
        Date now = new Date();
        Date fallbackFrom = new Date(now.getTime() - FALLBACK_WINDOW_MS);
        if (widgets == null || widgets.isEmpty()) {
            return Uni.createFrom().item(new WidgetData(spaceId, now, now, List.of()));
        }

        // 1) 按窗口归并：需要 overview 的卡片落在哪一个窗口上（不需要的走兜底窗口，
        //    它们本来就不会读时间相关的字段，但仍要归并成同一次查询）；顺带收拢
        //    另几路要的 id —— 同一个服务/设备/点位被几张卡引用只取一次
        Map<String, Window> windows = new LinkedHashMap<>();
        List<String> serviceIds = new ArrayList<>();
        List<String> dids = new ArrayList<>();
        Map<String, CurveQuery> curves = new LinkedHashMap<>();
        Map<String, FailureQuery> failures = new LinkedHashMap<>();
        for (DashboardWidgetView widget : widgets) {
            Source source = sourceOf(widget);
            if (source == Source.OVERVIEW) {
                Window window = windowOf(widget, now, fallbackFrom);
                windows.computeIfAbsent(window.key(), ignored -> window);
            } else if (source == Source.SERVICE) {
                collect(configString(widget.config(), "serviceId"), serviceIds);
            } else if (source == Source.DEVICE) {
                collect(configString(widget.config(), "did"), dids);
            } else if (source == Source.FIELD) {
                // 服务定义与服务卡共用同一批取数（曲线的 unit / step 就来自定义）：
                // 同一个服务在本屏只读一次定义，代价是只画曲线的服务也顺带取了它的影子
                collect(configString(widget.config(), "serviceId"), serviceIds);
                Window window = windowOf(widget, now, fallbackFrom);
                CurveQuery curve = CurveQuery.of(widget, window);
                if (curve != null) {
                    curves.computeIfAbsent(curve.key(), ignored -> curve);
                }
                FailureQuery failure = FailureQuery.of(widget, window);
                if (failure != null) {
                    failures.computeIfAbsent(failure.key(), ignored -> failure);
                }
            }
        }

        // 2) 先判归属，再取数。**这一步是安全边界，不是优化**：`serviceId` / `did` 是用户填的，
        //    只按 id 查（`Modbus-History.md` 当前的写法）就意味着拿到别人的 id 就能读别人的数据。
        //    已存布局同样要判 —— 保存时校验过，但那张卡片引用的服务可能在那之后被删掉了。
        return ownershipValidator.errorsByWidget(space, widgets)
                .chain(errors -> Uni.combine().all()
                        .unis(fetchOverviews(spaceId, windows), fetchServices(serviceIds),
                                fetchDevices(space.rootSpaceId(), dids), fetchCurves(curves),
                                fetchFailures(failures))
                        .asTuple()
                        .map(tuple -> build(spaceId, widgets,
                                new Sources(tuple.getItem1(), tuple.getItem2(), tuple.getItem3(),
                                        tuple.getItem4(), tuple.getItem5()),
                                errors, fallbackFrom, now)));
    }

    /** 收一个非空的 id 进去，已经收过的跳过（保持首次出现的顺序）。 */
    private static void collect(String id, List<String> ids) {
        if (id != null && !ids.contains(id)) {
            ids.add(id);
        }
    }

    /**
     * 一张卡片的数据源。**一个函数同时决定「取哪一路」和「期望哪一路的结果」**——
     * 分成两个判断（一个决定要不要查、一个决定怎么读）就会错位，而错位的表现恰好是最难看的：
     * 没查的那一路在成数据时查不到，卡片上是一句含混的 {@code no data}，
     * 而不是「这个数据源还没做」。
     */
    private enum Source {
        /** statistics 的 {@code overview}：统计卡、分布图、告警曲线。 */
        OVERVIEW,
        /** 服务的当前值 + 定义：服务卡。 */
        SERVICE,
        /** 设备影子 + 设备型号：设备卡。 */
        DEVICE,
        /** 点位的历史序列 + 该方法的失败时刻：字段曲线（{@code line(serviceField)}）。 */
        FIELD,
        /** 还没接数据源的卡片类型，或写坏的 {@code source} / {@code dimension}。 */
        NONE
    }

    /**
     * {@code line} 按 {@code source} 分叉，其余按类型。
     *
     * <p>写坏的 {@code source}（比如库里存着上一版的名字）归 {@link Source#NONE}：它会在
     * {@link #unsupportedDetail} 里被说成 {@code line(bogus)}，比含混的「这个窗口没取到数」
     * 更能指出是哪儿写坏了。保存时 {@code WebDashboardWidgetStructureValidator} 已经拦过一道，
     * 但服务端加过一个取值又删掉时，老布局就会带着它躺在库里。</p>
     */
    private static Source sourceOf(DashboardWidgetView widget) {
        if (widget == null || widget.type() == null) {
            return Source.NONE;
        }
        return switch (widget.type()) {
            case "stat", "distribution" -> Source.OVERVIEW;
            case "line" -> {
                String source = configString(widget.config(), "source");
                if ("alarmCount".equals(source)) {
                    yield Source.OVERVIEW;
                }
                yield "serviceField".equals(source) ? Source.FIELD : Source.NONE;
            }
            case "service" -> Source.SERVICE;
            case "device" -> Source.DEVICE;
            default -> Source.NONE;
        };
    }

    /**
     * 一张卡片的窗口。**永不返回 null**：没带 {@code window}（{@code devices.total} 这类）
     * 或 window 写坏的，一律落进兜底窗口 {@code [now - 1h, now]}。
     *
     * <p>兜底窗口由调用方算好传进来，而不是在这里各自 {@code now - 1h}：否则两张无窗口的卡片
     * 会得到相差几毫秒的两个窗口，白白多查一次。</p>
     */
    private static Window windowOf(DashboardWidgetView widget, Date now, Date fallbackFrom) {
        Object raw = widget == null || widget.config() == null ? null : widget.config().get("window");
        if (raw instanceof Map<?, ?> window) {
            String kind = DashboardWidgetConfigs.str(window.get("kind"));
            if ("last".equals(kind)) {
                Integer hours = DashboardWidgetConfigs.intVal(window.get("hours"));
                if (hours != null && hours >= 1) {
                    return new Window(new Date(now.getTime() - hours * 3_600_000L), now);
                }
            } else if ("range".equals(kind)) {
                Long from = DashboardWidgetConfigs.longVal(window.get("from"));
                Long to = DashboardWidgetConfigs.longVal(window.get("to"));
                if (from != null && to != null && to > from) {
                    return new Window(new Date(from), new Date(to));
                }
            }
        }
        return new Window(fallbackFrom, now);
    }

    /**
     * 并发跑一批「键 → 单个 Uni」，结果按同一批键收成 Map。
     *
     * <p>四路取数（窗口、服务、曲线、失败）都是这个形状：{@code Uni.join().all(...)} 只按
     * <b>位置</b>回结果，所以要自己按下标与键对上。各写一遍的代价是四处各错一次的机会，
     * 而错位的表现是**A 卡拿到了 B 卡的数据** —— 一屏数字都在、就是串了位，最难查的那一类。</p>
     *
     * <p>{@code andFailFast()} 在这里是安全的：每个元素在进来之前都已经各自 recover 过了，
     * 正常路径上不会有人失败。</p>
     */
    private static <T> Uni<Map<String, T>> joinAll(Map<String, Uni<T>> queries) {
        if (queries.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        return Uni.join()
                .all(new ArrayList<>(queries.values()))
                .andFailFast()
                .map(results -> {
                    Map<String, T> byKey = new LinkedHashMap<>();
                    int i = 0;
                    for (String key : queries.keySet()) {
                        byKey.put(key, results.get(i++));
                    }
                    return byKey;
                });
    }

    /**
     * 逐窗口取数。**窗口取数失败不整份失败**：一个窗口的查询出错（比如恰好撞上 31 天上限）
     * 只该让落在这个窗口上的卡片黑掉，不该让整屏看板都出不来。
     */
    private Uni<Map<String, Fetched>> fetchOverviews(String spaceId, Map<String, Window> windows) {
        Map<String, Uni<Fetched>> queries = new LinkedHashMap<>();
        windows.forEach((key, window) -> queries.put(key,
                statisticsQueryService.overview(spaceId, window.from(), window.to())
                        .map(overview -> new Fetched(overview, null))
                        .onFailure(IllegalArgumentException.class)
                        .recoverWithItem(failure -> new Fetched(null, failure.getMessage()))));
        return joinAll(queries);
    }

    /**
     * 逐服务取当前值 + 定义。**一个服务的失败不整份失败**：和窗口取数同一个理由 ——
     * 一个服务的查询出错只该黑掉引用它的那几张卡。
     *
     * <p>这里 recover 的是**任何**失败，不只是 {@code IllegalArgumentException}
     * （与上面 overview 的窄 recover 不同）：{@code Uni.join().andFailFast()} 会把单个失败
     * 放大成整份 {@code render} 失败，也就是**一屏卡片全黑**。而这批查询里有两条独立的腿 ——
     * 影子查询是本地库（可能超时），服务定义查询是另一次读 —— 任何一条腿抖一下都不该让
     * 整个看板空掉。消息原样带上，卡片自己会说是哪个服务出错了。</p>
     */
    private Uni<Map<String, ServiceFetched>> fetchServices(List<String> serviceIds) {
        Map<String, Uni<ServiceFetched>> queries = new LinkedHashMap<>();
        for (String serviceId : serviceIds) {
            queries.put(serviceId, Uni.combine().all()
                    .unis(historyQueryService.currentValues(serviceId), serviceRepo.findById(serviceId))
                    .asTuple()
                    .map(tuple -> new ServiceFetched(shadowsByFunction(tuple.getItem1()),
                            tuple.getItem2(), null))
                    .onFailure().recoverWithItem(
                            failure -> new ServiceFetched(Map.of(), null, messageOf(failure))));
        }
        return joinAll(queries);
    }

    /**
     * 逐点位取历史序列（曲线卡）。**一个点位的失败不整份失败**：与 {@link #fetchServices}
     * 同一条理由 —— {@code andFailFast} 会把一次失败放大成一屏全黑，而这里最常见的失败是
     * **配置问题**（窗口太宽、样本超 {@code MAX_ROWS}），它本就只该黑掉引用它的那一张卡。
     * 消息原样带上，卡片自己会说清是哪条曲线、为什么。
     */
    private Uni<Map<String, CurveFetched>> fetchCurves(Map<String, CurveQuery> curves) {
        Map<String, Uni<CurveFetched>> queries = new LinkedHashMap<>();
        curves.forEach((key, curve) -> queries.put(key,
                historyQueryService.range(curve.serviceId(), curve.functionIndex(), curve.field(),
                                curve.window().from(), curve.window().to(), curve.maxPoints())
                        .map(result -> new CurveFetched(ModbusHistoryRangeCodec.encode(
                                curve.serviceId(), curve.functionIndex(), curve.field(), result), null))
                        .onFailure().recoverWithItem(
                                failure -> new CurveFetched(null, messageOf(failure)))));
        return joinAll(queries);
    }

    /**
     * 逐方法取采集失败的时刻（曲线图上那些竖虚线）。
     *
     * <p><b>取不到时给空清单，不让这张卡失败</b> —— 竖线是曲线上的辅助信息，为它把整条曲线
     * 换成一张错误卡是拿主要信息换次要信息。而参数真的坏了（窗口非法、类型不认）时
     * {@code range} 那一路用的是同一个窗口，会一起坏，卡片仍然会失败并说出原因，
     * 所以这里的静默不会把「配置错了」伪装成「这段时间没失败过」。</p>
     */
    private Uni<Map<String, JsonArray>> fetchFailures(Map<String, FailureQuery> failures) {
        Map<String, Uni<JsonArray>> queries = new LinkedHashMap<>();
        failures.forEach((key, query) -> queries.put(key,
                historyQueryService.failures(query.serviceId(), query.functionIndex(), null,
                                query.window().from(), query.window().to(), null)
                        .map(result -> failureTimes(result.items()))
                        .onFailure().recoverWithItem(failure -> new JsonArray())));
        return joinAll(queries);
    }

    /**
     * 失败记录 → 时刻清单。**只取 {@code at}**：图上画的是一条竖线，
     * 失败的类型、原因、远端码都在历史页那张表里，这里用不上（§5.4 的曲线卡只画曲线）。
     */
    private static JsonArray failureTimes(List<ModbusServiceFailure> items) {
        JsonArray times = new JsonArray();
        if (items != null) {
            for (ModbusServiceFailure item : items) {
                if (item != null && item.at != null) {
                    times.add(item.at.getTime());
                }
            }
        }
        return times;
    }

    /** 影子按 {@code functionIndex} 索引（一个服务下每个方法至多一条影子）。 */
    private static Map<Integer, ModbusServiceShadow> shadowsByFunction(List<ModbusServiceShadow> shadows) {
        Map<Integer, ModbusServiceShadow> byIndex = new HashMap<>();
        if (shadows != null) {
            for (ModbusServiceShadow shadow : shadows) {
                if (shadow != null && shadow.functionIndex != null) {
                    byIndex.put(shadow.functionIndex, shadow);
                }
            }
        }
        return byIndex;
    }

    /**
     * 取设备影子与设备型号。**一屏一次调用**：{@code getShadows} 本身就收一个 did 列表
     * （一次调用喂全部设备卡，did 可重复），逐卡各调一次会把「批量」这件事做没了 ——
     * 一屏 30 张设备卡就是 30 次跨服务往返。
     *
     * <p>{@code ownerId} 传**根空间 id**，与 {@code DeviceResource} 的
     * {@code /shadows/{spaceId}} 逐字一致：它被签进下游 JWT，是 manipulation 侧设备归属的
     * 主键之一，传错了那边直接把这次操作标成「设备不存在」。</p>
     *
     * <p>失败恢复的粒度**是这一批**，不是单张卡：批量调用本来就是一次往返，拆不出「哪台设备
     * 出错」这一层。所以这里 recover 成一条错误文案，让所有设备卡带着它各自失败 ——
     * 而不是让 {@code Uni.join().andFailFast()} 把整份 render 打挂（一屏卡片全黑）。
     * 单台设备的问题（没上报、读失败）不在这里，它在影子的元素里，逐卡各说各的。</p>
     */
    private Uni<DeviceFetched> fetchDevices(String ownerId, List<String> dids) {
        if (dids.isEmpty()) {
            return Uni.createFrom().item(new DeviceFetched(Map.of(), Map.of(), null));
        }
        return Uni.combine().all()
                .unis(deviceRepo.findByDeviceIds(dids), manipulationService.getShadows(ownerId, dids))
                .asTuple()
                .map(tuple -> new DeviceFetched(devicesByDid(tuple.getItem1()),
                        elementsByPid(tuple.getItem2()), null))
                .onFailure().recoverWithItem(
                        failure -> new DeviceFetched(Map.of(), Map.of(), messageOf(failure)));
    }

    /** 设备按 did 索引（{@code _id} 就是 did）。 */
    private static Map<String, DeviceEntity> devicesByDid(List<DeviceEntity> devices) {
        Map<String, DeviceEntity> byDid = new HashMap<>();
        if (devices != null) {
            for (DeviceEntity device : devices) {
                if (device != null && device.did != null) {
                    byDid.put(device.did, device);
                }
            }
        }
        return byDid;
    }

    /**
     * 影子元素按 pid 索引。**元素里没有 did** —— did 就编在 pid 里
     * （{@code <did>.<siid>.<piid>}，见 {@code DeviceShadowMapper}），
     * 所以一张卡拿着自己那一整个 pid 直接查即可。
     *
     * <p>没有 pid 的元素整条丢掉：一条查不到的读数对任何卡片都没用，留着只会让
     * 「谁的」这个问题悬空。</p>
     */
    private static Map<String, JsonObject> elementsByPid(JsonArray shadows) {
        Map<String, JsonObject> byPid = new HashMap<>();
        if (shadows != null) {
            for (int i = 0; i < shadows.size(); i++) {
                if (shadows.getValue(i) instanceof JsonObject element) {
                    String pid = element.getString("pid");
                    if (pid != null && !pid.isBlank()) {
                        byPid.put(pid, element);
                    }
                }
            }
        }
        return byPid;
    }

    /** 异常文案；没有 message 的（NPE 之类）退回类名，免得卡片上是一条空白的原因。 */
    private static String messageOf(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isBlank()
                ? (failure == null ? "unknown" : failure.getClass().getSimpleName())
                : message;
    }

    /** 逐卡片成数据。顺序与请求一致。 */
    private static WidgetData build(String spaceId, List<? extends DashboardWidgetView> widgets, Sources sources,
                                    Map<String, String> ownershipErrors, Date fallbackFrom, Date now) {
        List<Item> items = new ArrayList<>();
        Date from = null;
        Date to = null;
        for (DashboardWidgetView widget : widgets) {
            String id = widget == null || widget.id() == null ? "" : widget.id();
            // 归属错误优先于「暂不支持」：引用了不存在或不属于本空间的东西，比「这个版本还没做」
            // 更值得先说 —— 它可能是真的配错了
            String ownershipError = ownershipErrors.get(id);
            if (ownershipError != null) {
                items.add(Item.failed(id, ownershipError));
                continue;
            }
            String err = unsupported(widget);
            if (err != null) {
                items.add(Item.failed(id, err));
                continue;
            }
            Window window = windowOf(widget, now, fallbackFrom);
            // 外层窗口是**各卡片窗口的并集**，仅供参考：每张卡片该用哪个窗口看它自己 data 里的字段
            if (from == null || window.from().before(from)) {
                from = window.from();
            }
            if (to == null || window.to().after(to)) {
                to = window.to();
            }
            items.add(item(widget, id, window, sources));
        }
        return new WidgetData(spaceId, from == null ? now : from, to == null ? now : to, items);
    }

    /**
     * 一张卡片的结果：按类型分派到各自的数据源。
     *
     * <p>「这一格的组合还没有数据源」由各分支返回 {@code null} 表示，统一在下面变成
     * {@link #unsupportedDetail} 的文案 —— 这样「类型支持但某个取值不支持」（比如
     * {@code line} 的数据源写坏了）仍然说得清楚，而不是含混的一句「取数失败」。</p>
     */
    private static Item item(DashboardWidgetView widget, String id, Window window, Sources sources) {
        if (sourceOf(widget) == Source.SERVICE) {
            ServiceFetched service = sources.services().get(configString(widget.config(), "serviceId"));
            if (service == null) {
                return Item.failed(id, "no data");
            }
            if (service.error() != null) {
                return Item.failed(id, service.error());
            }
            JsonObject payload = serviceData(widget.config(), service);
            return payload == null ? Item.failed(id, unsupportedDetail(widget)) : Item.ok(id, payload);
        }
        if (sourceOf(widget) == Source.DEVICE) {
            // 一批设备一起取的，这批失败时每张设备卡都拿同一条原因（见 fetchDevices）
            if (sources.devices().error() != null) {
                return Item.failed(id, sources.devices().error());
            }
            JsonObject payload = deviceData(widget.config(), sources.devices());
            return payload == null ? Item.failed(id, unsupportedDetail(widget)) : Item.ok(id, payload);
        }
        if (sourceOf(widget) == Source.FIELD) {
            CurveQuery query = CurveQuery.of(widget, window);
            CurveFetched curve = query == null ? null : sources.curves().get(query.key());
            if (curve == null) {
                return Item.failed(id, "no data");
            }
            if (curve.error() != null) {
                return Item.failed(id, curve.error());
            }
            // 定义与服务卡取自同一批（见 fetchCurves）：那一批失败时这条曲线也画不出来 ——
            // 没有定义的曲线不知道单位、也不知道该不该走阶梯线
            ServiceFetched service = sources.services().get(query.serviceId());
            if (service == null) {
                return Item.failed(id, "no data");
            }
            if (service.error() != null) {
                return Item.failed(id, service.error());
            }
            return Item.ok(id, fieldData(query, curve, service, sources.failures()));
        }
        if (sourceOf(widget) == Source.NONE) {
            // 「还没接」而不是「配置错了」：写坏的 source / dimension 走这条，说得具体些
            // （见 unsupportedDetail）
            return Item.failed(id, unsupportedDetail(widget));
        }

        Fetched data = sources.overviews().get(window.key());
        if (data == null || data.overview() == null) {
            return Item.failed(id, data == null ? "no data" : data.error());
        }
        JsonObject payload = payload(widget, data.overview());
        return payload == null ? Item.failed(id, unsupportedDetail(widget)) : Item.ok(id, payload);
    }

    /** 还没接数据源的卡片：说清楚是「还没做」，而不是「配置错了」。 */
    private static String unsupported(DashboardWidgetView widget) {
        if (widget == null || widget.type() == null) {
            return "widget type is required";
        }
        return switch (widget.type()) {
            case "stat", "line", "distribution", "service", "device" -> null;
            default -> UNSUPPORTED_TYPE + widget.type();
        };
    }

    private static String unsupportedDetail(DashboardWidgetView widget) {
        if ("line".equals(widget.type())) {
            return UNSUPPORTED_TYPE + "line(" + configString(widget.config(), "source") + ")";
        }
        if ("distribution".equals(widget.type())) {
            return UNSUPPORTED_DIMENSION + configString(widget.config(), "dimension");
        }
        return UNSUPPORTED_TYPE + widget.type();
    }

    /** 按卡片类型成 data；返回 {@code null} 表示这一格的组合在 P1 还没有数据源。 */
    private static JsonObject payload(DashboardWidgetView widget, StatisticsQueryService.Overview overview) {
        return switch (widget.type()) {
            case "stat" -> statData(widget.config(), overview);
            case "distribution" -> distributionData(widget.config(), overview);
            case "line" -> lineData(widget.config(), overview);
            default -> null;
        };
    }

    /**
     * 统计卡的 data：{@code {value?, hourly?, to?}}。
     *
     * <p><b>{@code alarms.today} 的 value 不在这里算</b>：「今日」= 用户本地时区的 00:00 之后，
     * 而服务端不知道用户时区（也不该按服务器时区猜）。所以这类卡片下发 {@code hourly} 与窗口终点
     * {@code to}，由前端按本地 00:00 求和 —— 与现有 dashboard 页的做法逐字一致
     * （{@code dashboard.functions.ts} 的 {@code sumSince(hourly, dayStart(o.to))}）。
     * 这也是 §D5「服务端只返回原始值」的直接结果。</p>
     */
    private static JsonObject statData(Map<String, Object> config, StatisticsQueryService.Overview overview) {
        String metric = configString(config, "metric");
        JsonObject data = new JsonObject();
        Long value = statValue(metric, overview);
        if (value != null) {
            data.put("value", value);
        }
        if (needsLocalDay(metric)) {
            data.put("hourly", hourly(overview));
            data.put("to", overview.to().getTime());
        }
        return data;
    }

    /** 「今日」需要本地时区才能算，所以在需要它的卡片上一并下发整点桶。 */
    private static boolean needsLocalDay(String metric) {
        return "alarms.today".equals(metric);
    }

    /**
     * 单个 metric 的原始值。{@code alarms.today} 返回 {@code null}（见 {@link #statData}）。
     *
     * <p>{@code failures.total} 是**故障种类数**不是次数（{@code API.md} §8.1 的口径），
     * 编辑器里对这张卡有专门的提示，这里不做换算，也不是 bug。</p>
     */
    private static Long statValue(String metric, StatisticsQueryService.Overview overview) {
        if (metric == null) {
            return null;
        }
        return switch (metric) {
            case "devices.total" -> (long) overview.devices().total();
            case "devices.online" -> (long) overview.devices().online();
            case "services.total" -> (long) overview.services().total();
            case "alarms.window" -> (long) overview.alarms().total();
            case "failures.total" -> (long) overview.failures().total();
            default -> null;
        };
    }

    /**
     * 分布图的 data：{@code {groups: [{key, count}]}}，**全量下发**。
     *
     * <p>不在服务端截断（§5.5）：{@code limit} 是显示层的取舍，前端截成「前 N + 其他」，
     * 这样用户改 limit 不用重新取数，也不会出现「后端悄悄截断了、卡片上看不出来」。
     * 顺序沿用 {@code overview} 的结果（条数降序、同数按名称升序），这里不重排 ——
     * 两处各排一次，迟早排得不一样。</p>
     */
    private static JsonObject distributionData(Map<String, Object> config, StatisticsQueryService.Overview overview) {
        String dimension = configString(config, "dimension");
        List<StatisticsQueryService.Count> rows = switch (dimension == null ? "" : dimension) {
            case "deviceType" -> overview.devices().byType();
            // serviceType 的 key 是 configId：解成「厂家 型号」并合并同名是展示层的事（§5.5），
            // 和现有 dashboard 页一样，由前端拿配置清单一并做
            case "serviceType" -> overview.services().byConfig();
            case "alarmType" -> overview.alarms().byText();
            default -> null;
        };
        if (rows == null) {
            return null;
        }
        JsonArray groups = new JsonArray();
        for (StatisticsQueryService.Count row : rows) {
            groups.add(new JsonObject().put("key", row.key()).put("count", row.count()));
        }
        return new JsonObject().put("groups", groups);
    }

    /** 告警曲线的 data：{@code {from, to, points: [{at, count}]}}，整点桶原样下发。 */
    private static JsonObject lineData(Map<String, Object> config, StatisticsQueryService.Overview overview) {
        if (!"alarmCount".equals(configString(config, "source"))) {
            return null;
        }
        return new JsonObject()
                .put("from", overview.from().getTime())
                .put("to", overview.to().getTime())
                .put("points", hourly(overview));
    }

    /**
     * 字段曲线的 data：**{@code /history/range} 的响应体**（由 {@link ModbusHistoryRangeCodec} 编，
     * 与历史页那条路逐字同一份形状）+ 三样展示元数据。
     *
     * <ul>
     *   <li>{@code unit} / {@code step}：取自服务定义（服务端手里本来就有那一份，同服务卡）。
     *       {@code step} = 「是不是一个位」：位的曲线该走阶梯线（0 一直保持到下一次翻转），
     *       直连会画出根本不存在的中间值。判据与历史页的 {@code fieldRef} 同一处
     *       （{@code service.fields.ts}）；</li>
     *   <li>{@code failures}：这个方法在窗口内的采集失败时刻，图上画成竖虚线。</li>
     * </ul>
     *
     * <p><b>不看 {@code showFailureShadow}</b>：那是「画不画」的展示决策，取数层不与它耦合 ——
     * 清单照取，画不画由前端定。让服务端按开关跳过这次查询，换来的是「改一次开关要重新取数」，
     * 而这份清单本来就是跟着窗口一起取回来的，省不下什么。</p>
     */
    private static JsonObject fieldData(CurveQuery query, CurveFetched curve, ServiceFetched service,
                                        Map<String, JsonArray> failures) {
        FieldMeta meta = fieldMeta(findFunction(service.service(), query.functionIndex()), query.field());
        // 这份响应体是这次取数现编的（`fetchCurves` 里每个去重键一份），就地缀上三样即可
        JsonObject data = curve.range();
        data.put("unit", meta.unit());
        data.put("step", meta.bit());
        data.put("failures", failures.getOrDefault(
                FailureQuery.keyOf(query.serviceId(), query.functionIndex(), query.window()),
                new JsonArray()));
        return data;
    }

    /** 整点桶。桶起点是 epoch 毫秒，刻度文案（{@code HH:00}）由前端按本地时区格式化。 */
    private static JsonArray hourly(StatisticsQueryService.Overview overview) {
        JsonArray points = new JsonArray();
        for (StatisticsQueryService.Bucket bucket : overview.alarms().hourly()) {
            points.add(new JsonObject().put("at", bucket.at()).put("count", bucket.count()));
        }
        return points;
    }

    /**
     * 服务卡的 data：
     * {@code {functionIndex, recordedAt?, fields: [{field, value?, unit, bit}], error?, errorAt?}}。
     *
     * <p><b>「采过没采过」的判据是 {@code recordedAt} 在不在</b>，不是某个状态字段：影子就是
     * 「最后一次成功的采集」，一个从没成功过的方法**根本不会有影子**
     * （{@link ModbusHistoryQueryService#currentValues} 的说法）。所以没有影子时只给
     * {@code functionIndex}，前端据此渲染「尚未采集」；<b>不给 {@code fields}</b> ——
     * 一行都没有值可显示的空壳不是信息。</p>
     *
     * <p>{@code recordedAt} 属于**整个 function**，不是每个字段各自的：同一张卡的字段必须来自
     * 同一个 function（§5.7），所以时间只有一处。</p>
     *
     * <p>{@code error} / {@code errorAt} 与值**可以同时存在**：影子里的 {@code fields} /
     * {@code recordedAt} 是最后一次**成功**的采集，失败时一律不动（见 {@code ModbusServiceShadow}
     * 的说明）。也就是说有 {@code lastError} 时值仍在，只是从那一刻起没再更新过。
     * 两个都下发、由前端并排显示 —— 服务端不做「失败就不给值」的取舍，那会丢掉最后的已知读数。</p>
     *
     * <p>按 {@code config.fields} 的**配置顺序**产出：用户选的就是这个顺序，卡片应当照它排。
     * {@code unit} / {@code bit} 取自服务定义（服务端手里本来就有那一份），可翻译的解释
     * （位值说「开/关」、枚举描述）仍留在前端 —— 见 §D5 与本次修订（十）。</p>
     */
    private static JsonObject serviceData(Map<String, Object> config, ServiceFetched fetched) {
        Integer functionIndex = DashboardWidgetConfigs.intVal(config.get("functionIndex"));
        if (functionIndex == null) {
            return null;
        }
        ModbusServiceShadow shadow = fetched.shadows().get(functionIndex);
        JsonObject data = new JsonObject().put("functionIndex", functionIndex);
        if (shadow == null) {
            return data;
        }
        data.put("fields", fieldRows(findFunction(fetched.service(), functionIndex),
                DashboardWidgetConfigs.strList(config.get("fields")),
                shadow.fields == null ? null : new JsonObject(shadow.fields)));
        if (shadow.recordedAt != null) {
            data.put("recordedAt", shadow.recordedAt.getTime());
        }
        if (shadow.lastError != null) {
            data.put("error", shadow.lastError);
        }
        if (shadow.errorAt != null) {
            data.put("errorAt", shadow.errorAt.getTime());
        }
        return data;
    }

    /**
     * 配置里那几个字段各成一行，带上它们的 {@code unit} 与「是不是位」。
     *
     * <p>这一轮没取到值的字段**不带 {@code value}**（不是补 0、也不是补 null）：前端据此显示
     * {@code -}，「没有这个键」与「值是 0」是两回事，后者是真实的读数。</p>
     */
    private static JsonArray fieldRows(ModbusServiceFunction function, List<String> configured,
                                       JsonObject values) {
        JsonArray rows = new JsonArray();
        for (String name : configured) {
            JsonObject row = new JsonObject().put("field", name);
            Object value = values == null ? null : values.getValue(name);
            if (value != null) {
                row.put("value", value);
            }
            FieldMeta meta = fieldMeta(function, name);
            row.put("unit", meta.unit());
            row.put("bit", meta.bit());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 一个字段名在服务定义里的展示元数据。**先找父字段、再找位**，与归属校验的
     * {@code DashboardWidgetOwnershipValidator.hasField} 同一顺序：位名是用户自己填的，可能与别的字段重名，
     * 先命中父字段才对。
     *
     * <p>找不到时给「无单位、非位」：正常路径上到不了（字段名在保存时已校验过），
     * 但如果服务定义在那之后改了，这里也不该把卡片渲染成空白 —— 按名字把值照原样显示出来，
     * 是能给的最后一个诚实答案。</p>
     */
    private static FieldMeta fieldMeta(ModbusServiceFunction function, String name) {
        if (function != null && function.response != null) {
            for (ModbusServiceField field : function.response) {
                if (field != null && name.equals(field.field)) {
                    return new FieldMeta(field.unit == null ? "" : field.unit, false);
                }
            }
            for (ModbusServiceField field : function.response) {
                if (field == null || field.bitList == null) {
                    continue;
                }
                for (ModbusServiceFieldBit bit : field.bitList) {
                    if (bit != null && name.equals(bit.field)) {
                        // 位没有单位：与历史页 fieldRef 对位的 `unit: ''` 一致
                        return new FieldMeta("", true);
                    }
                }
            }
        }
        return new FieldMeta("", false);
    }

    /**
     * 按 {@code index} 找方法。与 {@code DashboardWidgetOwnershipValidator} / {@code ModbusServiceService}
     * 里那两个同名私有方法同一段逻辑（那两处的签名一个收 {@code Integer}、一个收 {@code int}）——
     * 三次出现仍是各写一份：为 8 行扫描去动两个正在工作的类，风险比收益大。
     */
    private static ModbusServiceFunction findFunction(ModbusService service, Integer index) {
        if (service == null || service.functions == null || index == null) {
            return null;
        }
        for (ModbusServiceFunction function : service.functions) {
            if (function != null && index.equals(function.index)) {
                return function;
            }
        }
        return null;
    }

    /**
     * 设备卡的 data：{@code {pid, type?, value?}} / {@code {pid, type?, error?}}，三态由
     * <b>有没有 {@code value}</b> 与 <b>有没有 {@code error}</b> 决定：
     *
     * <ul>
     *   <li>有 {@code value} —— 正常，读数是设备最后一次上报的值；</li>
     *   <li>有 {@code error} —— 读取失败，元素是 {@code {pid, status, description}}
     *       （{@code PropertyOperationCodec.Get.RESULT} 的编码：出错时只给状态与描述、不给值），
     *       {@code error} 就是那句描述；</li>
     *   <li>两个都没有 —— 尚未上报（影子压根没有这个 pid，或有这个 pid 但没有值）。</li>
     * </ul>
     *
     * <p>{@code type} 是设备型号，<b>只要有就给</b>：前端拿它去产品规格里查这个属性的名字与单位。
     * 查不到型号时也给（卡片会退回显示 pid 原文），但不编一个假的。</p>
     *
     * <p><b>值一律原样搬运</b>：不缩放、不按 {@code format} 换算、不把布尔翻译成「开/关」——
     * 服务端只给原始值，展示层的解释留在前端（§D5 与修订（十））。</p>
     */
    private static JsonObject deviceData(Map<String, Object> config, DeviceFetched fetched) {
        String pid = configString(config, "pid");
        if (pid == null) {
            return null;
        }
        JsonObject data = new JsonObject().put("pid", pid);
        DeviceEntity device = fetched.devices().get(configString(config, "did"));
        if (device != null && device.type != null && !device.type.isBlank()) {
            data.put("type", device.type);
        }
        JsonObject element = fetched.shadows().get(pid);
        if (element == null) {
            return data;
        }
        // 「缺 status 就是成功」与 PropertyOperationCodec.Get.RESULT.decode 同一条规则
        // （那边读 status 的缺省也是 COMPLETED）：非负即成功，元素上就只有 value。
        int status = element.getInteger("status", Status.COMPLETED);
        if (status < 0) {
            data.put("error", errorText(element, status));
        } else {
            Object value = element.getValue("value");
            if (value != null) {
                data.put("value", value);
            }
        }
        return data;
    }

    /**
     * 影子元素里的错误描述。没有描述时退回状态码本身（{@code "status -422"}）——
     * 空串会让卡片上那条 tooltip 变成一个空框，看不出是「读取失败」还是「什么都还没发生」。
     */
    private static String errorText(JsonObject element, int status) {
        String description = element.getString("description");
        return description == null || description.isBlank() ? "status " + status : description;
    }

    private static String configString(Map<String, Object> config, String key) {
        return config == null ? null : DashboardWidgetConfigs.str(config.get(key));
    }

    /** 解析后的窗口（绝对时刻）。{@code key()} 是分组去重用的，只由两端时刻决定。 */
    private record Window(Date from, Date to) {

        String key() {
            return from.getTime() + "-" + to.getTime();
        }
    }

    /** 一个窗口的取数结果：要么有 overview，要么有错误文案。 */
    private record Fetched(StatisticsQueryService.Overview overview, String error) {
    }

    /**
     * 一屏取数的全部结果，按各自的去重键索引。
     *
     * <p>五张表放一起递下去，是为了让 {@link #build} / {@link #item} 收一个参数而不是五个：
     * 每加一路数据源就多一个位置参数，而下标/顺序错了的编译期表现只是「类型对不上」——
     * 两个同为 {@code Map<String, ?>} 的参数调换了位置，编译器一声不吭。</p>
     */
    private record Sources(Map<String, Fetched> overviews, Map<String, ServiceFetched> services,
                           DeviceFetched devices, Map<String, CurveFetched> curves,
                           Map<String, JsonArray> failures) {
    }

    /**
     * 一条字段曲线的取数请求：某个点位在某段窗口上要多少个点。
     *
     * <p><b>去重键里必须带 {@code maxPoints}</b>：要 200 个点与要 2000 个点是两次不同的查询
     * （降不降采样、桶多粗都不一样）。只按「点位 × 窗口」去重，会让其中一张卡拿到另一张的点数
     * —— 而它看起来完全正常，因为它本来就是一组合法的采样。</p>
     */
    private record CurveQuery(String serviceId, Integer functionIndex, String field, Window window,
                              int maxPoints) {

        String key() {
            return serviceId + "#" + functionIndex + "#" + field + "#" + window.key() + "#" + maxPoints;
        }

        /**
         * 从卡片配置里读。三元组缺一就给 {@code null}（{@code WebDashboardWidgetStructureValidator} 已经拦过，
         * 这里是兜底：服务端加过一个必填项又撤掉时，老布局会带着不完整的配置躺在库里）。
         */
        static CurveQuery of(DashboardWidgetView widget, Window window) {
            Map<String, Object> config = widget.config();
            String serviceId = configString(config, "serviceId");
            Integer functionIndex = DashboardWidgetConfigs.intVal(config.get("functionIndex"));
            String field = configString(config, "field");
            if (serviceId == null || functionIndex == null || field == null) {
                return null;
            }
            Integer maxPoints = DashboardWidgetConfigs.intVal(config.get("maxPoints"));
            // 缺省由 queryService 那侧归一（夹到 [1, 2000]），这里只给「没配」的那档
            return new CurveQuery(serviceId, functionIndex, field, window,
                    maxPoints == null ? ModbusHistoryQueryService.DEFAULT_MAX_POINTS : maxPoints);
        }
    }

    /**
     * 一个方法在窗口内的采集失败。
     *
     * <p><b>键里没有 {@code field}</b>：一次失败是整个方法的事（帧都读不通时哪个字段都没读到，
     * 见 {@code ModbusServiceFailure}），所以同一个方法的几条曲线画的是同一条竖线，
     * 共用一次查询。</p>
     */
    private record FailureQuery(String serviceId, Integer functionIndex, Window window) {

        String key() {
            return keyOf(serviceId, functionIndex, window);
        }

        static String keyOf(String serviceId, Integer functionIndex, Window window) {
            return serviceId + "#" + functionIndex + "#" + window.key();
        }

        /** 从卡片配置里读（方法序号缺了就给 {@code null}：没有它也无从查失败）。 */
        static FailureQuery of(DashboardWidgetView widget, Window window) {
            String serviceId = configString(widget.config(), "serviceId");
            Integer functionIndex = DashboardWidgetConfigs.intVal(widget.config().get("functionIndex"));
            return serviceId == null || functionIndex == null
                    ? null : new FailureQuery(serviceId, functionIndex, window);
        }
    }

    /** 一条曲线的取数结果：要么有响应体，要么有错误文案。空响应体是不可能的（没有点也是合法结果）。 */
    private record CurveFetched(JsonObject range, String error) {
    }

    /**
     * 一个服务的取数结果：该服务各方法当前的影子（按 functionIndex）、服务定义、以及错误文案。
     *
     * <p>定义与影子一起取：定义用来产出每个字段的 {@code unit} / {@code bit}，两者缺一个卡片
     * 都画不出来，分两次取只会多一个中间状态。</p>
     */
    private record ServiceFetched(Map<Integer, ModbusServiceShadow> shadows, ModbusService service,
                                  String error) {
    }

    /**
     * 一屏设备卡的取数结果：设备实体（按 did，取型号用）、影子元素（按 pid），以及错误文案。
     *
     * <p>三张表放一起而不是「一台设备一条」：这一批本来就是两次调用换来的两份表，
     * 拆成 per-device 只是把同一份数据重新分组，反倒要一张 did → 记录 的索引。</p>
     */
    private record DeviceFetched(Map<String, DeviceEntity> devices, Map<String, JsonObject> shadows,
                                 String error) {
    }

    /** 一个字段的展示元数据：单位，以及它是不是一个位（位值前端说「开/关」）。 */
    private record FieldMeta(String unit, boolean bit) {
    }
}
