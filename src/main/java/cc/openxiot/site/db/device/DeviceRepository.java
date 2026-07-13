package cc.openxiot.site.db.device;

import io.quarkus.mongodb.panache.PanacheMongoRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class DeviceRepository implements PanacheMongoRepository<DeviceEntity> {

    public List<DeviceEntity> findBySpaceId(String spaceId) {
        return list("space.spaceId", spaceId);
    }

    public List<DeviceEntity> findByRootId(String rootId) {
        return list("rootId", rootId);
    }

    public List<DeviceEntity> findByDeviceIds(List<String> dids) {
        return list("_id in ?1", dids);
    }

    public DeviceEntity findByDeviceId(String did) {
        return find("_id", did).firstResult();
    }

    public long deleteByDeviceIds(List<String> dids) {
        return delete("_id in ?1", dids);
    }
}
