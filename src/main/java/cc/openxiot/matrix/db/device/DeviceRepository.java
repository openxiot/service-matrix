package cc.openxiot.matrix.db.device;

import cc.openxiot.matrix.db.ModelAnchor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
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

    /**
     * 原子写入 3D 锚点（$set）。设备的 {@code _id} 就是 did，不是 ObjectId。
     *
     * <p>用更新操作符而不是「查出来赋值再存」：设备实体上还有 {@code space} 等字段，
     * 整份写回会拿陈旧副本把它们盖掉。</p>
     */
    public Uni<Long> setAnchor(String did, ModelAnchor anchor) {
        return mongoCollection()
                .updateOne(Filters.eq("_id", did), Updates.set("anchor", anchor.toDocument()))
                .map(UpdateResult::getModifiedCount);
    }

    /** 原子清除 3D 锚点（$unset）。字段整个移除，不是置 null。 */
    public Uni<Long> clearAnchor(String did) {
        return mongoCollection()
                .updateOne(Filters.eq("_id", did), Updates.unset("anchor"))
                .map(UpdateResult::getModifiedCount);
    }
}
