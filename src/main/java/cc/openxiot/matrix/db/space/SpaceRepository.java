package cc.openxiot.matrix.db.space;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class SpaceRepository implements ReactivePanacheMongoRepository<SpaceEntity> {

    public Uni<SpaceEntity> findById(String id) {
        return findById(new ObjectId(id));
    }

    public Uni<Boolean> deleteById(String id) {
        return deleteById(new ObjectId(id));
    }

    public Uni<List<SpaceEntity>> findByParentId(String parentId) {
        return list("parentId", parentId);
    }

    public Uni<List<SpaceEntity>> findByAncestor(String ancestorId) {
        return list("ancestors", ancestorId);
    }

    /**
     * 根空间列表：owners 数组内「同一元素」满足 id + type（$elemMatch，避免数组点号跨元素误匹配）。
     * type = organization → 组织根空间；type = user → 用户作为管理员或成员参与的根空间。
     */
    public Uni<List<SpaceEntity>> findRootsByOwner(String ownerId, String ownerType) {
        Document filter = new Document("parentId", null)
                .append("owners", new Document("$elemMatch",
                        new Document("id", ownerId).append("type", ownerType)));
        return mongoCollection().find(filter).collect().asList();
    }

    /** 原子追加成员条目（$push） */
    public Uni<Long> pushOwner(String rootId, SpaceOwner owner) {
        Document ownerDoc = new Document("id", owner.id)
                .append("type", owner.type)
                .append("role", owner.role)
                .append("name", owner.name)
                .append("remark", owner.remark);
        return mongoCollection()
                .updateOne(Filters.eq("_id", new ObjectId(rootId)), Updates.push("owners", ownerDoc))
                .map(UpdateResult::getModifiedCount);
    }

    /** 原子移除用户成员条目（$pull，仅匹配 type = user 的条目） */
    public Uni<Long> pullOwner(String rootId, String memberId) {
        return mongoCollection()
                .updateOne(Filters.eq("_id", new ObjectId(rootId)),
                        Updates.pull("owners",
                                Filters.and(
                                        Filters.eq("type", SpaceOwnerType.USER),
                                        Filters.eq("id", memberId))))
                .map(UpdateResult::getModifiedCount);
    }

    public Uni<SpaceEntity> persistEntity(SpaceEntity space) {
        if (space.createTime == null) {
            space.createTime = LocalDateTime.now();
        }
        space.updateTime = LocalDateTime.now();
        return persist(space);
    }

    public Uni<SpaceEntity> persistOrUpdateEntity(SpaceEntity space) {
        space.updateTime = LocalDateTime.now();
        return persistOrUpdate(space);
    }
}
