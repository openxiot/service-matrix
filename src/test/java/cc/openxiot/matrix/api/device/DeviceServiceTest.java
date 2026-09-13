package cc.openxiot.matrix.api.device;

import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceRepository;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.SpaceOn;
import cc.openxiot.matrix.db.space.SpaceRepository;
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

    @Mock
    SpaceRepository spaceRepository;

    @InjectMocks
    DeviceService service;

    /** 空间 _id 是 ObjectId（SpaceRepository.findById 会 new ObjectId(id)），所以用合法值而不是 "root-1"。 */
    private static final String ROOT_1 = "6512f3a4b5c6d7e8f9a0b101";
    private static final String ROOT_2 = "6512f3a4b5c6d7e8f9a0b102";
    private static final String LEVEL_1 = "6512f3a4b5c6d7e8f9a0b103";
    private static final String LEVEL_2 = "6512f3a4b5c6d7e8f9a0b104";

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

    /** 从未分配过落点的设备：没有「原根空间」可比，直接落位，不必查空间。 */
    @Test
    void updateSpace_unassignedDevice_updatesDeviceSpace() {
        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        when(deviceRepository.findByDeviceId("d1")).thenReturn(Uni.createFrom().item(device));
        when(deviceRepository.persistOrUpdate(any(DeviceEntity.class)))
                .thenReturn(Uni.createFrom().item(device));

        service.updateSpace("d1", LEVEL_1, ROOT_1).await().indefinitely();

        assertNotNull(device.space);
        assertEquals(LEVEL_1, device.space.spaceId);
        assertEquals(ROOT_1, device.space.rootId);
        verify(deviceRepository).persistOrUpdate(any(DeviceEntity.class));
    }

    /** 同一根空间内部换落点（子空间之间搬）：根一致，放行。 */
    @Test
    void updateSpace_sameRoot_movesWithinRoot() {
        DeviceEntity device = deviceIn(LEVEL_1);
        when(deviceRepository.findByDeviceId("d1")).thenReturn(Uni.createFrom().item(device));
        when(spaceRepository.findById(LEVEL_1))
                .thenReturn(Uni.createFrom().item(spaceUnder(ROOT_1)));
        when(deviceRepository.persistOrUpdate(any(DeviceEntity.class)))
                .thenReturn(Uni.createFrom().item(device));

        service.updateSpace("d1", LEVEL_2, ROOT_1).await().indefinitely();

        assertEquals(LEVEL_2, device.space.spaceId);
        assertEquals(ROOT_1, device.space.rootId);
        verify(deviceRepository).persistOrUpdate(any(DeviceEntity.class));
    }

    /** 跨根空间搬：归属记录挂在老根空间上，搬过去只会天天报 "device not owned"，直接拒。 */
    @Test
    void updateSpace_acrossRoot_rejects() {
        DeviceEntity device = deviceIn(LEVEL_1);
        when(deviceRepository.findByDeviceId("d1")).thenReturn(Uni.createFrom().item(device));
        when(spaceRepository.findById(LEVEL_1))
                .thenReturn(Uni.createFrom().item(spaceUnder(ROOT_1)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.updateSpace("d1", LEVEL_2, ROOT_2).await().indefinitely());

        assertTrue(e.getMessage().contains("d1"));
        verify(deviceRepository, never()).persistOrUpdate(any(DeviceEntity.class));
    }

    /** 设备已落在目标根空间自身上：根就是它自己，不必查库。 */
    @Test
    void updateSpace_alreadyInTargetRoot_skipsLookup() {
        DeviceEntity device = deviceIn(ROOT_1);
        when(deviceRepository.findByDeviceId("d1")).thenReturn(Uni.createFrom().item(device));
        when(deviceRepository.persistOrUpdate(any(DeviceEntity.class)))
                .thenReturn(Uni.createFrom().item(device));

        service.updateSpace("d1", LEVEL_2, ROOT_1).await().indefinitely();

        assertEquals(LEVEL_2, device.space.spaceId);
        verify(spaceRepository, never()).findById(anyString());
        verify(deviceRepository).persistOrUpdate(any(DeviceEntity.class));
    }

    @Test
    void updateSpace_notFound_doesNothing() {
        when(deviceRepository.findByDeviceId("d1")).thenReturn(Uni.createFrom().nullItem());

        service.updateSpace("d1", LEVEL_1, ROOT_1).await().indefinitely();

        verify(deviceRepository, never()).persistOrUpdate(any(DeviceEntity.class));
    }

    /** 造一个已经落在某个空间里的设备。 */
    private static DeviceEntity deviceIn(String spaceId) {
        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        device.space = new SpaceOn();
        device.space.spaceId = spaceId;
        return device;
    }

    /** 造一个直接挂在根空间下的子空间：ancestors 从根往下排，第一个就是根（同 SpaceService.create）。 */
    private static SpaceEntity spaceUnder(String rootId) {
        SpaceEntity space = new SpaceEntity();
        space.parentId = rootId;
        space.ancestors = List.of(rootId);
        return space;
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
