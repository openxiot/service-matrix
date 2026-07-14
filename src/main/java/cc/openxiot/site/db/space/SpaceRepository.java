package cc.openxiot.site.db.space;

import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

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

    public Uni<List<SpaceEntity>> findRootByUserId(String userId) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        params.put("parentId", null);
        return list("users.userId = :userId and parentId = :parentId", params);
    }
}
