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

    @Test
    void getByDeviceId_delegates() {
        DeviceEntity d = new DeviceEntity();
        d.did = "d1";
        when(deviceRepository.findByDeviceId("d1")).thenReturn(Uni.createFrom().item(d));

        DeviceEntity result = service.getByDeviceId("d1").await().indefinitely();

        assertSame(d, result);
    }

    @Test
    void deleteByDeviceIds_delegates() {
        when(deviceRepository.deleteByDeviceIds(List.of("d1")))
                .thenReturn(Uni.createFrom().item(1L));

        Long result = service.deleteByDeviceIds(List.of("d1")).await().indefinitely();

        assertEquals(1L, result);
    }
}
