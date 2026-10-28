package cc.openxiot.matrix.api.dashboard.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.dashboard.web.WebDashboardWidget;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceRepository;
import cc.openxiot.matrix.db.modbus.fd.DeviceOn;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.fd.FieldDeviceRepository;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.SpaceOn;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 归属校验：卡片引用的设备/服务是否在**本项目的子树**里。
 *
 * <p>这个类在 P1 时不存在，而它本该存在 —— 缺了它，一个把**设备组根 id** 当成**空间根 id**
 * 来比的缺陷一路走到了验收（设备卡当时在编辑器里建不出来，所以一直没被看见）。本类里
 * {@link #device_ownSpaceRootIdIsADeviceGroupRoot_stillBelongs()} 就是钉那个缺陷的：
 * 设备上那个 {@code rootId} 与空间根**是两个命名空间**，判定一律不碰它。</p>
 *
 * <p>三个上游都是 {@code @InjectMock}：本类要钉的不是 Mongo 查询怎么写，而是
 * **拿什么当基准、以及哪些情况算不属于**。</p>
 */
@QuarkusTest
class WidgetOwnershipValidatorTest {

    private static final String ROOT_ID = "6512f3a4b5c6d7e8f9a0b1c2";
    private static final String CHILD_ID = "6512f3a4b5c6d7e8f9a0b1c3";
    private static final String OTHER_ID = "6512f3a4b5c6d7e8f9a0b1c4";

    /** 服务 id 用十六进制原文，与卡片 config 里填的是同一个字符串（{@code loadServices} 按原文查）。 */
    private static final String SERVICE_ID = "6512f3a4b5c6d7e8f9a0b1c5";

    @InjectMock
    SpaceService spaceService;

    @InjectMock
    FieldDeviceRepository fdRepo;

    @InjectMock
    DeviceRepository deviceRepo;

    @Inject
    DashboardWidgetOwnershipValidator validator;

    /** 默认：根空间 + 一个子空间；两个库都查不到东西。要测什么的用例各自再 stub。 */
    @BeforeEach
    void stubEmptyLibraries() {
        when(spaceService.getDescendants(ROOT_ID))
                .thenReturn(Uni.createFrom().item(List.of(space(ROOT_ID), space(CHILD_ID))));
        when(deviceRepo.findByDeviceIds(anyList())).thenReturn(Uni.createFrom().item(List.of()));
        when(fdRepo.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Uni.createFrom().nullItem());
    }

    // ===== 设备 =====

    /**
     * 修掉的那个缺陷：设备落在本空间，但设备文档上的 {@code rootId} 是**设备组的根**
     * —— 与空间根完全不同的命名空间。判定不看它，所以这张卡必须通过。
     */
    @Test
    void device_ownSpaceRootIdIsADeviceGroupRoot_stillBelongs() {
        stubDevices(device("did-1", ROOT_ID, "device-group-root-xyz"));

        assertTrue(errors(device("w1", "did-1")).isEmpty());
    }

    /** 子树：子空间里的设备算本项目可用（否则会出现「卡片存得下、渲染却是空的」）。 */
    @Test
    void device_inChildSpace_belongs() {
        stubDevices(device("did-1", CHILD_ID, null));

        assertTrue(errors(device("w1", "did-1")).isEmpty());
    }

    @Test
    void device_inAnotherSpace_doesNotBelong() {
        stubDevices(device("did-1", OTHER_ID, ROOT_ID));

        assertEquals("widgets[0](w1): device does not belong to this space: did-1",
                errors(device("w1", "did-1")).get("w1"));
    }

    @Test
    void device_notFound() {
        assertEquals("widgets[0](w1): device not found: did-1", errors(device("w1", "did-1")).get("w1"));
    }

    /**
     * 设备还没有落点（{@code DeviceService.updateSpace} 之前的设备就是这样）。
     * 算不属于，但**不能抛 NPE** —— 判据里不能把 null 交给 {@code Set.contains}。
     */
    @Test
    void device_withoutSpace_doesNotBelongAndDoesNotThrow() {
        DeviceEntity device = device("did-1", null, null);
        device.space = null;
        stubDevices(device);

        assertEquals("widgets[0](w1): device does not belong to this space: did-1",
                errors(device("w1", "did-1")).get("w1"));
    }

    // ===== 服务 =====

    @Test
    void service_inOwnSpace_belongs() {
        stubFd(fd(SERVICE_ID, ROOT_ID, register(1, "温度", "R")));

        assertTrue(errors(serviceWithRegisters("w1", SERVICE_ID, 1)).isEmpty());
    }

    @Test
    void service_inChildSpace_belongs() {
        stubFd(fd(SERVICE_ID, CHILD_ID, register(1, "温度", "R")));

        assertTrue(errors(serviceWithRegisters("w1", SERVICE_ID, 1)).isEmpty());
    }

    /**
     * 服务上的落点快照写的是别的空间（搬迁后没更新），但活值 {@code space.spaceId} 在本子树里
     * —— 判定走活值，所以通过。旧写法比的是那个快照，会把这张卡误判成不属于本空间。
     */
    @Test
    void service_staleRootIdSnapshotButLiveSpaceIdIsInSubtree_belongs() {
        FieldDevice stale = fd(SERVICE_ID, ROOT_ID, register(1, "温度", "R"));
        stale.device.space.rootId = OTHER_ID;
        stubFd(stale);

        assertTrue(errors(serviceWithRegisters("w1", SERVICE_ID, 1)).isEmpty());
    }

    @Test
    void service_inAnotherSpace_doesNotBelong() {
        stubFd(fd(SERVICE_ID, OTHER_ID, register(1, "温度", "R")));

        assertEquals("widgets[0](w1): fd does not belong to this space: " + SERVICE_ID,
                errors(serviceWithRegisters("w1", SERVICE_ID, 1)).get("w1"));
    }

    @Test
    void service_withoutSpace_doesNotBelongAndDoesNotThrow() {
        FieldDevice orphan = fd(SERVICE_ID, ROOT_ID, register(1, "温度", "R"));
        orphan.device.space = null;
        stubFd(orphan);

        assertEquals("widgets[0](w1): fd does not belong to this space: " + SERVICE_ID,
                errors(serviceWithRegisters("w1", SERVICE_ID, 1)).get("w1"));
    }

    @Test
    void service_notFound() {
        assertEquals("widgets[0](w1): fd not found: " + SERVICE_ID,
                errors(serviceWithRegisters("w1", SERVICE_ID, 1)).get("w1"));
    }

    // ===== 字段身份：寄存器序号（registryIndex），不是寄存器名 =====

    /** 多只寄存器一次引用：每只都要在点表快照里解析得到。 */
    @Test
    void service_multipleRegisters_belongs() {
        stubFd(fd(SERVICE_ID, ROOT_ID, register(1, "温度", "R"), register(2, "压力", "RW")));

        assertTrue(errors(serviceWithRegisters("w1", SERVICE_ID, 1, 2)).isEmpty());
    }

    /** 序号解析不到（写错 / 服务已换点表）时照旧报错，不让它存下去。 */
    @Test
    void service_registerNotFound() {
        stubFd(fd(SERVICE_ID, ROOT_ID, register(1, "温度", "R")));

        assertEquals("widgets[0](w1): register not found: 9",
                errors(serviceWithRegisters("w1", SERVICE_ID, 9)).get("w1"));
    }

    /** 只写寄存器（W）没有读数：配上它只会永远显示「尚未采集」。 */
    @Test
    void service_registerNotReadable() {
        stubFd(fd(SERVICE_ID, ROOT_ID, register(1, "温度", "W")));

        assertEquals("widgets[0](w1): register not readable: 1",
                errors(serviceWithRegisters("w1", SERVICE_ID, 1)).get("w1"));
    }

    /**
     * 同名寄存器（ADL400 的「发生时间：分、时」）按序号判定，不会互相串位：
     * 序号 1 可读、序号 2 只写 —— 判定只看序号指向的那一只。
     */
    @Test
    void service_duplicateNames_resolveByIndexNotName() {
        stubFd(fd(SERVICE_ID, ROOT_ID,
                register(1, "发生时间", "R"), register(2, "发生时间", "W")));

        assertTrue(errors(serviceWithRegisters("w1", SERVICE_ID, 1)).isEmpty());
        assertEquals("widgets[0](w1): register not readable: 2",
                errors(serviceWithRegisters("w1", SERVICE_ID, 2)).get("w1"));
    }

    /** 曲线卡（fdField 数据源）同样按寄存器序号引用。 */
    @Test
    void line_fdField_registerResolves() {
        stubFd(fd(SERVICE_ID, ROOT_ID, register(1, "温度", "R")));

        assertTrue(errors(lineServiceField("w1", SERVICE_ID, 1)).isEmpty());
    }

    /** 曲线卡的序号解析不到照旧报错。 */
    @Test
    void line_fdField_registerNotFound() {
        stubFd(fd(SERVICE_ID, ROOT_ID, register(1, "温度", "R")));

        assertEquals("widgets[0](w1): register not found: 9",
                errors(lineServiceField("w1", SERVICE_ID, 9)).get("w1"));
    }

    // ===== 编排：什么时候查库、错误之间互不牵连 =====

    /** stat / distribution 只读本空间聚合，没有用户填的 id，一次库都不该查。 */
    @Test
    void statAndDistributionCards_doNotTouchTheDatabase() {
        assertTrue(errors(widget("w1", "stat", config("metric", "devices.total")),
                widget("w2", "distribution", config("dimension", "deviceType"))).isEmpty());

        verifyNoInteractions(spaceService, deviceRepo, fdRepo);
    }

    @Test
    void noWidgets_doesNotTouchTheDatabase() {
        assertTrue(errors().isEmpty());
        verifyNoInteractions(spaceService, deviceRepo, fdRepo);
    }

    /** 一张卡出错不影响另一张：渲染路径靠这张表逐张隔离，不是整屏失败。 */
    @Test
    void oneBadWidgetDoesNotAffectAnother() {
        stubDevices(device("did-1", ROOT_ID, null));

        Map<String, String> errors = errors(device("w1", "did-1"), device("w2", "did-missing"));

        assertFalse(errors.containsKey("w1"));
        assertEquals("widgets[1](w2): device not found: did-missing", errors.get("w2"));
    }

    /** 报错带下标与 widgetId：编辑器据此定位到那张卡（下标会随排序变化，id 不会）。 */
    @Test
    void errorPrefixCarriesIndexAndWidgetId() {
        stubDevices(device("did-1", OTHER_ID, null));

        assertEquals("widgets[2](w3): device does not belong to this space: did-1",
                errors(widget("w1", "stat", config("metric", "devices.total")),
                        widget("w2", "stat", config("metric", "fds.total")),
                        device("w3", "did-1")).get("w3"));
    }

    // ===== helpers =====

    private Map<String, String> errors(WebDashboardWidget... widgets) {
        return validator.errorsByWidget(root(), List.of(widgets)).await().indefinitely();
    }

    private void stubDevices(DeviceEntity... devices) {
        when(deviceRepo.findByDeviceIds(anyList())).thenReturn(Uni.createFrom().item(List.of(devices)));
    }

    private void stubFd(FieldDevice fd) {
        when(fdRepo.findById(SERVICE_ID)).thenReturn(Uni.createFrom().item(fd));
    }

    /** 根空间：{@code parentId} 为空，故 {@code rootSpaceId()} 就是它自己。 */
    private static SpaceEntity root() {
        return space(ROOT_ID);
    }

    private static SpaceEntity space(String id) {
        SpaceEntity s = new SpaceEntity();
        s.id = new ObjectId(id);
        s.parentId = ROOT_ID.equals(id) ? null : ROOT_ID;
        s.ancestors = ROOT_ID.equals(id) ? List.of() : List.of(ROOT_ID);
        return s;
    }

    private static DeviceEntity device(String did, String spaceId, String deviceGroupRootId) {
        DeviceEntity d = new DeviceEntity();
        d.did = did;
        d.rootId = deviceGroupRootId;
        d.space = spaceId == null ? null : new SpaceOn(space(spaceId));
        return d;
    }

    /**
     * 服务定义夹具：卡片只按 {@code registryIndex} 校验，故只需点表快照（polls 不参与判定）。
     */
    private static FieldDevice fd(String id, String spaceId, ModbusRegistry... registers) {
        FieldDevice fd = new FieldDevice();
        fd.id = new ObjectId(id);
        fd.name = "1 号冷水机组";
        fd.device = new DeviceOn();
        fd.device.space = spaceId == null ? null : new SpaceOn(space(spaceId));
        ModbusConfig config = new ModbusConfig();
        config.registries = new ArrayList<>(List.of(registers));
        fd.configSnapshot = config;
        return fd;
    }

    private static ModbusRegistry register(int index, String name, String access) {
        ModbusRegistry reg = new ModbusRegistry();
        reg.index = index;
        reg.name = name;
        reg.area = "holding";
        reg.unit = "℃";
        reg.access = access;
        reg.address = index - 1;
        return reg;
    }

    private static WebDashboardWidget device(String id, String did) {
        return widget(id, "device", config("did", did, "pid", "p1"));
    }

    /** 服务卡：只带服务 + 寄存器序号，不带方法序号。 */
    private static WebDashboardWidget serviceWithRegisters(String id, String fdid, Integer... registryIndexes) {
        return widget(id, "fd", config("fdid", fdid, "fields", List.of(registryIndexes)));
    }

    /** 曲线卡（fdField 数据源）：只带服务 + 寄存器序号。 */
    private static WebDashboardWidget lineServiceField(String id, String fdid, Integer registryIndex) {
        return widget(id, "line", config("source", "fdField", "fdid", fdid, "field", registryIndex));
    }

    private static WebDashboardWidget widget(String id, String type, Map<String, Object> config) {
        WebDashboardWidget w = new WebDashboardWidget();
        w.id = id;
        w.type = type;
        w.size = "W12H200";
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
}
