package cc.openxiot.matrix.api.device;

import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceRepository;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock
    DeviceRepository deviceRepository;

    @InjectMocks
    DeviceService service;

    @Test
    void save_setsCreateDate() {
        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        when(deviceRepository.persistOrUpdate(any(DeviceEntity.class)))
                .thenReturn(Uni.createFrom().item(device));

        DeviceEntity result = service.save(device).await().indefinitely();

        assertNotNull(device.create);
        assertSame(device, result);
    }

    @Test
    void save_existingDate_preservesIt() {
        Date now = new Date();
        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        device.create = now;
        when(deviceRepository.persistOrUpdate(any(DeviceEntity.class)))
                .thenReturn(Uni.createFrom().item(device));

        DeviceEntity result = service.save(device).await().indefinitely();

        assertSame(now, device.create);
        assertSame(device, result);
    }

    @Test
    void updateSpace_found_updatesDeviceSpace() {
        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        when(deviceRepository.findByDeviceId("d1")).thenReturn(Uni.createFrom().item(device));
        when(deviceRepository.persistOrUpdate(any(DeviceEntity.class)))
                .thenReturn(Uni.createFrom().item(device));

        service.updateSpace("d1", "space-1", "root-1").await().indefinitely();

        assertNotNull(device.space);
        assertEquals("space-1", device.space.spaceId);
        assertEquals("root-1", device.space.rootId);
        verify(deviceRepository).persistOrUpdate(any(DeviceEntity.class));
    }

    @Test
    void updateSpace_notFound_doesNothing() {
        when(deviceRepository.findByDeviceId("d1")).thenReturn(Uni.createFrom().nullItem());

        service.updateSpace("d1", "space-1", "root-1").await().indefinitely();

        verify(deviceRepository, never()).persistOrUpdate(any(DeviceEntity.class));
    }

    @Test
    void getBySpaceId_delegates() {
        when(deviceRepository.findBySpaceId("sid"))
                .thenReturn(Uni.createFrom().item(List.of(new DeviceEntity())));

        List<DeviceEntity> result = service.getBySpaceId("sid").await().indefinitely();

        assertEquals(1, result.size());
    }

    @Test
    void getByDeviceIds_delegates() {
        when(deviceRepository.findByDeviceIds(List.of("d1")))
                .thenReturn(Uni.createFrom().item(List.of(new DeviceEntity())));

        List<DeviceEntity> result = service.getByDeviceIds(List.of("d1")).await().indefinitely();

        assertEquals(1, result.size());
    }

    // ===== Modbus 虚拟子设备：online 与父设备保持一致 =====

    private static DeviceEntity virtualChild(String did, String parentId) {
        DeviceEntity child = new DeviceEntity();
        child.did = did;
        child.protocol = "modbus";
        child.parentId = parentId;
        child.online = false; // 存储位离线起步，读取时应被父设备 online 覆盖
        return child;
    }

    private static DeviceEntity parent(String did, boolean online) {
        DeviceEntity p = new DeviceEntity();
        p.did = did;
        p.protocol = "other";
        p.online = online;
        return p;
    }

    @Test
    void getBySpaceId_mirrorsVirtualChildOnlineFromParent() {
        DeviceEntity child = virtualChild("virt-1", "p1");
        DeviceEntity p = parent("p1", true);
        when(deviceRepository.findBySpaceId("sid"))
                .thenReturn(Uni.createFrom().item(List.of(child, p)));

        List<DeviceEntity> result = service.getBySpaceId("sid").await().indefinitely();

        assertTrue(result.get(0).online, "virtual child online should mirror its parent");
    }

    @Test
    void getBySpaceId_virtualChildKeepsOfflineWhenParentOffline() {
        DeviceEntity child = virtualChild("virt-1", "p1");
        DeviceEntity p = parent("p1", false);
        when(deviceRepository.findBySpaceId("sid"))
                .thenReturn(Uni.createFrom().item(List.of(child, p)));

        List<DeviceEntity> result = service.getBySpaceId("sid").await().indefinitely();

        assertFalse(result.get(0).online);
    }

    @Test
    void getBySpaceId_nonVirtualDevicesUntouched() {
        DeviceEntity a = parent("a", true);
        when(deviceRepository.findBySpaceId("sid"))
                .thenReturn(Uni.createFrom().item(List.of(a)));

        List<DeviceEntity> result = service.getBySpaceId("sid").await().indefinitely();

        assertTrue(result.get(0).online);
        verify(deviceRepository, never()).findByDeviceIds(any());
    }

    @Test
    void getByDeviceId_singleVirtualChildFetchesParentOnline() {
        DeviceEntity child = virtualChild("virt-1", "p1");
        when(deviceRepository.findByDeviceId("virt-1")).thenReturn(Uni.createFrom().item(child));
        when(deviceRepository.findByDeviceId("p1")).thenReturn(Uni.createFrom().item(parent("p1", true)));

        DeviceEntity result = service.getByDeviceId("virt-1").await().indefinitely();

        assertTrue(result.online, "virtual child online should mirror its parent");
    }

    @Test
    void getByDeviceId_virtualChildParentMissingKeepsStoredOnline() {
        DeviceEntity child = virtualChild("virt-1", "p1");
        when(deviceRepository.findByDeviceId("virt-1")).thenReturn(Uni.createFrom().item(child));
        when(deviceRepository.findByDeviceId("p1")).thenReturn(Uni.createFrom().nullItem());

        DeviceEntity result = service.getByDeviceId("virt-1").await().indefinitely();

        assertFalse(result.online);
    }

    @Test
    void getByDeviceId_nonVirtualPassthrough() {
        DeviceEntity d = parent("p1", true);
        when(deviceRepository.findByDeviceId("p1")).thenReturn(Uni.createFrom().item(d));

        DeviceEntity result = service.getByDeviceId("p1").await().indefinitely();

        assertSame(d, result);
        verify(deviceRepository, never()).findByDeviceId("virt-none");
    }

    @Test
    void deleteByDeviceIds_delegates() {
        when(deviceRepository.deleteByDeviceIds(List.of("d1")))
                .thenReturn(Uni.createFrom().item(1L));

        Long result = service.deleteByDeviceIds(List.of("d1")).await().indefinitely();

        assertEquals(1L, result);
    }
}
