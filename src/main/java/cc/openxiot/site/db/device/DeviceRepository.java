package cc.openxiot.site.db.device;

import io.quarkus.mongodb.panache.PanacheMongoRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class DeviceRepository implements PanacheMongoRepository<DeviceEntity> {

    public List<DeviceEntity> findByType(String type) {
        return list("type", type);
    }

    public List<DeviceEntity> findBySpaceId(String spaceId) {
        return list("spaceId", spaceId);
    }

    public List<DeviceEntity> findByOnline(boolean online) {
        return list("online", online);
    }

    public List<DeviceEntity> findByRootId(String rootId) {
        return list("rootId", rootId);
    }

    public List<DeviceEntity> findByParentId(String parentId) {
        return list("parentId", parentId);
    }
}
