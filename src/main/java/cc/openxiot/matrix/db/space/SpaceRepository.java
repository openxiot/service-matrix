package cc.openxiot.matrix.db.space;

import cc.openxiot.matrix.typedef.model.ModelAnchor;
import cc.openxiot.matrix.db.space.access.SpaceAccess;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
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

    /** 按祖先取整棵子树（{@code ancestors} 含该 id 的都是它的后代，不含自己）。索引见 {@link #ensureIndexes}。 */
    public Uni<List<SpaceEntity>> findByAncestor(String ancestorId) {
        return list("ancestors", ancestorId);
    }

    /**
     * 启动时确保 {@code ancestors} 索引存在（幂等；失败仅告警，不阻断启动）。
     *
     * <p>{@code ancestors} 是数组，这条是**多键索引**：空间树（{@code /tree/{rootId}}）与空间图
     * （{@code /graph/{rootId}}）两个接口都要靠 {@link #findByAncestor} 取整棵子树，
     * 没有索引就是每次全表扫。</p>
     *
     * <p>只给 {@code ancestors} 建，没给 {@code parentId}：后者目前只用在删空间前的「有没有子空间」
     * 这一处判断上（{@link #findByParentId}），不在热路径上。哪天给空间树做懒加载（按父逐层取）
     * 再补它。</p>
     */
    public Uni<Void> ensureIndexes() {
        return mongoCollection()
                .createIndex(Indexes.ascending("ancestors"))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * 根空间列表：accesses 数组内「同一元素」满足 id + type（$elemMatch，避免数组点号跨元素误匹配）。
     * type = organization → 组织根空间；type = user → 用户作为管理员或成员参与的根空间。
     */
    public Uni<List<SpaceEntity>> findRootsByAccess(String accessId, String accessType) {
        Document filter = new Document("parentId", null)
                .append("accesses", new Document("$elemMatch",
                        new Document("id", accessId).append("type", accessType)));
        return mongoCollection().find(filter).collect().asList();
    }

    /** 原子追加访问条目（$push） */
    public Uni<Long> pushAccess(String rootId, SpaceAccess access) {
        Document accessDoc = new Document("id", access.id)
                .append("type", access.type)
                .append("role", access.role)
                .append("name", access.name)
                .append("remark", access.remark);
        return mongoCollection()
                .updateOne(Filters.eq("_id", new ObjectId(rootId)), Updates.push("accesses", accessDoc))
                .map(UpdateResult::getModifiedCount);
    }

    /** 原子移除用户访问条目（$pull，仅匹配 type = user 的条目） */
    public Uni<Long> pullAccess(String rootId, String memberId) {
        return mongoCollection()
                .updateOne(Filters.eq("_id", new ObjectId(rootId)),
                        Updates.pull("accesses",
                                Filters.and(
                                        Filters.eq("type", SpaceAccessType.USER),
                                        Filters.eq("id", memberId))))
                .map(UpdateResult::getModifiedCount);
    }

    /** 原子调整用户访问条目角色（$set 位置操作符，仅匹配 type = user 的条目） */
    public Uni<Long> updateAccessRole(String rootId, String memberId, String role) {
        return mongoCollection()
                .updateOne(
                        Filters.and(
                                Filters.eq("_id", new ObjectId(rootId)),
                                Filters.elemMatch("accesses", Filters.and(
                                        Filters.eq("type", SpaceAccessType.USER),
                                        Filters.eq("id", memberId)))),
                        Updates.set("accesses.$.role", role))
                .map(UpdateResult::getModifiedCount);
    }

    /** 原子更新用户访问条目备注（$set 位置操作符，仅匹配 type = user 的条目） */
    public Uni<Long> updateAccessRemark(String rootId, String memberId, String remark) {
        return mongoCollection()
                .updateOne(
                        Filters.and(
                                Filters.eq("_id", new ObjectId(rootId)),
                                Filters.elemMatch("accesses", Filters.and(
                                        Filters.eq("type", SpaceAccessType.USER),
                                        Filters.eq("id", memberId)))),
                        Updates.set("accesses.$.remark", remark))
                .map(UpdateResult::getModifiedCount);
    }

    /**
     * 原子写入 3D 锚点（$set）。
     *
     * <p>用更新操作符而不是「查出来赋值再存」：锚点只动 {@code anchor} 一个字段，
     * 跟同时改名字/属性的别的客户端互不覆盖，也不会把整份文档按陈旧副本写回去。</p>
     */
    public Uni<Long> setAnchor(String spaceId, ModelAnchor anchor) {
        return mongoCollection()
                .updateOne(Filters.eq("_id", new ObjectId(spaceId)), Updates.set("anchor", anchor.toDocument()))
                .map(UpdateResult::getModifiedCount);
    }

    /** 原子清除 3D 锚点（$unset）。字段整个移除，不是置 null。 */
    public Uni<Long> clearAnchor(String spaceId) {
        return mongoCollection()
                .updateOne(Filters.eq("_id", new ObjectId(spaceId)), Updates.unset("anchor"))
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
