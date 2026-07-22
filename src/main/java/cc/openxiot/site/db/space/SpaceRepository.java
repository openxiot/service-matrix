package cc.openxiot.site.db.space;

import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public Uni<List<SpaceEntity>> findRootByOrganizationId(String orgId) {
        Map<String, Object> params = new HashMap<>();
        params.put("orgId", orgId);
        params.put("parentId", null);
        return list("organizationId = :orgId and parentId = :parentId", params);
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
