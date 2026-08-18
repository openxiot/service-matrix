package cc.openxiot.matrix.api.space;

import cc.openxiot.matrix.api.member.AccountUserLookup;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.SpaceOwner;
import cc.openxiot.matrix.db.space.SpaceOwnerRole;
import cc.openxiot.matrix.db.space.SpaceOwnerType;
import cc.openxiot.matrix.db.space.SpaceRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class SpaceService {

    @Inject
    SpaceRepository spaceRepository;

    @Inject
    AccountUserLookup accountUserLookup;

    public Uni<SpaceEntity> getById(String id) {
        return spaceRepository.findById(id);
    }

    public Uni<List<SpaceEntity>> getByParentId(String parentId) {
        return spaceRepository.findByParentId(parentId);
    }

    public Uni<List<SpaceEntity>> getRootsByOwner(String ownerId, String ownerType) {
        return spaceRepository.findRootsByOwner(ownerId, ownerType);
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

    /**
     * 创建根空间并写入拥有者条目：用户/组织名从 account 解析为快照。
     * 个人创建（type=user, role=admin）；组织创建（type=organization, role=admin）。
     */
    public Uni<SpaceEntity> createWithOwner(SpaceEntity space, SpaceOwner owner) {
        return resolveOwnerName(owner)
                .map(resolved -> {
                    space.owners = List.of(resolved);
                    return space;
                })
                .chain(this::create);
    }

    /** 添加根空间成员（user 条目；role 默认 member；name 从 account 解析快照）。 */
    public Uni<Void> addMember(String rootId, String memberId, String role, String remark) {
        String resolvedRole = normalizeRole(role);
        return accountUserLookup.userName(memberId)
                .chain(name -> spaceRepository.findById(rootId)
                        .chain(Unchecked.function(space -> {
                            if (space == null || space.parentId != null) {
                                throw new IllegalArgumentException("Root space not found: " + rootId);
                            }
                            if (space.owners != null && space.owners.stream().anyMatch(o ->
                                    SpaceOwnerType.USER.equals(o.type) && memberId.equals(o.id))) {
                                throw new IllegalArgumentException("Member already exists: " + memberId);
                            }
                            SpaceOwner owner = new SpaceOwner(memberId, SpaceOwnerType.USER, resolvedRole, name, remark);
                            return spaceRepository.pushOwner(rootId, owner);
                        })))
                .replaceWithVoid();
    }

    /** 移除根空间成员。 */
    public Uni<Void> removeMember(String rootId, String memberId) {
        return spaceRepository.findById(rootId)
                .chain(Unchecked.function(space -> {
                    if (space == null || space.parentId != null) {
                        throw new IllegalArgumentException("Root space not found: " + rootId);
                    }
                    boolean exists = space.owners != null && space.owners.stream().anyMatch(o ->
                            SpaceOwnerType.USER.equals(o.type) && memberId.equals(o.id));
                    if (!exists) {
                        throw new IllegalArgumentException("Member not found: " + memberId);
                    }
                    return spaceRepository.pullOwner(rootId, memberId);
                }))
                .replaceWithVoid();
    }

    private Uni<SpaceOwner> resolveOwnerName(SpaceOwner owner) {
        if (SpaceOwnerType.USER.equals(owner.type)) {
            return accountUserLookup.userName(owner.id)
                    .map(name -> new SpaceOwner(owner.id, owner.type, owner.role, name, owner.remark));
        }
        if (SpaceOwnerType.ORGANIZATION.equals(owner.type)) {
            return accountUserLookup.orgName(owner.id)
                    .map(name -> new SpaceOwner(owner.id, owner.type, owner.role, name, owner.remark));
        }
        return Uni.createFrom().item(owner);
    }

    private String normalizeRole(String role) {
        return SpaceOwnerRole.ADMIN.equals(role) ? SpaceOwnerRole.ADMIN : SpaceOwnerRole.MEMBER;
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
                        space.owners = parent.owners;
                        return spaceRepository.persistEntity(space);
                    });
        } else {
            requireRootOwners(space);
            space.level = 0;
            space.ancestors = List.of();
            space.parentId = null;
            return spaceRepository.persistEntity(space);
        }
    }

    private void requireRootOwners(SpaceEntity space) {
        if (space.owners == null || space.owners.isEmpty()) {
            throw new IllegalArgumentException("owners is required for root space");
        }
        for (SpaceOwner owner : space.owners) {
            if (owner.id == null || owner.id.isBlank()
                    || owner.type == null || owner.type.isBlank()) {
                throw new IllegalArgumentException("owner (id/type) is required for root space");
            }
        }
    }

    public Uni<SpaceEntity> update(SpaceEntity space) {
        return spaceRepository.findById(space.id)
                .chain(existing -> {
                    if (existing == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Space not found"));
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
                                        return Uni.createFrom().failure(new IllegalArgumentException("Parent space not found"));
                                    }
                                    existing.parentId = space.parentId;
                                    existing.level = parent.level + 1;
                                    List<String> ancestors = new ArrayList<>();
                                    if (parent.ancestors != null) {
                                        ancestors.addAll(parent.ancestors);
                                    }
                                    ancestors.add(space.parentId);
                                    existing.ancestors = ancestors;
                                    existing.owners = parent.owners;
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
                        return Uni.createFrom().failure(new IllegalArgumentException("Cannot delete space with children"));
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
