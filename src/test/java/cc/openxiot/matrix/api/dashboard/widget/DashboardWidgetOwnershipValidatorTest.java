package cc.openxiot.matrix.api.dashboard.widget;

import cc.openxiot.matrix.db.modbus.service.poll.ModbusPollFrom;
import cc.openxiot.matrix.db.modbus.service.poll.ModbusPoll;
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
import cc.openxiot.matrix.db.modbus.service.DeviceOn;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.poll.response.field.ModbusResponseField;
import cc.openxiot.matrix.db.modbus.service.poll.response.field.ModbusResponseFieldBit;
import cc.openxiot.matrix.db.modbus.service.poll.response.ModbusResponses;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
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
    ModbusServiceRepository serviceRepo;

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
        when(serviceRepo.findById(org.mockito.ArgumentMatchers.anyString()))
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
        stubService(service(SERVICE_ID, ROOT_ID, poll(1, "温度", null)));

        assertTrue(errors(serviceWithField("w1", SERVICE_ID, 1, "温度")).isEmpty());
    }

    @Test
    void service_inChildSpace_belongs() {
        stubService(service(SERVICE_ID, CHILD_ID, poll(1, "温度", null)));

        assertTrue(errors(serviceWithField("w1", SERVICE_ID, 1, "温度")).isEmpty());
    }

    /**
     * 服务上的落点快照写的是别的空间（搬迁后没更新），但活值 {@code space.spaceId} 在本子树里
     * —— 判定走活值，所以通过。旧写法比的是那个快照，会把这张卡误判成不属于本空间。
     */
    @Test
    void service_staleRootIdSnapshotButLiveSpaceIdIsInSubtree_belongs() {
        ModbusService stale = service(SERVICE_ID, ROOT_ID, poll(1, "温度", null));
        stale.device.space.rootId = OTHER_ID;
        stubService(stale);

        assertTrue(errors(serviceWithField("w1", SERVICE_ID, 1, "温度")).isEmpty());
    }

    @Test
    void service_inAnotherSpace_doesNotBelong() {
        stubService(service(SERVICE_ID, OTHER_ID, poll(1, "温度", null)));

        assertEquals("widgets[0](w1): service does not belong to this space: " + SERVICE_ID,
                errors(serviceWithField("w1", SERVICE_ID, 1, "温度")).get("w1"));
    }

    @Test
    void service_withoutSpace_doesNotBelongAndDoesNotThrow() {
        ModbusService orphan = service(SERVICE_ID, ROOT_ID, poll(1, "温度", null));
        orphan.device.space = null;
        stubService(orphan);

        assertEquals("widgets[0](w1): service does not belong to this space: " + SERVICE_ID,
                errors(serviceWithField("w1", SERVICE_ID, 1, "温度")).get("w1"));
    }

    @Test
    void service_notFound() {
        assertEquals("widgets[0](w1): service not found: " + SERVICE_ID,
                errors(serviceWithField("w1", SERVICE_ID, 1, "温度")).get("w1"));
    }

    @Test
    void service_functionNotFound() {
        stubService(service(SERVICE_ID, ROOT_ID, poll(1, "温度", null)));

        assertEquals("widgets[0](w1): function not found: 9",
                errors(serviceWithField("w1", SERVICE_ID, 9, "温度")).get("w1"));
    }

    @Test
    void service_fieldNotFound() {
        stubService(service(SERVICE_ID, ROOT_ID, poll(1, "温度", null)));

        assertEquals("widgets[0](w1): field not found: 湿度",
                errors(serviceWithField("w1", SERVICE_ID, 1, "湿度")).get("w1"));
    }

    /** 位是独立点位：名字写在 {@code response.fields[].bitList[].field} 上，也得认。 */
    @Test
    void service_bitField_belongs() {
        stubService(service(SERVICE_ID, ROOT_ID, poll(1, "状态字", List.of("进水阀", "出水阀"))));

        assertTrue(errors(serviceWithField("w1", SERVICE_ID, 1, "进水阀")).isEmpty(),
                "位名应当被认作合法字段（见 ModbusPollResponseFieldBit）");
    }

    // ===== 新卡：不存方法序号，字段（寄存器）按名解析到某个读方法 =====

    /** 服务卡不再带 pollIndex：字段能解析到某个读方法即合法（方法由取数时解析）。 */
    @Test
    void service_withoutPollIndex_registerField_belongs() {
        stubService(service(SERVICE_ID, ROOT_ID, poll(1, "温度", null), poll(2, "压力", null)));

        assertTrue(errors(serviceWithRegisters("w1", SERVICE_ID, "温度", "压力")).isEmpty(),
                "寄存器字段卡不应再要求方法序号（pollIndex）");
    }

    /** 位名走新路径同样认。 */
    @Test
    void service_withoutPollIndex_bitField_belongs() {
        stubService(service(SERVICE_ID, ROOT_ID, poll(1, "状态字", List.of("进水阀"))));

        assertTrue(errors(serviceWithRegisters("w1", SERVICE_ID, "进水阀")).isEmpty());
    }

    /** 字段解析不到（写错 / 取不到数）时照旧报错，不让它存下去。 */
    @Test
    void service_withoutPollIndex_fieldNotFound() {
        stubService(service(SERVICE_ID, ROOT_ID, poll(1, "温度", null)));

        assertEquals("widgets[0](w1): field not found: 湿度",
                errors(serviceWithRegisters("w1", SERVICE_ID, "湿度")).get("w1"));
    }

    /** 曲线卡（serviceField 数据源）同样不再要求方法序号：字段能解析即合法。 */
    @Test
    void line_serviceField_withoutPollIndex_fieldResolves() {
        stubService(service(SERVICE_ID, ROOT_ID, poll(1, "温度", null)));

        assertTrue(errors(lineServiceField("w1", SERVICE_ID, "温度")).isEmpty());
    }

    /** 曲线卡的字段解析不到照旧报错。 */
    @Test
    void line_serviceField_withoutPollIndex_fieldNotFound() {
        stubService(service(SERVICE_ID, ROOT_ID, poll(1, "温度", null)));

        assertEquals("widgets[0](w1): field not found: 湿度",
                errors(lineServiceField("w1", SERVICE_ID, "湿度")).get("w1"));
    }

    // ===== 编排：什么时候查库、错误之间互不牵连 =====

    /** stat / distribution 只读本空间聚合，没有用户填的 id，一次库都不该查。 */
    @Test
    void statAndDistributionCards_doNotTouchTheDatabase() {
        assertTrue(errors(widget("w1", "stat", config("metric", "devices.total")),
                widget("w2", "distribution", config("dimension", "deviceType"))).isEmpty());

        verifyNoInteractions(spaceService, deviceRepo, serviceRepo);
    }

    @Test
    void noWidgets_doesNotTouchTheDatabase() {
        assertTrue(errors().isEmpty());
        verifyNoInteractions(spaceService, deviceRepo, serviceRepo);
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
                        widget("w2", "stat", config("metric", "services.total")),
                        device("w3", "did-1")).get("w3"));
    }

    // ===== helpers =====

    private Map<String, String> errors(WebDashboardWidget... widgets) {
        return validator.errorsByWidget(root(), List.of(widgets)).await().indefinitely();
    }

    private void stubDevices(DeviceEntity... devices) {
        when(deviceRepo.findByDeviceIds(anyList())).thenReturn(Uni.createFrom().item(List.of(devices)));
    }

    private void stubService(ModbusService service) {
        when(serviceRepo.findById(SERVICE_ID)).thenReturn(Uni.createFrom().item(service));
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

    private static ModbusService service(String id, String spaceId, ModbusPoll... polls) {
        ModbusService s = new ModbusService();
        s.id = new ObjectId(id);
        s.name = "1 号冷水机组";
        s.device = new DeviceOn();
        s.device.space = spaceId == null ? null : new SpaceOn(space(spaceId));
        s.polls = List.of(polls);
        return s;
    }

    private static ModbusPoll poll(int index, String field, List<String> bits) {
        ModbusResponseField f = new ModbusResponseField();
        f.field = field;
        f.unit = "℃";
        if (bits != null) {
            List<ModbusResponseFieldBit> bitList = new ArrayList<>();
            int offset = 0;
            for (String bit : bits) {
                ModbusResponseFieldBit b = new ModbusResponseFieldBit();
                b.offset = offset++;
                b.field = bit;
                bitList.add(b);
            }
            f.bitList = bitList;
        }
        ModbusPoll fn = new ModbusPoll();
                    fn.from = new ModbusPollFrom();
                    fn.from.type = "command";
                    fn.from.index = index;
                    fn.index = index;
        fn.name = field;
        fn.response = ModbusResponses.of(f);
        return fn;
    }

    private static WebDashboardWidget device(String id, String did) {
        return widget(id, "device", config("did", did, "pid", "p1"));
    }

    private static WebDashboardWidget serviceWithField(String id, String serviceId, int pollIndex, String field) {
        return widget(id, "service", config("serviceId", serviceId,
                "pollIndex", pollIndex, "fields", List.of(field)));
    }

    /** 新卡服务卡：只带服务 + 寄存器字段，不带方法序号。 */
    private static WebDashboardWidget serviceWithRegisters(String id, String serviceId, String... fields) {
        return widget(id, "service", config("serviceId", serviceId, "fields", List.of(fields)));
    }

    /** 新卡曲线卡（serviceField 数据源）：只带服务 + 寄存器字段，不带方法序号。 */
    private static WebDashboardWidget lineServiceField(String id, String serviceId, String field) {
        return widget(id, "line", config("source", "serviceField", "serviceId", serviceId, "field", field));
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
