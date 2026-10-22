package cc.openxiot.matrix.api.modbus.shadow;

import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceLastPolling;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceLastPollingRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequests;
import cc.openxiot.matrix.db.modbus.service.polling.response.ModbusPollingResponses;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseField;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModbusShadowService}（寄存器缓存网格）的归并逻辑：不连数据库，钉住
 * 「可读寄存器 × 最后一次轮询快照」的归并、状态三态、以及批量/容错口径。
 *
 * <p>只测纯读投影本身；鉴权在资源层，这里不涉及。</p>
 */
class ModbusShadowServiceTest {

    private static final String SERVICE_ID = "65f0c0ffee0123456789abcd";
    private static final String CONFIG_ID = "65f0c0ffee0123456789abce";

    private ModbusShadowService shadowService;
    private ModbusServiceRepository repo;
    private ModbusConfigRepository configRepo;
    private ModbusServiceLastPollingRepository lastPollingRepo;

    @BeforeEach
    void setUp() {
        repo = mock(ModbusServiceRepository.class);
        configRepo = mock(ModbusConfigRepository.class);
        lastPollingRepo = mock(ModbusServiceLastPollingRepository.class);
        shadowService = new ModbusShadowService();
        shadowService.repo = repo;
        shadowService.configRepo = configRepo;
        shadowService.lastPollingRepo = lastPollingRepo;
    }

    @Test
    void registerCacheMergesRegistersIntoGrid() {
        long t0 = 1_700_000_000_000L;
        givenService(List.of(readFunction(1, "进水温度"), readFunction(2, "出水温度")));
        when(configRepo.findById(CONFIG_ID)).thenReturn(Uni.createFrom().item(registerConfig()));
        when(lastPollingRepo.listByServiceId(SERVICE_ID)).thenReturn(Uni.createFrom().item(List.of(
                lastPolling(1, Map.of("进水温度", 61.5), new Date(t0), null, null),
                lastPolling(2, Map.of("出水温度", 35.0), new Date(t0 + 60_000), "no response", new Date(t0 + 120_000)))));

        List<ModbusRegisterSnapshot> grid = shadowService.registerCache(SERVICE_ID).await().indefinitely();

        // 只列可读寄存器（access R/RW），写寄存器不出现
        assertEquals(3, grid.size());
        // 有值 + 最近成功 → current
        ModbusRegisterSnapshot temp = grid.get(0);
        assertEquals("进水温度", temp.name);
        assertEquals(61.5, temp.value);
        assertEquals(1, temp.pollingIndex);
        assertEquals(t0, temp.recordedAt.getTime());
        assertEquals("current", temp.status);
        // 有值 + 最近失败 → stale，值仍是最后已知值
        ModbusRegisterSnapshot outTemp = grid.get(1);
        assertEquals("出水温度", outTemp.name);
        assertEquals(35.0, outTemp.value);
        assertEquals("stale", outTemp.status);
        assertEquals("no response", outTemp.lastError);
        // 可读但从没被调用过 → no-data，无值、无方法归属
        ModbusRegisterSnapshot pressure = grid.get(2);
        assertEquals("压力", pressure.name);
        assertNull(pressure.value);
        assertNull(pressure.pollingIndex);
        assertEquals("no-data", pressure.status);

        verify(configRepo).findById(CONFIG_ID);
    }

    @Test
    void registerCacheWithoutConfigReturnsEmpty() {
        givenService(List.of(readFunction(1, "进水温度")));
        when(configRepo.findById(CONFIG_ID)).thenReturn(Uni.createFrom().nullItem());

        List<ModbusRegisterSnapshot> grid = shadowService.registerCache(SERVICE_ID).await().indefinitely();

        assertTrue(grid.isEmpty());
    }

    @Test
    void registerCacheWhenServiceMissingReturnsEmpty() {
        // 服务不存在：读取是投影，「能给多少给多少」，不因单个 id 失败
        when(repo.findById(SERVICE_ID)).thenReturn(Uni.createFrom().nullItem());

        List<ModbusRegisterSnapshot> grid = shadowService.registerCache(SERVICE_ID).await().indefinitely();

        assertTrue(grid.isEmpty());
    }

    @Test
    void registerCachesGroupsByServiceAndKeepsOrder() {
        String id2 = "65f0c0ffee0123456789abcf";
        givenService(List.of(readFunction(1, "进水温度")));
        // 第二个服务：同样的点表，但从未被调用（no-data）
        ModbusService svc2 = service();
        svc2.id = new ObjectId(id2);
        when(repo.findById(id2)).thenReturn(Uni.createFrom().item(svc2));
        when(configRepo.findById(CONFIG_ID)).thenReturn(Uni.createFrom().item(registerConfig()));
        when(lastPollingRepo.listByServiceId(SERVICE_ID)).thenReturn(Uni.createFrom().item(List.of(
                lastPolling(1, Map.of("进水温度", 61.5), new Date(1_700_000_000_000L), null, null))));
        when(lastPollingRepo.listByServiceId(id2)).thenReturn(Uni.createFrom().item(List.of()));

        Map<String, List<ModbusRegisterSnapshot>> grid =
                shadowService.registerCaches(List.of(SERVICE_ID, id2)).await().indefinitely();

        assertEquals(2, grid.size());
        assertEquals(3, grid.get(SERVICE_ID).size());
        assertEquals("current", grid.get(SERVICE_ID).get(0).status);
        assertEquals(3, grid.get(id2).size());
        assertEquals("no-data", grid.get(id2).get(0).status);
    }

    @Test
    void registerCachesToleratesMissingService() {
        String bogus = "dea1dbeef000000000000001";
        givenService(List.of(readFunction(1, "进水温度")));
        when(repo.findById(bogus)).thenReturn(Uni.createFrom().nullItem());
        when(configRepo.findById(CONFIG_ID)).thenReturn(Uni.createFrom().item(registerConfig()));
        when(lastPollingRepo.listByServiceId(SERVICE_ID)).thenReturn(Uni.createFrom().item(List.of()));

        Map<String, List<ModbusRegisterSnapshot>> grid =
                shadowService.registerCaches(List.of(SERVICE_ID, bogus)).await().indefinitely();

        // 有效的那家正常出网格；不存在的按空网格
        assertTrue(grid.get(SERVICE_ID).size() > 0);
        assertTrue(grid.get(bogus).isEmpty());
    }

    // ---- 夹具 ----

    private void givenService(List<ModbusPolling> functions) {
        ModbusService svc = service();
        svc.functions = functions;
        when(repo.findById(SERVICE_ID)).thenReturn(Uni.createFrom().item(svc));
    }

    private static ModbusService service() {
        ModbusService svc = new ModbusService();
        svc.id = new ObjectId(SERVICE_ID);
        svc.configId = CONFIG_ID;
        return svc;
    }

    private static ModbusRegistry register(String name, String area, String access, String unit) {
        ModbusRegistry reg = new ModbusRegistry();
        reg.name = name;
        reg.area = area;
        reg.access = access;
        reg.unit = unit;
        return reg;
    }

    private static ModbusConfig registerConfig() {
        ModbusConfig cfg = new ModbusConfig();
        cfg.registries = List.of(
                register("进水温度", "holding", "R", "℃"),
                register("出水温度", "holding", "RW", "℃"),
                register("电机运行", "coil", "W", null),          // 写寄存器：不参与缓存
                register("压力", "input", "R", "MPa"));            // 可读但无读方法 → no-data
        return cfg;
    }

    private static ModbusPolling readFunction(int index, String fieldName) {
        ModbusPolling fn = new ModbusPolling();
        fn.index = index;
        fn.name = "读" + fieldName;
        fn.request = ModbusPollingRequests.holding();
        ModbusPollingResponseField f = new ModbusPollingResponseField();
        f.index = 1;
        f.field = fieldName;
        f.bytes = 2;
        f.format = "uint16";
        f.byteOrder = "ABCD";
        fn.response = ModbusPollingResponses.of(f);
        return fn;
    }

    private static ModbusServiceLastPolling lastPolling(int index, Map<String, Object> fields,
                                                        Date recordedAt, String lastError, Date errorAt) {
        ModbusServiceLastPolling lp = new ModbusServiceLastPolling();
        lp.serviceId = SERVICE_ID;
        lp.pollingIndex = index;
        lp.fields = fields;
        lp.recordedAt = recordedAt;
        lp.lastError = lastError;
        lp.errorAt = errorAt;
        return lp;
    }
}