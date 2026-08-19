package cc.openxiot.matrix.api.space;

import cc.openxiot.matrix.api.space.access.AccountUserLookup;
import cc.openxiot.matrix.db.space.access.SpaceAccess;
import cc.openxiot.matrix.db.space.access.SpaceAccessRole;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import cc.openxiot.matrix.db.space.SpaceEntity;
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

    public Uni<List<SpaceEntity>> getRootsByAccess(String accessId, String accessType) {
        return spaceRepository.findRootsByAccess(accessId, accessType);
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
     * 创建根空间并写入访问条目：用户/组织名从 account 解析为快照。
     * 个人创建（type=user, role=admin）；组织创建（type=organization, role=admin）。
     */
    public Uni<SpaceEntity> createWithAccess(SpaceEntity space, SpaceAccess access) {
        return resolveAccessName(access)
                .map(resolved -> {
                    space.accesses = List.of(resolved);
                    return space;
                })
                .chain(this::create);
    }

    /** 添加根空间用户访问条目（role 默认 member；name 从 account 解析快照）。 */
    public Uni<Void> addAccess(String rootId, String memberId, String role, String remark) {
        String resolvedRole = normalizeRole(role);
        return accountUserLookup.userName(memberId)
                .chain(name -> spaceRepository.findById(rootId)
                        .chain(Unchecked.function(space -> {
                            if (space == null || space.parentId != null) {
                                throw new IllegalArgumentException("Root space not found: " + rootId);
                            }
                            if (space.accesses != null && space.accesses.stream().anyMatch(o ->
                                    SpaceAccessType.USER.equals(o.type) && memberId.equals(o.id))) {
                                throw new IllegalArgumentException("Member already exists: " + memberId);
                            }
                            SpaceAccess access = new SpaceAccess(memberId, SpaceAccessType.USER, resolvedRole, name, remark);
                            return spaceRepository.pushAccess(rootId, access);
                        })))
                .replaceWithVoid();
    }

    /** 调整成员角色（admin/member）。最后一个管理员不可被降级。 */
    public Uni<Void> updateAccessRole(String rootId, String memberId, String role) {
        String resolvedRole = normalizeRole(role);
        return spaceRepository.findById(rootId)
                .chain(Unchecked.function(space -> {
                    if (space == null || space.parentId != null) {
                        throw new IllegalArgumentException("Root space not found: " + rootId);
                    }
                    SpaceAccess target = findUserAccess(space, memberId);
                    if (target == null) {
                        throw new IllegalArgumentException("Member not found: " + memberId);
                    }
                    if (SpaceAccessRole.ADMIN.equals(target.role)
                            && !resolvedRole.equals(target.role)
                            && !hasAdminWithout(space, memberId)) {
                        throw new IllegalArgumentException("Cannot demote the last admin");
                    }
                    return spaceRepository.updateAccessRole(rootId, memberId, resolvedRole);
                }))
                .replaceWithVoid();
    }

    /** 移除根空间用户访问条目（管理员移除其他成员，或成员本人退出）。最后一个管理员不可被移除。 */
    public Uni<Void> removeAccess(String rootId, String memberId) {
        return spaceRepository.findById(rootId)
                .chain(Unchecked.function(space -> {
                    if (space == null || space.parentId != null) {
                        throw new IllegalArgumentException("Root space not found: " + rootId);
                    }
                    SpaceAccess target = findUserAccess(space, memberId);
                    if (target == null) {
                        throw new IllegalArgumentException("Member not found: " + memberId);
                    }
                    if (SpaceAccessRole.ADMIN.equals(target.role) && !hasAdminWithout(space, memberId)) {
                        throw new IllegalArgumentException("Cannot remove the last admin");
                    }
                    return spaceRepository.pullAccess(rootId, memberId);
                }))
                .replaceWithVoid();
    }

    private SpaceAccess findUserAccess(SpaceEntity space, String memberId) {
        if (space.accesses == null) {
            return null;
        }
        return space.accesses.stream()
                .filter(o -> SpaceAccessType.USER.equals(o.type) && memberId.equals(o.id))
                .findFirst()
                .orElse(null);
    }

    /** 排除指定用户后，是否仍存在其他 user admin（不做组织兜底）。 */
    private boolean hasAdminWithout(SpaceEntity space, String excludedUserId) {
        if (space.accesses == null) {
            return false;
        }
        return space.accesses.stream().anyMatch(o ->
                SpaceAccessType.USER.equals(o.type)
                        && !excludedUserId.equals(o.id)
                        && SpaceAccessRole.ADMIN.equals(o.role));
    }

    private Uni<SpaceAccess> resolveAccessName(SpaceAccess access) {
        if (SpaceAccessType.USER.equals(access.type)) {
            return accountUserLookup.userName(access.id)
                    .map(name -> new SpaceAccess(access.id, access.type, access.role, name, access.remark));
        }
        if (SpaceAccessType.ORGANIZATION.equals(access.type)) {
            return accountUserLookup.orgName(access.id)
                    .map(name -> new SpaceAccess(access.id, access.type, access.role, name, access.remark));
        }
        return Uni.createFrom().item(access);
    }

    private String normalizeRole(String role) {
        return SpaceAccessRole.ADMIN.equals(role) ? SpaceAccessRole.ADMIN : SpaceAccessRole.MEMBER;
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
                        space.accesses = parent.accesses;
                        return spaceRepository.persistEntity(space);
                    });
        } else {
            requireRootAccesses(space);
            space.level = 0;
            space.ancestors = List.of();
            space.parentId = null;
            return spaceRepository.persistEntity(space);
        }
    }

    private void requireRootAccesses(SpaceEntity space) {
        if (space.accesses == null || space.accesses.isEmpty()) {
            throw new IllegalArgumentException("accesses is required for root space");
        }
        for (SpaceAccess access : space.accesses) {
            if (access.id == null || access.id.isBlank()
                    || access.type == null || access.type.isBlank()) {
                throw new IllegalArgumentException("access (id/type) is required for root space");
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
                                    existing.accesses = parent.accesses;
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
