package cc.openxiot.site.api.device;

import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.device.DeviceRepository;
import cc.openxiot.site.db.device.DeviceSpace;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Date;
import java.util.List;

@ApplicationScoped
public class DeviceService {

    @Inject
    DeviceRepository deviceRepository;

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

    public Uni<Void> updateSpace(String did, String spaceId, String rootSpaceId) {
        return deviceRepository.findByDeviceId(did)
                .chain(device -> {
                    if (device != null) {
                        DeviceSpace space = new DeviceSpace();
                        space.spaceId = spaceId;
                        space.rootId = rootSpaceId;
                        device.space = space;
                        return deviceRepository.persistOrUpdate(device).replaceWithVoid();
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    public Uni<Long> deleteByDeviceIds(List<String> dids) {
        return deviceRepository.deleteByDeviceIds(dids);
    }
}
