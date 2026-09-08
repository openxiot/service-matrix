package cc.openxiot.matrix.api.modbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.ModbusDeviceConfig;
import cc.openxiot.matrix.db.modbus.ModbusDeviceConfigRepository;
import cc.openxiot.matrix.db.modbus.ModbusPoint;
import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
class ModbusDeviceConfigServiceTest {

    @Mock
    ModbusDeviceConfigRepository repo;

    @InjectMocks
    ModbusDeviceConfigService service;

    private ModbusDeviceConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new ModbusDeviceConfig();
        cfg.orgId = "org1";
        cfg.manufacturer = "开利";
        cfg.model = "19XRV";
        cfg.slaveId = 1;
        cfg.visibility = "private";
    }

    @Test
    void createSetsIdAndInserts() {
        cfg.creator = Person.of("acc1", "小明");
        cfg.updater = Person.of("acc1", "小明");
        when(repo.insert(any(ModbusDeviceConfig.class))).thenReturn(Uni.createFrom().voidItem());

        ModbusDeviceConfig created = service.create(cfg).await().indefinitely();

        assertNotNull(created.id);
        // 创建者/更新者由上层注入，服务层仅保证生成 id 并落库
        assertEquals("acc1", created.creator.id);
        assertNotNull(created.creator.timestamp);
        assertEquals("小明", created.updater.name);
        verify(repo).insert(eq(cfg));
    }

    @Test
    void createWithoutPointsAllowed() {
        cfg.points = null;
        when(repo.insert(any(ModbusDeviceConfig.class))).thenReturn(Uni.createFrom().voidItem());
        assertNotNull(service.create(cfg).await().indefinitely());
    }

    @Test
    void createWithBlankOrgFails() {
        cfg.orgId = " ";
        assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
    }

    @Test
    void createWithBlankManufacturerFails() {
        cfg.manufacturer = "";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("manufacturer is required", ex.getMessage());
    }

    @Test
    void createWithBlankModelFails() {
        cfg.model = " ";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("model is required", ex.getMessage());
    }

    @Test
    void createWithBlankSlaveIdFails() {
        cfg.slaveId = null;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("slaveId is required", ex.getMessage());
    }

    @Test
    void createWithInvalidSlaveIdFails() {
        cfg.slaveId = 300;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid slaveId: 300", ex.getMessage());
    }

    @Test
    void createWithBlankVisibilityFails() {
        cfg.visibility = "";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("visibility is required", ex.getMessage());
    }

    @Test
    void createWithInvalidVisibilityFails() {
        cfg.visibility = "secret";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid visibility: secret", ex.getMessage());
    }

    @Test
    void createWithInvalidPointAreaFails() {
        ModbusPoint p = new ModbusPoint();
        p.name = "CHW_TEMP";
        p.dataType = "uint16";
        p.area = "weird";
        cfg.points = List.of(p);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid points[0].area: weird", ex.getMessage());
    }

    @Test
    void createWithAllSupportedAreasAllowed() {
        ModbusPoint p = new ModbusPoint();
        p.name = "TAG";
        p.dataType = "uint16";
        p.area = "discrete_input"; // 与前三大区并列的新增区域，须可保存
        cfg.points = List.of(p);
        when(repo.insert(any(ModbusDeviceConfig.class))).thenReturn(Uni.createFrom().voidItem());
        assertNotNull(service.create(cfg).await().indefinitely());
    }

    @Test
    void createWithPointNameMissingFails() {
        ModbusPoint p = new ModbusPoint();
        p.dataType = "uint16";
        cfg.points = List.of(p);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("points[0].name is required", ex.getMessage());
    }

    @Test
    void updateNotFoundFails() {
        when(repo.findByIdAndOrg("nope", "org1")).thenReturn(Uni.createFrom().nullItem());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update("org1", "nope", cfg).await().indefinitely());
        assertEquals("modbus device config not found: nope", ex.getMessage());
    }

    @Test
    void updateMergesEditableFields() {
        ModbusDeviceConfig existing = new ModbusDeviceConfig();
        existing.id = "c1";
        existing.orgId = "org1";
        existing.manufacturer = "old";
        existing.model = "old-model";
        existing.slaveId = 5;
        existing.visibility = "public";
        existing.creator = Person.of("acc1", "小明");
        existing.updater = Person.of("acc1", "小明");
        when(repo.findByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusDeviceConfig.class))).thenReturn(Uni.createFrom().voidItem());

        cfg.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        ModbusDeviceConfig updated = service.update("org1", "c1", cfg).await().indefinitely();

        assertEquals("new", updated.manufacturer);
        assertEquals("19XRV", updated.model);
        assertEquals(1, updated.slaveId);
        assertEquals("private", updated.visibility);
        assertEquals("c1", updated.id);
        // 更新只替换 updater，creator 保持不变
        assertEquals("acc1", updated.creator.id);
        assertEquals("acc2", updated.updater.id);
        assertNotNull(updated.updater.timestamp);
        verify(repo).replace(eq(existing));
    }

    @Test
    void updateWithBlankModelFails() {
        ModbusDeviceConfig existing = new ModbusDeviceConfig();
        existing.id = "c1";
        existing.orgId = "org1";
        when(repo.findByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(existing));

        cfg.model = "";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update("org1", "c1", cfg).await().indefinitely());
        assertEquals("model is required", ex.getMessage());
    }

    @Test
    void getNotFoundFails() {
        when(repo.findByIdAndOrg("ghost", "org1")).thenReturn(Uni.createFrom().nullItem());
        assertThrows(IllegalArgumentException.class,
                () -> service.get("org1", "ghost").await().indefinitely());
    }

    @Test
    void listByOrgDelegates() {
        when(repo.listByOrg("org1")).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listByOrg("org1").await().indefinitely().size());
    }

    @Test
    void listByOrgBlankFails() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listByOrg(" ").await().indefinitely());
    }

    @Test
    void listPublicDelegates() {
        when(repo.listPublic()).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listPublic().await().indefinitely().size());
    }

    @Test
    void listVisibleDelegates() {
        when(repo.listVisible("org1")).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listVisible("org1").await().indefinitely().size());
    }

    @Test
    void listVisibleBlankOrgFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.listVisible(" ").await().indefinitely());
        assertEquals("orgId is required", ex.getMessage());
    }

    @Test
    void deleteZeroFailsAsNotFound() {
        when(repo.deleteByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(0L));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.delete("org1", "c1").await().indefinitely());
        assertEquals("modbus device config not found: c1", ex.getMessage());
    }

    @Test
    void deleteDelegates() {
        when(repo.deleteByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(1L));
        assertEquals(1L, service.delete("org1", "c1").await().indefinitely());
    }
}
