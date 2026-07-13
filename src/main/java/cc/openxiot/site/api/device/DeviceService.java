package cc.openxiot.site.api.device;

import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.device.DeviceRepository;
import cc.openxiot.site.db.device.DeviceSpace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Date;
import java.util.List;

@ApplicationScoped
public class DeviceService {

    @Inject
    DeviceRepository deviceRepository;

    public List<DeviceEntity> getBySpaceId(String spaceId) {
        return deviceRepository.findBySpaceId(spaceId);
    }

    public List<DeviceEntity> getByDeviceIds(List<String> dids) {
        return deviceRepository.findByDeviceIds(dids);
    }

    public void save(DeviceEntity device) {
        if (device.create == null) {
            device.create = new Date();
        }
        deviceRepository.persistOrUpdate(device);
    }

    public void saveAll(List<DeviceEntity> devices) {
        for (DeviceEntity device : devices) {
            save(device);
        }
    }

    public void updateSpace(String did, String spaceId, String rootSpaceId) {
        DeviceEntity device = deviceRepository.findByDeviceId(did);
        if (device != null) {
            DeviceSpace space = new DeviceSpace();
            space.spaceId = spaceId;
            space.rootId = rootSpaceId;
            device.space = space;
            deviceRepository.persistOrUpdate(device);
        }
    }

    public long deleteByDeviceIds(List<String> dids) {
        return deviceRepository.deleteByDeviceIds(dids);
    }
}
