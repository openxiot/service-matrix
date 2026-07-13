package cc.openxiot.site.db.space;

import io.quarkus.mongodb.panache.PanacheMongoRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SpaceRepository implements PanacheMongoRepository<SpaceEntity> {

    public SpaceEntity findById(String id) {
        return findById(new ObjectId(id));
    }

    public boolean deleteById(String id) {
        return deleteById(new ObjectId(id));
    }

    public List<SpaceEntity> findByParentId(String parentId) {
        return list("parentId", parentId);
    }

    public List<SpaceEntity> findByAncestor(String ancestorId) {
        return list("ancestors", ancestorId);
    }

    public List<SpaceEntity> findRootByUserId(String userId) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        params.put("parentId", null);
        return list("users.userId = :userId and parentId = :parentId", params);
    }
}
