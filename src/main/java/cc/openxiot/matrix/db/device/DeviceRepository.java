package cc.openxiot.matrix.db.device;

import cc.openxiot.matrix.typedef.model.ModelAnchor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class DeviceRepository {

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<DeviceEntity> collection() {
        return mongoClient.getDatabase("matrix").getCollection("devices", DeviceEntity.class);
    }

    public Uni<List<DeviceEntity>> findBySpaceId(String spaceId) {
        return collection().find(Filters.eq("space.spaceId", spaceId)).collect().asList();
    }

    /**
     * 列出落点在这些空间的设备（一次 {@code $in}）。
     *
     * <p>给「整个子树」的清单用（看板编辑器的候选设备）：子树的空间 id 是**现算**出来的
     * （{@code SpaceService.getDescendants}），逐个空间各查一次会把一次清单变成 N 次往返。
     * 与 {@link #findBySpaceId} 同一个字段，所以口径与归属校验、取数三处一致。</p>
     *
     * <p>空清单**短路**：{@code $in []} 一条也匹配不到，但没必要为此打一次库
     * （同 {@code FieldDeviceRepository.deleteByDeviceDids}）。</p>
     */
    public Uni<List<DeviceEntity>> findBySpaceIds(List<String> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return collection().find(Filters.in("space.spaceId", spaceIds)).collect().asList();
    }

    public Uni<List<DeviceEntity>> findByRootId(String rootId) {
        return collection().find(Filters.eq("rootId", rootId)).collect().asList();
    }

    public Uni<List<DeviceEntity>> findByDeviceIds(List<String> dids) {
        return collection().find(Filters.in("_id", dids)).collect().asList();
    }

    public Uni<DeviceEntity> findByDeviceId(String did) {
        return collection().find(Filters.eq("_id", did)).collect().first();
    }

    /** 设备 upsert（{@code _id} 即 did，不存在则插入，存在则全量替换）。 */
    public Uni<DeviceEntity> persistOrUpdate(DeviceEntity device) {
        return collection()
                .replaceOne(Filters.eq("_id", device.did), device, new ReplaceOptions().upsert(true))
                .map(v -> device);
    }

    public Uni<Long> deleteByDeviceIds(List<String> dids) {
        return collection().deleteMany(Filters.in("_id", dids))
                .map(DeleteResult::getDeletedCount);
    }

    /**
     * 原子写入 3D 锚点（$set）。设备的 {@code _id} 就是 did，不是 ObjectId。
     *
     * <p>用更新操作符而不是「查出来赋值再存」：设备实体上还有 {@code space} 等字段，
     * 整份写回会拿陈旧副本把它们盖掉。</p>
     */
    public Uni<Long> setAnchor(String did, ModelAnchor anchor) {
        return collection()
                .updateOne(Filters.eq("_id", did), Updates.set("anchor", anchor.toDocument()))
                .map(UpdateResult::getModifiedCount);
    }

    /** 原子清除 3D 锚点（$unset）。字段整个移除，不是置 null。 */
    public Uni<Long> clearAnchor(String did) {
        return collection()
                .updateOne(Filters.eq("_id", did), Updates.unset("anchor"))
                .map(UpdateResult::getModifiedCount);
    }
}