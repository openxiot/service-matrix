package cc.openxiot.site.api.device;

import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.device.DeviceRepository;
import cc.openxiot.site.db.device.DeviceSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
        service.save(device);
        assertNotNull(device.create);
        verify(deviceRepository).persistOrUpdate(any(DeviceEntity.class));
    }

    @Test
    void save_existingDate_preservesIt() {
        Date now = new Date();
        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        device.create = now;
        service.save(device);
        assertSame(now, device.create);
        verify(deviceRepository).persistOrUpdate(any(DeviceEntity.class));
    }

    @Test
    void updateSpace_found_updatesDeviceSpace() {
        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        when(deviceRepository.findByDeviceId("d1")).thenReturn(device);

        service.updateSpace("d1", "space-1", "root-1");

        assertNotNull(device.space);
        assertEquals("space-1", device.space.spaceId);
        assertEquals("root-1", device.space.rootId);
        verify(deviceRepository).persistOrUpdate(any(DeviceEntity.class));
    }

    @Test
    void updateSpace_notFound_doesNothing() {
        when(deviceRepository.findByDeviceId("d1")).thenReturn(null);
        service.updateSpace("d1", "space-1", "root-1");
        verify(deviceRepository, never()).persistOrUpdate(any(DeviceEntity.class));
    }

    @Test
    void getBySpaceId_delegates() {
        when(deviceRepository.findBySpaceId("sid")).thenReturn(List.of(new DeviceEntity()));
        assertEquals(1, service.getBySpaceId("sid").size());
    }

    @Test
    void getByDids_delegates() {
        when(deviceRepository.findByDeviceIds(List.of("d1"))).thenReturn(List.of(new DeviceEntity()));
        assertEquals(1, service.getByDids(List.of("d1")).size());
    }

    @Test
    void deleteByDids_delegates() {
        when(deviceRepository.deleteByDeviceIds(List.of("d1"))).thenReturn(1L);
        assertEquals(1L, service.deleteByDids(List.of("d1")));
    }
}
