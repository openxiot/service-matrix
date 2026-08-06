package cc.openxiot.matrix.db.device;

import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class DeviceRepository implements ReactivePanacheMongoRepository<DeviceEntity> {

    public Uni<List<DeviceEntity>> findBySpaceId(String spaceId) {
        return list("space.spaceId", spaceId);
    }

    public Uni<List<DeviceEntity>> findByRootId(String rootId) {
        return list("rootId", rootId);
    }

    public Uni<List<DeviceEntity>> findByDeviceIds(List<String> dids) {
        return list("_id in ?1", dids);
    }

    public Uni<DeviceEntity> findByDeviceId(String did) {
        return find("_id", did).firstResult();
    }

    public Uni<Long> deleteByDeviceIds(List<String> dids) {
        return delete("_id in ?1", dids);
    }
}
