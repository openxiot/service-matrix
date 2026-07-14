package cc.openxiot.site.api.space;

import cc.openxiot.site.db.space.SpaceEntity;
import cc.openxiot.site.db.space.SpaceRepository;
import cc.openxiot.site.db.space.SpaceUser;
import cc.openxiot.site.db.space.SpaceUserRole;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SpaceService {

    @Inject
    SpaceRepository spaceRepository;

    public Uni<SpaceEntity> getById(String id) {
        return spaceRepository.findById(id);
    }

    public Uni<List<SpaceEntity>> getByParentId(String parentId) {
        return spaceRepository.findByParentId(parentId);
    }

    public Uni<List<SpaceEntity>> getRootsByUserId(String userId) {
        return spaceRepository.findRootByUserId(userId);
    }

    public Uni<List<SpaceEntity>> getDescendants(String rootId) {
        return spaceRepository.findById(rootId)
                .flatMap(root -> {
                    if (root == null) return Uni.createFrom().item(List.<SpaceEntity>of());
                    return spaceRepository.findByAncestor(rootId)
                            .map(descendants -> {
                                List<SpaceEntity> result = new ArrayList<>();
                                result.add(root);
                                result.addAll(descendants);
                                return result;
                            });
                })
                .onItem().ifNull().continueWith(List.of());
    }

    public Uni<SpaceEntity> create(SpaceEntity space, String userId) {
        if (space.parentId != null && !space.parentId.isBlank()) {
            return spaceRepository.findById(space.parentId)
                    .flatMap(parent -> {
                        if (parent == null) {
                            return Uni.createFrom().failure(
                                    new IllegalArgumentException("Parent space not found"));
                        }
                        space.level = parent.level + 1;
                        List<String> ancestors = new ArrayList<>();
                        if (parent.ancestors != null) {
                            ancestors.addAll(parent.ancestors);
                        }
                        ancestors.add(space.parentId);
                        space.ancestors = ancestors;
                        return setupUserAndPersist(space, userId);
                    });
        } else {
            space.level = 0;
            space.ancestors = List.of();
            space.parentId = null;
            return setupUserAndPersist(space, userId);
        }
    }

    private Uni<SpaceEntity> setupUserAndPersist(SpaceEntity space, String userId) {
        SpaceUser user = new SpaceUser();
        user.userId = userId;
        user.role = SpaceUserRole.MANAGER.toString();
        space.users = List.of(user);
        return space.persist();
    }

    public Uni<SpaceEntity> update(SpaceEntity space) {
        return spaceRepository.findById(space.id)
                .flatMap(existing -> {
                    if (existing == null) {
                        return Uni.createFrom().failure(
                                new IllegalArgumentException("Space not found"));
                    }

                    existing.name = space.name;
                    existing.type = space.type;
                    existing.typeAlias = space.typeAlias;
                    existing.sortOrder = space.sortOrder;
                    existing.attributes = space.attributes;

                    if (space.parentId != null && !space.parentId.equals(existing.parentId)) {
                        return spaceRepository.findById(space.parentId)
                                .flatMap(parent -> {
                                    if (parent == null) {
                                        return Uni.createFrom().failure(
                                                new IllegalArgumentException("Parent space not found"));
                                    }
                                    existing.parentId = space.parentId;
                                    existing.level = parent.level + 1;
                                    List<String> ancestors = new ArrayList<>();
                                    if (parent.ancestors != null) {
                                        ancestors.addAll(parent.ancestors);
                                    }
                                    ancestors.add(space.parentId);
                                    existing.ancestors = ancestors;
                                    return existing.update();
                                });
                    }

                    return existing.update();
                });
    }

    public Uni<Boolean> delete(String spaceId) {
        return spaceRepository.findByParentId(spaceId)
                .flatMap(children -> {
                    if (!children.isEmpty()) {
                        return Uni.createFrom().failure(
                                new IllegalArgumentException("Cannot delete space with children"));
                    }
                    return spaceRepository.deleteById(spaceId);
                });
    }

    public Uni<Boolean> noAccess(String spaceId, String userId) {
        return spaceRepository.findById(spaceId)
                .map(space -> {
                    if (space == null) return true;
                    if (space.users == null || space.users.isEmpty()) return true;
                    return space.users.stream().noneMatch(u -> u.userId.equals(userId));
                })
                .onItem().ifNull().continueWith(true);
    }
}
