package cc.openxiot.matrix.api.space;

import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.SpaceRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    public Uni<List<SpaceEntity>> getRootsByOrganizationId(String orgId) {
        return spaceRepository.findRootByOrganizationId(orgId);
    }

    public Uni<List<SpaceEntity>> getDescendants(String rootId) {
        return spaceRepository.findById(rootId)
                .chain(root -> {
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

    public Uni<SpaceEntity> create(SpaceEntity space) {
        if (space.parentId != null && !space.parentId.isBlank()) {
            return spaceRepository.findById(space.parentId)
                    .chain(parent -> {
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
                        space.organizationId = parent.organizationId;
                        return spaceRepository.persistEntity(space);
                    });
        } else {
            if (space.organizationId == null || space.organizationId.isBlank()) {
                return Uni.createFrom().failure(
                        new IllegalArgumentException("organizationId is required for root space"));
            }
            space.level = 0;
            space.ancestors = List.of();
            space.parentId = null;
            return spaceRepository.persistEntity(space);
        }
    }

    public Uni<SpaceEntity> update(SpaceEntity space) {
        return spaceRepository.findById(space.id)
                .chain(existing -> {
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
                                .chain(parent -> {
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
                                    existing.organizationId = parent.organizationId;
                                    return spaceRepository.persistOrUpdateEntity(existing);
                                });
                    }

                    return spaceRepository.persistOrUpdateEntity(existing);
                });
    }

    public Uni<Boolean> delete(String spaceId) {
        return spaceRepository.findByParentId(spaceId)
                .chain(children -> {
                    if (!children.isEmpty()) {
                        return Uni.createFrom().failure(
                                new IllegalArgumentException("Cannot delete space with children"));
                    }
                    return spaceRepository.deleteById(spaceId);
                });
    }

    public Uni<Boolean> noAccess(String spaceId) {
        return spaceRepository.findById(spaceId)
                .map(Objects::isNull)
                .onItem().ifNull().continueWith(true);
    }
}
