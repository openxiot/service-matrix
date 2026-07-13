package cc.openxiot.site.api.space;

import cc.openxiot.site.db.space.SpaceEntity;
import cc.openxiot.site.db.space.SpaceRepository;
import cc.openxiot.site.db.space.SpaceUser;
import cc.openxiot.site.db.space.SpaceUserRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SpaceService {

    @Inject
    SpaceRepository spaceRepository;

    public SpaceEntity getById(String id) {
        return spaceRepository.findById(id);
    }

    public List<SpaceEntity> getByParentId(String parentId) {
        return spaceRepository.findByParentId(parentId);
    }

    public List<SpaceEntity> getRootsByUserId(String userId) {
        return spaceRepository.findRootByUserId(userId);
    }

    public List<SpaceEntity> getDescendants(String rootId) {
        SpaceEntity root = spaceRepository.findById(rootId);
        if (root == null) {
            return List.of();
        }
        List<SpaceEntity> descendants = spaceRepository.findByAncestor(rootId);
        List<SpaceEntity> result = new ArrayList<>();
        result.add(root);
        result.addAll(descendants);
        return result;
    }

    public SpaceEntity create(SpaceEntity space, String userId) {
        if (space.parentId != null && !space.parentId.isBlank()) {
            SpaceEntity parent = spaceRepository.findById(space.parentId);
            if (parent == null) {
                throw new IllegalArgumentException("Parent space not found");
            }
            space.level = parent.level + 1;
            List<String> ancestors = new ArrayList<>();
            if (parent.ancestors != null) {
                ancestors.addAll(parent.ancestors);
            }
            ancestors.add(space.parentId);
            space.ancestors = ancestors;
        } else {
            space.level = 0;
            space.ancestors = List.of();
            space.parentId = null;
        }

        SpaceUser user = new SpaceUser();
        user.userId = userId;
        user.role = SpaceUserRole.MANAGER.toString();
        space.users = List.of(user);

        space.persist();
        return space;
    }

    public SpaceEntity update(SpaceEntity space) {
        SpaceEntity existing = spaceRepository.findById(space.id);
        if (existing == null) {
            throw new IllegalArgumentException("Space not found");
        }

        existing.name = space.name;
        existing.type = space.type;
        existing.typeAlias = space.typeAlias;
        existing.sortOrder = space.sortOrder;
        existing.attributes = space.attributes;

        if (space.parentId != null && !space.parentId.equals(existing.parentId)) {
            SpaceEntity parent = spaceRepository.findById(space.parentId);
            if (parent == null) {
                throw new IllegalArgumentException("Parent space not found");
            }

            existing.parentId = space.parentId;
            existing.level = parent.level + 1;

            List<String> ancestors = new ArrayList<>();
            if (parent.ancestors != null) {
                ancestors.addAll(parent.ancestors);
            }
            ancestors.add(space.parentId);
            existing.ancestors = ancestors;
        }

        existing.update();
        return existing;
    }

    public void delete(String spaceId) {
        List<SpaceEntity> children = spaceRepository.findByParentId(spaceId);
        if (!children.isEmpty()) {
            throw new IllegalArgumentException("Cannot delete space with children");
        }
        spaceRepository.deleteById(spaceId);
    }

    public boolean noAccess(String spaceId, String userId) {
        SpaceEntity space = spaceRepository.findById(spaceId);
        if (space == null) {
            return true;
        }
        if (space.users == null || space.users.isEmpty()) {
            return true;
        }
        return space.users.stream().noneMatch(u -> u.userId.equals(userId));
    }
}
