package cc.openxiot.matrix.api.dashboard.widget;

import cc.openxiot.matrix.db.modbus.service.polling.ModbusPollingFrom;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cc.openxiot.matrix.api.device.manipulation.ManipulationService;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService;
import cc.openxiot.matrix.db.dashboard.web.WebDashboardWidget;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceLastPolling;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceRegistryHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceRegistryHistoryRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadowRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseField;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseFieldBit;
import cc.openxiot.matrix.db.modbus.service.polling.response.ModbusPollingResponses;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 一屏取数的编排逻辑：**按窗口去重**、**逐卡片隔离**、以及「哪些组合在 P1 还没有数据源」。
 *
 * <p>两个上游都是 {@code @InjectMock}（{@code StatisticsQueryService} 与
 * {@code WebDashboardLayoutValidator}），所以这里测的正是本类自己写的那部分：窗口怎么解析、
 * 什么时候复用一次查询、一张卡失败怎么不牵连别人。上游各自的正确性由它们自己的测试负责。</p>
 *
 * <p>归属校验被换成 mock，是因为那部分需要查库；本类要钉的是**它的结果怎么被用** ——
 * 尤其是「归属错误优先于『暂不支持』」这条顺序。</p>
 */
@QuarkusTest
class WidgetDataServiceTest {

    private static final String SPACE_ID = "6512f3a4b5c6d7e8f9a0b1c2";
    private static final String SERVICE_ID = "svc-1";

    /** 一个属性的完整 pid：{@code <did>.<siid>.<piid>}，影子元素里的 pid 也是这个形状。 */
    private static final String PID = "did-1.1.1";
    private static final long HOUR = 3_600_000L;

    /** 最后一次成功采集的时刻，与失败时刻分开：失败时 {@code recordedAt} 必须停在成功那一刻。 */
    private static final Date AT = new Date(1_700_000_000_000L);
    private static final Date ERROR_AT = new Date(1_700_000_600_000L);

    @InjectMock
    StatisticsQueryService statisticsQueryService;

    @InjectMock
    DashboardWidgetOwnershipValidator ownershipValidator;

    @InjectMock
    ModbusServiceRegistryHistoryRepository registryHistoryRepo;

    @InjectMock
    ModbusServiceRepository serviceRepo;

    @InjectMock
    ModbusServiceShadowRepository registryShadowRepo;

    @InjectMock
    ManipulationService manipulationService;

    @InjectMock
    DeviceRepository deviceRepo;

    @Inject
    DashboardWidgetDataService service;

    /** 区间查询要返回的寄存器事件：成功（带 value）与失败（{@code failure=true}）混在一起，
     *  由服务自己筛开 —— 值曲线与失败竖线同一条源。下面那两桩答案每次调用时现读。 */
    private final List<ModbusServiceRegistryHistory> events = new ArrayList<>();

    /**
     * 默认没有归属问题、也没有影子与服务定义；要测什么的用例各自再 stub 一次。
     *
     * <p>各路取数的默认返回**必须给**：未 stub 的 mock 返回 {@code null} 而不是一个
     * 「空结果」的 Uni，组合起来就是 NPE。</p>
     */
    @BeforeEach
    void stubDefaults() {
        when(ownershipValidator.errorsByWidget(any(), anyList())).thenReturn(Uni.createFrom().item(Map.of()));
        when(serviceRepo.findById(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(registryShadowRepo.listByServiceId(anyString())).thenReturn(Uni.createFrom().item(List.of()));
        when(manipulationService.getShadows(anyString(), anyList()))
                .thenReturn(Uni.createFrom().item(new JsonArray()));
        when(deviceRepo.findByDeviceIds(anyList())).thenReturn(Uni.createFrom().item(List.of()));
        // 曲线事件用 doAnswer 注册，而不是 when(...).thenAnswer(...)：when 那套写法在**重新**
        // 打桩时会把上一次注册的答案先跑一遍（参数全是 null），于是一个按 from/to 现算的答案会在
        // 打桩那一刻就炸掉。用 doAnswer 则先立桩、后调用，已经有了的桩不会被跑。用例只往
        // events 里塞数据，答案每次调用时现读。carry-in 默认没有（区间起点前无事件）。
        when(registryHistoryRepo.findCarryIn(anyString(), any(), any())).thenReturn(Uni.createFrom().nullItem());
        doAnswer(invocation -> Uni.createFrom().item(List.copyOf(events)))
                .when(registryHistoryRepo).findRange(anyString(), any(), any(), any(), anyInt());
    }

    // ===== 空与基本形状 =====

    @Test
    void render_noWidgets_noQuery() {
        DashboardWidgetDataService.WidgetData data = render();

        assertEquals(SPACE_ID, data.spaceId());
        assertTrue(data.widgets().isEmpty());
        verifyNoInteractions(statisticsQueryService);
    }

    @Test
    void render_nullWidgets_noQuery() {
        assertTrue(service.render(space(), null).await().indefinitely().widgets().isEmpty());
        verifyNoInteractions(statisticsQueryService);
    }

    @Test
    void render_statCard_valueFromOverview() {
        stubOverview();
        JsonObject data = data(render(stat("w1", "devices.total", null)), 0);
        assertEquals(10, data.getInteger("value"));
    }

    @Test
    void render_statCard_leftoverSubMetricIsIgnored() {
        stubOverview();
        // 早先存下来的 config（那时统计卡还有个次要数字）：不认识就不理，主数字照给
        Map<String, Object> config = config("metric", "devices.total", "subMetric", "devices.online");
        JsonObject data = data(render(widget("w1", "stat", "W6H92", config)), 0);
        assertEquals(10, data.getInteger("value"));
        assertFalse(data.containsKey("subValue"));
    }

    @Test
    void render_statCard_servicesAndFailures() {
        stubOverview();
        assertEquals(2, data(render(stat("w1", "services.total", null)), 0).getInteger("value"));
        JsonObject failures = data(render(stat("w1", "failures.total", last(24))), 0);
        assertEquals(1, failures.getInteger("value"));
    }

    @Test
    void render_statCard_alarmsWindow() {
        stubOverview();
        assertEquals(5, data(render(stat("w1", "alarms.window", last(24))), 0).getInteger("value"));
    }

    /**
     * {@code alarms.today} 的 value 由**前端**算：服务端不知道用户时区，「今日」= 用户本地 00:00 之后，
     * 按服务器时区猜出来的数会在跨时区时错一整天。所以这里下发 {@code hourly} 与窗口终点 {@code to}，
     * 并且**不带 value**。
     */
    @Test
    void render_statCard_alarmsTodayEmitsHourlyWithoutValue() {
        stubOverview();
        JsonObject data = data(render(stat("w1", "alarms.today", last(24))), 0);

        assertFalse(data.containsKey("value"));
        assertEquals(1, data.getJsonArray("hourly").size());
        assertEquals(1_700_000_000_000L, data.getJsonArray("hourly").getJsonObject(0).getLong("at").longValue());
        assertEquals(2, data.getJsonArray("hourly").getJsonObject(0).getInteger("count"));
        assertTrue(data.getLong("to") > 0);
    }

    // ===== 窗口解析与去重 =====

    /**
     * 同一个窗口只查一次 —— 预置布局那 8 张卡（窗口全是 24h）因此只有一次 {@code overview}。
     */
    @Test
    void render_sameWindowIsQueriedOnce() {
        stubOverview();
        render(stat("w1", "alarms.window", last(24)),
                stat("w2", "alarms.today", last(24)),
                line("w3", last(24)));

        verify(statisticsQueryService, times(1)).overview(eq(SPACE_ID), any(), any());
    }

    /**
     * 窗口不同的卡片各拿各的数字：**不能**「取最大窗口查一次然后共用」——
     * 一张要 24h 的卡片拿到 7 天的聚合，数字就是错的。
     */
    @Test
    void render_distinctWindowsAreQueriedSeparately() {
        stubOverview();
        render(stat("w1", "alarms.window", last(24)), stat("w2", "alarms.window", last(12)));

        verify(statisticsQueryService, times(2)).overview(eq(SPACE_ID), any(), any());
    }

    /**
     * 不需要窗口的卡片（{@code devices.total} 这种）落进同一个兜底窗口：两张卡共用一次查询，
     * 而不是各按各的 {@code now} 得到相差几毫秒的两个窗口、白查一次。
     */
    @Test
    void render_windowlessCardsShareTheFallbackWindow() {
        stubOverview();
        render(stat("w1", "devices.total", null), stat("w2", "services.total", null));

        verify(statisticsQueryService, times(1)).overview(eq(SPACE_ID), any(), any());
    }

    @Test
    void render_lastWindowIsResolvedToAbsoluteRange() {
        stubOverview();
        render(stat("w1", "alarms.window", last(24)));

        ArgumentCaptor<Date> from = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> to = ArgumentCaptor.forClass(Date.class);
        verify(statisticsQueryService).overview(eq(SPACE_ID), from.capture(), to.capture());

        assertEquals(24 * HOUR, to.getValue().getTime() - from.getValue().getTime());
        // to 是「现在」：预置布局存的是语义（last 24h），要是存绝对毫秒，
        // 用户第二天打开看到的还是昨天的区间
        long skew = Math.abs(System.currentTimeMillis() - to.getValue().getTime());
        assertTrue(skew < 60_000L, () -> "window end is not `now`, off by " + skew + "ms");
    }

    @Test
    void render_rangeWindowIsUsedVerbatim() {
        stubOverview();
        Map<String, Object> window = config("kind", "range", "from", 1_700_000_000_000L, "to", 1_700_086_400_000L);
        render(stat("w1", "alarms.window", window));

        ArgumentCaptor<Date> from = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> to = ArgumentCaptor.forClass(Date.class);
        verify(statisticsQueryService).overview(eq(SPACE_ID), from.capture(), to.capture());
        assertEquals(1_700_000_000_000L, from.getValue().getTime());
        assertEquals(1_700_086_400_000L, to.getValue().getTime());
    }

    /**
     * 布局按**根空间**存与取（{@code SpaceEntity.rootSpaceId()}）：子空间页面看到的是
     * 项目那一份布局，取数也必须按同一个基准，否则会出现「存得下但渲染是空的」。
     */
    @Test
    void render_childSpaceQueriesTheRootSpace() {
        stubOverview();
        SpaceEntity child = new SpaceEntity();
        child.id = new ObjectId();
        child.parentId = "parent-1";
        child.ancestors = List.of("root-1");

        DashboardWidgetDataService.WidgetData data = service.render(child, List.of(stat("w1", "devices.total", null)))
                .await().indefinitely();

        assertEquals("root-1", data.spaceId());
        verify(statisticsQueryService).overview(eq("root-1"), any(), any());
    }

    // ===== 逐卡片隔离 =====

    /**
     * 一个窗口的查询出错（比如恰好撞上 31 天上限）只该让落在它上面的卡片黑掉 ——
     * 整屏看板因为一张卡配置不当而全白，是比那张卡显示错误更糟的结果。
     */
    @Test
    void render_oneWindowFailingBlacksOnlyItsCards() {
        when(statisticsQueryService.overview(eq(SPACE_ID), any(), any())).thenAnswer(invocation -> {
            Date from = invocation.getArgument(1);
            Date to = invocation.getArgument(2);
            if (to.getTime() - from.getTime() == 12 * HOUR) {
                return Uni.createFrom().failure(new IllegalArgumentException("window must not exceed 31 days"));
            }
            return Uni.createFrom().item(overview(from, to));
        });

        DashboardWidgetDataService.WidgetData data = render(
                stat("good", "alarms.window", last(24)),
                stat("bad", "alarms.window", last(12)));

        assertEquals(2, data.widgets().size());
        assertTrue(data.widgets().get(0).success());
        assertEquals(5, data.widgets().get(0).data().getInteger("value"));

        assertFalse(data.widgets().get(1).success());
        assertEquals("window must not exceed 31 days", data.widgets().get(1).message());
        assertNull(data.widgets().get(1).data());
    }

    @Test
    void render_itemsFollowRequestOrder() {
        stubOverview();
        DashboardWidgetDataService.WidgetData data = render(
                stat("a", "devices.total", null),
                stat("b", "services.total", null),
                stat("c", "failures.total", last(24)));

        assertEquals(List.of("a", "b", "c"), data.widgets().stream().map(DashboardWidgetDataService.Item::id).toList());
    }

    // ===== 归属校验：安全边界 =====

    /**
     * 归属错误**优先**于「暂不支持」：引用了不存在或不属于本空间的设备，比「这个版本还没做」
     * 更值得先说 —— 它可能是真的配错了。
     */
    @Test
    void render_ownershipErrorTakesPrecedenceOverUnsupported() {
        when(ownershipValidator.errorsByWidget(any(), anyList()))
                .thenReturn(Uni.createFrom().item(Map.of("w1", "device not found: did-9")));
        stubOverview();

        DashboardWidgetDataService.WidgetData data = render(device("w1", "did-9", "did-9.1.1"));

        assertFalse(data.widgets().get(0).success());
        assertEquals("device not found: did-9", data.widgets().get(0).message());
    }

    /**
     * 归属校验在**每一次取数**时都做，不只是保存时：卡片引用的服务可能在那之后被删掉，
     * 而 {@code serviceId} 是用户填的 —— 只按 id 查就意味着拿到别人的 id 就能读别人的数据。
     */
    @Test
    void render_ownershipIsCheckedOnEveryRender() {
        stubOverview();
        render(stat("w1", "devices.total", null));

        verify(ownershipValidator).errorsByWidget(any(), anyList());
    }

    // ===== device =====

    /** 正常态：影子里的值 + 型号（前端拿型号去产品规格里查属性名与单位）。 */
    @Test
    void render_deviceCard_valueFromShadow() {
        stubDevice("did-1", "tr_aaa");
        stubShadows(new JsonObject().put("pid", PID).put("value", 23.5));

        JsonObject data = data(render(device("w1", "did-1", PID)), 0);

        assertEquals(PID, data.getString("pid"));
        assertEquals(23.5, data.getDouble("value"));
        assertEquals("tr_aaa", data.getString("type"));
        // 值原样搬运：不缩放、不翻译、不把布尔变成「开/关」
        assertFalse(data.containsKey("error"));
    }

    /**
     * 尚未上报（影子压根没有这个 pid）：只给 pid 与型号，两个「有值」的键都不给。
     *
     * <p>与「值是 0」是两回事 —— 前端据此显示 {@code -}。同 {@code fieldRows} 的取舍。</p>
     */
    @Test
    void render_deviceCard_neverReported() {
        stubDevice("did-1", "tr_aaa");
        stubShadows(new JsonObject().put("pid", "did-1.1.2").put("value", 1));

        JsonObject data = data(render(device("w1", "did-1", PID)), 0);

        assertEquals(PID, data.getString("pid"));
        assertFalse(data.containsKey("value"));
        assertFalse(data.containsKey("error"));
    }

    /** 影子元素里有这个 pid 但没有值（状态是成功）：同样算「没有读数」，不补 0。 */
    @Test
    void render_deviceCard_elementWithoutValue() {
        stubDevice("did-1", "tr_aaa");
        stubShadows(new JsonObject().put("pid", PID).putNull("value"));

        JsonObject data = data(render(device("w1", "did-1", PID)), 0);

        assertFalse(data.containsKey("value"));
        assertFalse(data.containsKey("error"));
    }

    /**
     * 读取失败：元素是 {@code {pid, status, description}}（出错时不给 value）——
     * 那句描述原样给前端当 tooltip，不翻译。
     */
    @Test
    void render_deviceCard_readFailure() {
        stubDevice("did-1", "tr_aaa");
        stubShadows(new JsonObject().put("pid", PID).put("status", -422).put("description", "property cannot read"));

        JsonObject data = data(render(device("w1", "did-1", PID)), 0);

        assertEquals("property cannot read", data.getString("error"));
        assertFalse(data.containsKey("value"));
    }

    /** 有状态没有描述时不能给一个空的 error：空 tooltip 看不出是读取失败还是别的。 */
    @Test
    void render_deviceCard_failureWithoutDescription() {
        stubDevice("did-1", "tr_aaa");
        stubShadows(new JsonObject().put("pid", PID).put("status", -400));

        assertEquals("status -400", data(render(device("w1", "did-1", PID)), 0).getString("error"));
    }

    /** 型号查不到时仍给 pid（前端退回显示 pid 原文），但不编一个假的型号。 */
    @Test
    void render_deviceCard_unknownTypeIsOmitted() {
        stubShadows(new JsonObject().put("pid", PID).put("value", 1));

        JsonObject data = data(render(device("w1", "did-1", PID)), 0);

        assertEquals(PID, data.getString("pid"));
        assertFalse(data.containsKey("type"));
    }

    /** 一屏多张设备卡**只调一次**影子接口，did 去重后一起给它。 */
    @Test
    void render_deviceCardsShareOneCall() {
        stubDevices(deviceEntity("did-1", "tr_aaa"), deviceEntity("did-2", "tr_bbb"));
        stubShadows(new JsonObject().put("pid", PID).put("value", 1),
                new JsonObject().put("pid", "did-1.1.2").put("value", 2),
                new JsonObject().put("pid", "did-2.1.1").put("value", 3));

        DashboardWidgetDataService.WidgetData result = render(
                device("w1", "did-1", PID), device("w2", "did-1", "did-1.1.2"), device("w3", "did-2", "did-2.1.1"));

        assertEquals(1, result.widgets().get(0).data().getDouble("value"));
        assertEquals(2, result.widgets().get(1).data().getDouble("value"));
        assertEquals(3, result.widgets().get(2).data().getDouble("value"));

        ArgumentCaptor<List<String>> dids = ArgumentCaptor.forClass(List.class);
        verify(manipulationService, times(1)).getShadows(eq(SPACE_ID), dids.capture());
        // did 可重复，但只该送一次；顺序按卡片出现顺序（根的 id 就是空间 id，见 space()）
        assertEquals(List.of("did-1", "did-2"), dids.getValue());
    }

    /**
     * 影子那一批失败（跨服务调用挂了）**只黑设备卡**：同一屏里的统计卡照常出数。
     *
     * <p>这是 {@code fetchDevices} 里那个 recover 的全部意义 —— 批量调用只有一次往返，
     * 拆不出「哪台设备出错」，但绝不能把整份 render 打挂。</p>
     */
    @Test
    void render_deviceBatchFailureOnlyBlacksDeviceCards() {
        stubOverview();
        when(manipulationService.getShadows(anyString(), anyList()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("manipulation is down")));

        DashboardWidgetDataService.WidgetData result = render(stat("w1", "devices.total", null),
                device("w2", "did-1", PID));

        assertTrue(result.widgets().get(0).success());
        assertFalse(result.widgets().get(1).success());
        assertEquals("manipulation is down", result.widgets().get(1).message());
    }

    /** 没有设备卡时不碰 manipulation，也不查设备表。 */
    @Test
    void render_noDeviceCards_noDeviceQuery() {
        stubOverview();
        render(stat("w1", "devices.total", null));

        verifyNoInteractions(manipulationService);
        verifyNoInteractions(deviceRepo);
    }

    // ===== service =====

    /** 正常态：值 + 单位 + 采集时刻。单位取自服务定义（服务端手里本来就有那份）。 */
    @Test
    void render_serviceCard_valueUnitAndRecordedAt() {
        stubService(shadow(1, Map.of("温度", 23.5), AT, null, null), serviceDef(1, "温度", "℃", null));

        JsonObject data = data(render(serviceCard("w1", 1, List.of("温度"))), 0);

        assertEquals(AT.getTime(), data.getLong("recordedAt").longValue());
        JsonObject row = data.getJsonArray("fields").getJsonObject(0);
        assertEquals("温度", row.getString("field"));
        assertEquals(23.5, row.getDouble("value").doubleValue());
        assertEquals("℃", row.getString("unit"));
        assertFalse(row.getBoolean("bit"));
    }

    /** 新卡不存方法序号：字段（寄存器）按名解析到读方法再取数，结果与带方法的老卡一致。 */
    @Test
    void render_serviceCard_withoutPollingIndex_resolvesField() {
        stubService(shadow(1, Map.of("温度", 23.5), AT, null, null), serviceDef(1, "温度", "℃", null));

        JsonObject data = data(render(serviceCardRegisters("w1", "温度")), 0);

        JsonObject row = data.getJsonArray("fields").getJsonObject(0);
        assertEquals("温度", row.getString("field"));
        assertEquals(23.5, row.getDouble("value").doubleValue());
        assertEquals("℃", row.getString("unit"));
        assertFalse(row.getBoolean("bit"));
        assertEquals(AT.getTime(), data.getLong("recordedAt").longValue());
    }

    /** 新卡尚未采集：字段能解析但那个方法没有影子，只回 fields 的空与无 recordedAt（判据同老卡）。 */
    @Test
    void render_serviceCard_withoutPollingIndex_neverCollected() {
        stubService(null, serviceDef(1, "温度", "℃", null));

        JsonObject data = data(render(serviceCardRegisters("w1", "温度")), 0);

        assertFalse(data.containsKey("recordedAt"));
        assertFalse(data.containsKey("fields"));
    }

    /**
     * 尚未采集：一只影子都没有。「有没有 recordedAt」就是这一态的判据（前端据此渲染
     * 「尚未采集」），而一行都没有值可显示的空 fields 不是信息。
     */
    @Test
    void render_serviceCard_neverCollectedHasOnlyNoData() {
        stubService(null, serviceDef(1, "温度", "℃", null));

        JsonObject data = data(render(serviceCardRegisters("w1", "温度")), 0);

        assertFalse(data.containsKey("pollingIndex"));
        assertFalse(data.containsKey("recordedAt"));
        assertFalse(data.containsKey("fields"));
        assertFalse(data.containsKey("error"));
    }

    /**
     * 采集失败：影子里的值与时刻是**最后一次成功**的，失败时不动
     * （见 {@code ModbusServiceLastPolling}），所以值与 error 同时存在 —— 两个都下发，
     * 「最后的已知读数」不该被丢掉。
     */
    @Test
    void render_serviceCard_failureKeepsLastKnownValue() {
        stubService(shadow(1, Map.of("温度", 23.5), AT, "modbus timeout", ERROR_AT),
                serviceDef(1, "温度", "℃", null));

        JsonObject data = data(render(serviceCard("w1", 1, List.of("温度"))), 0);

        assertEquals(23.5, data.getJsonArray("fields").getJsonObject(0).getDouble("value").doubleValue());
        assertEquals("modbus timeout", data.getString("error"));
        assertEquals(ERROR_AT.getTime(), data.getLong("errorAt").longValue());
        // 时刻仍是最后一次**成功**那一刻，不是失败时刻
        assertEquals(AT.getTime(), data.getLong("recordedAt").longValue());
    }

    /** 这一轮没取到值的字段**不带 value 键**：前端显示 `-`，与「值是 0」不是一回事。 */
    @Test
    void render_serviceCard_fieldWithoutValueOmitsTheKey() {
        stubService(shadow(1, Map.of("温度", 23.5), AT, null, null),
                serviceDef(1, "温度", "℃", "湿度"));

        JsonObject data = data(render(serviceCard("w1", 1, List.of("温度", "湿度"))), 0);

        JsonArray rows = data.getJsonArray("fields");
        assertTrue(rows.getJsonObject(0).containsKey("value"));
        assertFalse(rows.getJsonObject(1).containsKey("value"));
    }

    /** 字段顺序照**配置顺序**（用户选的就是这个顺序），不是服务定义里的顺序。 */
    @Test
    void render_serviceCard_keepsConfiguredFieldOrder() {
        stubService(shadow(1, Map.of("湿度", 60, "温度", 23.5), AT, null, null),
                serviceDef(1, "温度", "℃", "湿度"));

        JsonObject data = data(render(serviceCard("w1", 1, List.of("湿度", "温度"))), 0);

        assertEquals("湿度", data.getJsonArray("fields").getJsonObject(0).getString("field"));
        assertEquals("温度", data.getJsonArray("fields").getJsonObject(1).getString("field"));
    }

    /** 位是独立点位：名字写在 {@code bitList} 上，没有单位（与历史页 fieldRef 的 {@code unit: ''} 一致）。 */
    @Test
    void render_serviceCard_bitFieldHasNoUnitAndBitTrue() {
        stubService(shadow(1, Map.of("进水阀", 1), AT, null, null),
                serviceDef(1, "状态字", "℃", null, "进水阀"));

        JsonObject row = data(render(serviceCard("w1", 1, List.of("进水阀"))), 0)
                .getJsonArray("fields").getJsonObject(0);

        assertEquals("", row.getString("unit"));
        assertTrue(row.getBoolean("bit"));
        assertEquals(1, row.getInteger("value").intValue());
    }

    /** 断言的是 `bit` 而不是「单位为空」：单位为空也可能是用户没填。 */
    @Test
    void render_serviceCard_plainFieldIsNotABit() {
        stubService(shadow(1, Map.of("温度", 23.5), AT, null, null),
                serviceDef(1, "温度", "℃", null, "进水阀"));

        JsonObject row = data(render(serviceCard("w1", 1, List.of("温度"))), 0)
                .getJsonArray("fields").getJsonObject(0);

        assertFalse(row.getBoolean("bit"));
        assertEquals("℃", row.getString("unit"));
    }

    /** 同一个服务的多张卡只取一次（服务卡与字段曲线卡共用这一路去重）。 */
    @Test
    void render_serviceCardsOfTheSameServiceShareOneQuery() {
        stubService(shadow(1, Map.of("温度", 23.5), AT, null, null), serviceDef(1, "温度", "℃", null));

        render(serviceCard("w1", 1, List.of("温度")), serviceCard("w2", 1, List.of("温度")));

        verify(registryShadowRepo, times(1)).listByServiceId("svc-1");
    }

    /**
     * 一个服务取数失败只黑引用它的卡片：{@code Uni.join().andFailFast()} 会把单个失败放大成
     * 整份 render 失败（= 一屏全黑），所以这一路是按服务 recover 的。
     */
    @Test
    void render_serviceFetchFailureBlacksOnlyThatCard() {
        stubOverview();
        when(registryShadowRepo.listByServiceId("svc-1"))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("mongo timeout")));
        when(serviceRepo.findById("svc-1")).thenReturn(Uni.createFrom().nullItem());

        DashboardWidgetDataService.WidgetData data = render(serviceCard("w1", 1, List.of("温度")),
                stat("w2", "devices.total", null));

        assertFalse(data.widgets().get(0).success());
        assertEquals("mongo timeout", data.widgets().get(0).message());
        assertTrue(data.widgets().get(1).success(), "另一张卡不该被牵连");
    }

    /** 丢掉了 message 的失败（NPE 之类）也要给出一句话，不能是空白原因。 */
    @Test
    void render_serviceFetchFailureWithoutMessageFallsBackToTheClassName() {
        when(registryShadowRepo.listByServiceId("svc-1"))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException()));
        when(serviceRepo.findById("svc-1")).thenReturn(Uni.createFrom().nullItem());

        assertEquals("IllegalStateException", render(serviceCard("w1", 1, List.of("温度")))
                .widgets().get(0).message());
    }

    /**
     * 写坏的 {@code source}（比如服务端加过一个取值又撤掉了）说清是哪一个，而不是笼统的
     * {@code line} —— 与 {@code line(null)} 同一句文案，它指出的是「这一格的值不认识」。
     */
    @Test
    void render_unknownLineSourceIsNotSupported() {
        stubOverview();
        Map<String, Object> config = config("source", "bogus", "window", last(24));
        DashboardWidgetDataService.Item item = render(widget("w1", "line", "W12H200", config)).widgets().get(0);

        assertFalse(item.success());
        assertEquals("widget type is not supported yet: line(bogus)", item.message());
    }

    /**
     * {@code failureType} 在 P1 没有数据源：{@code Overview.FailureStats} 只有总数与整点时间线，
     * 没有按类型的分布（故障按 message 去重，类型要先定义出来）。
     */
    @Test
    void render_failureTypeDistributionIsNotSupportedYet() {
        stubOverview();
        Map<String, Object> config = config("dimension", "failureType", "window", last(24));
        DashboardWidgetDataService.Item item = render(widget("w1", "distribution", "W12H200", config)).widgets().get(0);

        assertFalse(item.success());
        assertEquals("dimension is not supported yet: failureType", item.message());
    }

    /**
     * overview 只发给**真正读它**的卡片：统计卡、分布图、{@code line(alarmCount)}。
     *
     * <p>这条在 P2 之前是反过来的 —— 那时设备卡不接数据源，但它的兜底窗口照样并进分组，
     * 于是「多一张设备卡」会白白多查一次 overview。现在设备卡/服务卡都不进这一路：
     * 它们的时间字段来自各自的影子，与窗口无关。</p>
     */
    @Test
    void render_overviewIsQueriedOnlyForTheCardsThatReadIt() {
        stubOverview();
        stubService(shadow(1, Map.of("温度", 23.5), AT, null, null), serviceDef(1, "温度", "℃", null));

        render(stat("w1", "devices.total", null),
                distribution("w2", "deviceType", null),
                line("w3", last(24)),
                serviceCard("w4", 1, List.of("温度")));

        // 三张读 overview 的卡片窗口全是兜底/24h 两组，故两次；服务卡不额外贡献窗口
        verify(statisticsQueryService, times(2)).overview(eq(SPACE_ID), any(), any());
    }

    /** 一张 overview 卡片都没有时，一次都不该查。 */
    @Test
    void render_noOverviewCards_noOverviewQuery() {
        stubService(shadow(1, Map.of("温度", 23.5), AT, null, null), serviceDef(1, "温度", "℃", null));

        render(serviceCard("w1", 1, List.of("温度")));

        verifyNoInteractions(statisticsQueryService);
    }

    // ===== distribution =====

    @Test
    void render_deviceTypeDistribution() {
        stubOverview();
        JsonObject data = data(render(distribution("w1", "deviceType", null)), 0);

        JsonArray groups = data.getJsonArray("groups");
        assertEquals(2, groups.size());
        assertEquals("冷机", groups.getJsonObject(0).getString("key"));
        assertEquals(3, groups.getJsonObject(0).getInteger("count"));
        assertEquals("水泵", groups.getJsonObject(1).getString("key"));
    }

    /**
     * 分布**全量下发**，不在服务端截断：{@code limit} 是显示层的取舍，前端截成「前 N + 其他」，
     * 这样用户改 limit 不用重新取数，也不会出现「后端悄悄截断了、卡片上看不出来」。
     */
    @Test
    void render_distributionIsNotTruncated() {
        stubOverview();
        Map<String, Object> config = config("dimension", "deviceType", "chart", "pie", "limit", 1);
        JsonObject data = data(render(widget("w1", "distribution", "W12H200", config)), 0);

        assertEquals(2, data.getJsonArray("groups").size());
    }

    @Test
    void render_serviceTypeDistribution() {
        stubOverview();
        JsonObject data = data(render(distribution("w1", "serviceType", null)), 0);
        // key 是 configId：解成「厂家 型号」并合并同名是前端的事
        assertEquals("cfg1", data.getJsonArray("groups").getJsonObject(0).getString("key"));
    }

    @Test
    void render_alarmTypeDistribution() {
        stubOverview();
        JsonObject data = data(render(distribution("w1", "alarmType", last(24))), 0);
        // key 是用户自己填的告警文本，原样给出、永不翻译
        assertEquals("超温", data.getJsonArray("groups").getJsonObject(0).getString("key"));
    }

    // ===== line =====

    @Test
    void render_alarmCountLine() {
        stubOverview();
        JsonObject data = data(render(line("w1", last(24))), 0);

        assertEquals(1, data.getJsonArray("points").size());
        assertEquals(1_700_000_000_000L, data.getJsonArray("points").getJsonObject(0).getLong("at").longValue());
        assertEquals(2, data.getJsonArray("points").getJsonObject(0).getInteger("count"));
        // 曲线自带窗口：前端画 x 轴刻度用它，而不是用外层那个「各卡片窗口的并集」
        assertEquals(24 * HOUR, data.getLong("to").longValue() - data.getLong("from").longValue());
    }

    @Test
    void render_lineWithoutSourceIsNotSupported() {
        stubOverview();
        DashboardWidgetDataService.Item item = render(widget("w1", "line", "W12H200", config("window", last(24)))).widgets().get(0);
        assertFalse(item.success());
        assertEquals("widget type is not supported yet: line(null)", item.message());
    }

    // ===== line(serviceField)：Modbus 字段曲线 =====

    @Test
    void render_serviceFieldCurve_historyShape() {
        stubDefinition();
        events.add(registryEvent(1_700_000_000_000L, 23.5, false));

        JsonObject data = data(render(curve("w1", last(24))), 0);

        // 曲线这一段就是 /history/range 的响应体：前端两条路共用同一个 decodeRange
        assertEquals(SERVICE_ID, data.getString("serviceId"));
        assertEquals(1, data.getInteger("pollingIndex").intValue());
        assertEquals("温度", data.getString("field"));
        assertEquals(24 * HOUR, data.getLong("to") - data.getLong("from"));
        assertEquals(500, data.getInteger("maxPoints").intValue());
        assertFalse(data.getBoolean("downsampled"));
        assertEquals(1, data.getInteger("total").intValue());
        assertEquals(1, data.getJsonArray("points").size());
        assertEquals(23.5, data.getJsonArray("points").getJsonObject(0).getDouble("value").doubleValue());
        // 展示元数据来自服务定义（与只读曲线的服务共用同一批取数）
        assertEquals("℃", data.getString("unit"));
        assertFalse(data.getBoolean("step"));
    }

    @Test
    void render_serviceFieldCurve_noSamplesIsStillSuccess() {
        stubDefinition();
        // 一个点都没有不是错误：这段时间这个点位没有采样（或还没采过），图上就是一条空曲线
        JsonObject data = data(render(curve("w1", last(24))), 0);

        assertEquals(0, data.getJsonArray("points").size());
        assertEquals(0, data.getInteger("total").intValue());
    }

    @Test
    void render_serviceFieldCurve_downsampledPointsAreBuckets() {
        stubDefinition();
        // 3 条样本、只要 2 个点 → 折成桶，前端据 downsampled 按桶解码（三个统计量）
        events.add(registryEvent(1_700_000_000_000L, 1.0, false));
        events.add(registryEvent(1_700_000_600_000L, 2.0, false));
        events.add(registryEvent(1_700_001_200_000L, 3.0, false));

        JsonObject data = data(render(curve("w1", last(24), config("maxPoints", 2))), 0);

        assertTrue(data.getBoolean("downsampled"));
        assertTrue(data.getJsonArray("points").getJsonObject(0).containsKey("until"));
        assertEquals(2, data.getInteger("maxPoints").intValue());
    }

    @Test
    void render_serviceFieldCurve_bitIsStepWithoutUnit() {
        stubDefinition();
        events.add(registryEvent(1_700_000_000_000L, 1, false));

        JsonObject data = data(render(curve("w1", last(24), config("field", "进水阀"))), 0);

        // 位是 0/1 的阶梯（0 一直保持到下一次翻转），直连会画出根本不存在的中间值
        assertTrue(data.getBoolean("step"));
        // 位没有单位：与历史页 fieldRef 的 `unit: ''` 一致
        assertEquals("", data.getString("unit"));
    }

    @Test
    void render_serviceFieldCurve_failuresAreMillisOfTheEvent() {
        stubDefinition();
        events.add(registryEvent(1_700_000_000_000L, 1, false));
        // 失败事件不带值：它与值曲线**同一条源**（findRange 一次取回，服务筛开）
        events.add(registryEvent(ERROR_AT.getTime(), null, true));
        events.add(registryEvent(AT.getTime(), null, true));

        JsonObject data = data(render(curve("w1", last(24))), 0);
        JsonArray times = data.getJsonArray("failures");

        assertEquals(2, times.size());
        assertEquals(ERROR_AT.getTime(), times.getLong(0).longValue());
        assertEquals(AT.getTime(), times.getLong(1).longValue());
        // 失败是**寄存器**的事：竖线来自它的失败事件，不再单独发一次失败查询
    }

    @Test
    void render_serviceFieldCurve_invalidWindowBlacksOnlyItsCard() {
        stubDefinition();
        // 只有 12h 那条窗口撞上限：寄存器历史查询报错，只该黑掉落在它上面的那张卡
        doAnswer(invocation -> {
            Date to = invocation.getArgument(3);
            Date from = invocation.getArgument(2);
            if (to.getTime() - from.getTime() == 12 * HOUR) {
                return Uni.createFrom().failure(new IllegalArgumentException("window must not exceed 31 days"));
            }
            return Uni.createFrom().item(List.of());
        }).when(registryHistoryRepo).findRange(anyString(), any(), any(), any(), anyInt());

        DashboardWidgetDataService.WidgetData data = render(curve("good", last(24)), curve("bad", last(12)));

        assertTrue(data.widgets().get(0).success());
        assertFalse(data.widgets().get(1).success());
        assertEquals("window must not exceed 31 days", data.widgets().get(1).message());
    }

    /**
     * 同一条曲线只查一次；**点数不同就是两条曲线** —— 只按「寄存器 × 窗口」去重会让其中一张卡
     * 拿到另一张的点数，而它看起来完全正常（本来就是一组合法的采样）。
     */
    @Test
    void render_curvesAreDedupedByPointCountToo() {
        stubDefinition();
        events.add(registryEvent(1_700_000_000_000L, 1, false));

        render(curve("a", last(24)), curve("b", last(24)),
                curve("c", last(24), config("maxPoints", 2000)));

        verify(registryHistoryRepo, times(2)).findRange(anyString(), any(), any(), any(), anyInt());
    }

    // ===== helpers =====

    /** 一条字段曲线的卡片：三元组 + 窗口，`extra` 可以改字段名或点数。 */
    private static WebDashboardWidget curve(String id, Map<String, Object> window, Map<String, Object> extra) {
        Map<String, Object> config = config("source", "serviceField", "serviceId", SERVICE_ID,
                "pollingIndex", 1, "field", "温度", "window", window);
        config.putAll(extra);
        return widget(id, "line", "W12H200", config);
    }

    private static WebDashboardWidget curve(String id, Map<String, Object> window) {
        return curve(id, window, Map.of());
    }

    /** 一次寄存器历史事件：成功带 {@code value}，失败带 {@code failure=true} 不带值。 */
    private static ModbusServiceRegistryHistory registryEvent(long at, Object value, boolean failure) {
        ModbusServiceRegistryHistory event = new ModbusServiceRegistryHistory();
        event.serviceId = SERVICE_ID;
        event.registerIndex = 1;
        event.name = "温度";
        event.unit = "℃";
        event.at = new Date(at);
        event.failure = failure;
        event.value = value;
        return event;
    }


    private DashboardWidgetDataService.WidgetData render(WebDashboardWidget... widgets) {
        return service.render(space(), List.of(widgets)).await().indefinitely();
    }

    private static SpaceEntity space() {
        SpaceEntity s = new SpaceEntity();
        s.id = new ObjectId(SPACE_ID);
        s.name = "测试项目";
        return s;
    }

    /** 一次取数的固定返回：设备 10/在线 7、服务 2、窗口内告警 5、故障 1 种。 */
    private static StatisticsQueryService.Overview overview(Date from, Date to) {
        return new StatisticsQueryService.Overview(from, to,
                new StatisticsQueryService.DeviceStats(10, 7, List.of(
                        new StatisticsQueryService.Count("冷机", 3),
                        new StatisticsQueryService.Count("水泵", 2))),
                new StatisticsQueryService.ServiceStats(2, List.of(
                        new StatisticsQueryService.Count("cfg1", 2))),
                new StatisticsQueryService.AlarmStats(5, List.of(
                        new StatisticsQueryService.Count("超温", 4)),
                        List.of(new StatisticsQueryService.Bucket(1_700_000_000_000L, 2))),
                new StatisticsQueryService.FailureStats(1, List.of(
                        new StatisticsQueryService.Bucket(1_700_000_000_000L, 1))));
    }

    private void stubOverview() {
        when(statisticsQueryService.overview(eq(SPACE_ID), any(), any()))
                .thenAnswer(invocation -> Uni.createFrom()
                        .item(overview(invocation.getArgument(1), invocation.getArgument(2))));
    }

    private static WebDashboardWidget[] widgets() {
        return new WebDashboardWidget[0];
    }

    private static WebDashboardWidget stat(String id, String metric, Map<String, Object> window) {
        Map<String, Object> config = config("metric", metric);
        if (window != null) {
            config.put("window", window);
        }
        return widget(id, "stat", "W6H92", config);
    }

    private static WebDashboardWidget distribution(String id, String dimension, Map<String, Object> window) {
        Map<String, Object> config = config("dimension", dimension);
        if (window != null) {
            config.put("window", window);
        }
        return widget(id, "distribution", "W12H200", config);
    }

    private static WebDashboardWidget line(String id, Map<String, Object> window) {
        return widget(id, "line", "W12H200", config("source", "alarmCount", "window", window));
    }

    private static WebDashboardWidget device(String id, String did, String pid) {
        return widget(id, "device", "W12H200", config("did", did, "pid", pid));
    }

    /** 一次查询返回的设备（只为型号：卡片把它转给前端去查产品规格）。 */
    private void stubDevice(String did, String type) {
        stubDevices(deviceEntity(did, type));
    }

    private void stubDevices(DeviceEntity... devices) {
        when(deviceRepo.findByDeviceIds(anyList())).thenReturn(Uni.createFrom().item(List.of(devices)));
    }

    private static DeviceEntity deviceEntity(String did, String type) {
        DeviceEntity device = new DeviceEntity();
        device.did = did;
        device.type = type;
        return device;
    }

    /**
     * 影子元素。{@code [{pid, value}]} 是正常、{@code [{pid, status, description}]} 是读失败 ——
     * 两种形状与 {@code PropertyOperationCodec.Get.RESULT} 的编码一致。
     */
    private void stubShadows(JsonObject... elements) {
        JsonArray array = new JsonArray();
        for (JsonObject element : elements) {
            array.add(element);
        }
        when(manipulationService.getShadows(anyString(), anyList())).thenReturn(Uni.createFrom().item(array));
    }

    private static WebDashboardWidget serviceCard(String id, int pollingIndex, List<String> fields) {
        return widget(id, "service", "W12H200", config("serviceId", SERVICE_ID,
                "pollingIndex", pollingIndex, "fields", fields));
    }

    /** 新卡服务卡：不存方法序号，只给服务 + 寄存器字段。 */
    private static WebDashboardWidget serviceCardRegisters(String id, String... fields) {
        return widget(id, "service", "W12H200", config("serviceId", SERVICE_ID, "fields", List.of(fields)));
    }

    /**
 * 服务卡：stub 服务定义 + 逐寄存器影子。入参仍是一个 {@code ModbusServiceLastPolling}（承载
 * 「字段 → 值」与失败态），convert 成服务定义里每只寄存器的影子：字段名 == 寄存器名，没有该
 * 字段 = 那只寄存器还没采到（value 空，卡片据此显示 {@code -}）。
 */
private void stubService(ModbusServiceLastPolling shadow, ModbusService definition) {
    List<ModbusServiceShadow> shadows = new ArrayList<>();
    if (shadow != null && definition != null && definition.configSnapshot != null
            && definition.configSnapshot.registries != null) {
        for (ModbusRegistry reg : definition.configSnapshot.registries) {
            ModbusServiceShadow s = new ModbusServiceShadow();
            s.serviceId = SERVICE_ID;
            s.registerIndex = reg.index;
            s.name = reg.name;
            s.unit = reg.unit;
            s.value = shadow.fields == null ? null : shadow.fields.get(reg.name);
            s.recordedAt = shadow.recordedAt;
            s.lastError = shadow.lastError;
            s.errorAt = shadow.errorAt;
            shadows.add(s);
        }
    }
    when(registryShadowRepo.listByServiceId(SERVICE_ID)).thenReturn(Uni.createFrom().item(shadows));
    when(serviceRepo.findById(SERVICE_ID)).thenReturn(Uni.createFrom().item(definition));
}

/** 服务定义：方法与「温度」（带单位），位区里有一个「进水阀」。曲线卡要的 unit / step 从这来。 */
private void stubDefinition() {
    when(serviceRepo.findById(SERVICE_ID))
            .thenReturn(Uni.createFrom().item(serviceDef(1, "温度", "℃", null, "进水阀")));
}

    /** 一条影子。{@code lastError} / {@code errorAt} 为 null 表示这个 function 没失败过。 */
    private static ModbusServiceLastPolling shadow(int pollingIndex, Map<String, Object> fields,
                                              Date recordedAt, String lastError, Date errorAt) {
        ModbusServiceLastPolling shadow = new ModbusServiceLastPolling();
        shadow.serviceId = SERVICE_ID;
        shadow.pollingIndex = pollingIndex;
        shadow.fields = fields;
        shadow.recordedAt = recordedAt;
        shadow.lastError = lastError;
        shadow.errorAt = errorAt;
        return shadow;
    }

    /**
     * 一个方法：{@code primary} 是主字段（带单位），{@code bit} 是它位区里的一个位名
     * （没有单位）。两者可以都给 —— 那正是「位名与父字段并存」的实际情况。
     */
    private static ModbusService serviceDef(int pollingIndex, String primary, String unit, String second,
                                            String... bits) {
        List<ModbusPollingResponseField> fields = new ArrayList<>();
        fields.add(field(primary, unit, bits.length == 0 ? null : List.of(bits)));
        if (second != null) {
            fields.add(field(second, "％", null));
        }
        ModbusPolling function = new ModbusPolling();
                    function.from = new ModbusPollingFrom();
                    function.from.type = "command";
                    function.from.index = pollingIndex;
                    function.index = pollingIndex;
        function.name = primary;
        function.response = ModbusPollingResponses.of(fields);

        ModbusService definition = new ModbusService();
        definition.id = new ObjectId("6512f3a4b5c6d7e8f9a0b1c9");
        definition.name = "1 号冷水机组";
        definition.functions = List.of(function);
        // 点表快照的寄存器：主字段、次字段、位各是一只可读寄存器（服务卡按名字解析到它的 index）
        List<ModbusRegistry> registries = new ArrayList<>();
        int index = 1;
        registries.add(register(index++, primary, unit, "holding"));
        if (second != null) {
            registries.add(register(index++, second, "％", "holding"));
        }
        for (String bit : bits) {
            registries.add(register(index++, bit, "", "coil"));
        }
        ModbusConfig config = new ModbusConfig();
        config.registries = registries;
        definition.configSnapshot = config;
        return definition;
    }

    /** 一只可读寄存器：卡片把配置的字段名解析到它、并读它 index 对应的影子。 */
    private static ModbusRegistry register(int index, String name, String unit, String area) {
        ModbusRegistry reg = new ModbusRegistry();
        reg.index = index;
        reg.name = name;
        reg.unit = unit;
        reg.area = area;
        reg.access = "R";
        reg.address = index - 1;
        return reg;
    }

    private static ModbusPollingResponseField field(String name, String unit, List<String> bits) {
        ModbusPollingResponseField f = new ModbusPollingResponseField();
        f.field = name;
        f.unit = unit;
        if (bits != null) {
            List<ModbusPollingResponseFieldBit> bitList = new ArrayList<>();
            int offset = 0;
            for (String bit : bits) {
                ModbusPollingResponseFieldBit b = new ModbusPollingResponseFieldBit();
                b.offset = offset++;
                b.field = bit;
                bitList.add(b);
            }
            f.bitList = bitList;
        }
        return f;
    }

    private static WebDashboardWidget widget(String id, String type, String size, Map<String, Object> config) {
        WebDashboardWidget w = new WebDashboardWidget();
        w.id = id;
        w.type = type;
        w.size = size;
        w.config = config;
        return w;
    }

    private static Map<String, Object> config(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> last(int hours) {
        return config("kind", "last", "hours", hours);
    }

    private static JsonObject data(DashboardWidgetDataService.WidgetData data, int index) {
        DashboardWidgetDataService.Item item = data.widgets().get(index);
        assertTrue(item.success(), () -> "widget " + item.id() + " failed: " + item.message());
        return item.data();
    }
}
