package cc.openxiot.matrix.api.device;

import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceRepository;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.SpaceOn;
import cc.openxiot.matrix.db.space.SpaceRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Date;
import java.util.List;

@ApplicationScoped
public class DeviceService {

    @Inject
    DeviceRepository deviceRepository;

    @Inject
    SpaceRepository spaceRepository;

    public Uni<List<DeviceEntity>> getBySpaceId(String spaceId) {
        return deviceRepository.findBySpaceId(spaceId);
    }

    public Uni<List<DeviceEntity>> getByDeviceIds(List<String> dids) {
        return deviceRepository.findByDeviceIds(dids);
    }

    public Uni<DeviceEntity> getByDeviceId(String did) {
        return deviceRepository.findByDeviceId(did);
    }

    public Uni<DeviceEntity> save(DeviceEntity device) {
        if (device.create == null) {
            device.create = new Date();
        }
        return deviceRepository.persistOrUpdate(device);
    }

    public Uni<List<DeviceEntity>> saveAll(List<DeviceEntity> devices) {
        List<Uni<DeviceEntity>> unis = devices.stream()
                .map(this::save)
                .toList();
        return Uni.join().all(unis).andFailFast();
    }

    /**
     * 改写设备落在哪个空间。
     *
     * <p><b>不允许跨根空间搬</b>：设备在 manipulation 的归属记录是 {@code (did, appId, ownerId)}，
     * 而 {@code ownerId} 就是**根空间 id**（见 {@link cc.openxiot.matrix.api.device.manipulation.ManipulationService}）。
     * 这里改的只是 matrix 这边的落点，改不了那条归属记录 —— 跨根空间搬过去，设备在新项目下
     * 每次调用都会报 "device not owned"，看起来像设备全坏了。要跨就先删掉再添加。</p>
     *
     * <p>设备还没有落点（{@code device.space} 为空）时不拦：那是从没分配过的设备，没有「原根空间」可言。</p>
     */
    public Uni<Void> updateSpace(String did, String spaceId, String rootSpaceId) {
        return deviceRepository.findByDeviceId(did)
                .chain(device -> {
                    if (device == null) {
                        return Uni.createFrom().voidItem();
                    }
                    return checkSameRoot(device, rootSpaceId)
                            .chain(ignored -> {
                                SpaceOn space = new SpaceOn();
                                space.spaceId = spaceId;
                                space.rootId = rootSpaceId;
                                device.space = space;
                                return deviceRepository.persistOrUpdate(device).replaceWithVoid();
                            });
                });
    }

    /**
     * 设备当前所在空间的根必须与目标根一致。
     *
     * <p>用当前空间**现算**根（{@link SpaceEntity#rootSpaceId()}），不看设备上存的 {@code space.rootId} ——
     * 那是迁移时由客户端写进去的快照，可能为空（改动之前的设备全是空的）或过期。</p>
     */
    private Uni<Void> checkSameRoot(DeviceEntity device, String targetRootId) {
        String currentSpaceId = device.space == null ? null : device.space.spaceId;
        // 设备正落在目标根空间自己身上：根就是它自己，不必再查
        if (currentSpaceId == null || currentSpaceId.equals(targetRootId)) {
            return Uni.createFrom().voidItem();
        }

        return spaceRepository.findById(currentSpaceId)
                .map(current -> {
                    if (current != null && !targetRootId.equals(current.rootSpaceId())) {
                        throw new IllegalArgumentException(
                                "cannot move device across root spaces, remove and add it again: " + device.did);
                    }
                    return null;
                })
                .replaceWithVoid();
    }

    public Uni<Long> deleteByDeviceIds(List<String> dids) {
        return deviceRepository.deleteByDeviceIds(dids);
    }
}
