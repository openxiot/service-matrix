package cc.openxiot.matrix.service.orgcheck;

import cc.openxiot.account.user.organization.UserOrganization;
import cc.openxiot.account.user.organization.member.Member;
import cc.openxiot.matrix.db.space.access.SpaceAccess;
import cc.openxiot.matrix.db.space.access.SpaceAccessRole;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * 空间访问控制。根空间持有 accesses 列表（组织条目为管理员；用户条目角色可为 admin / member）：
 * <ul>
 *     <li>读：accesses 含「该用户」的 user 条目（任意角色）；或 X-Org-Id 命中某组织条目且该用户在组织中。</li>
 *     <li>管：accesses 含「该用户 + role=admin」的 user 条目；或 X-Org-Id 命中某组织条目且该用户是组织管理员。</li>
 * </ul>
 * 组织成员关系仍存于 account.user-organizations（真实组织），组织条目本身不定义具体用户。
 */
@ApplicationScoped
public class OrgAccessService {

    public Uni<Void> checkMember(String orgId, String accountId) {
        return UserOrganization.<UserOrganization>findById(orgId)
                .map(org -> {
                    if (org == null) {
                        throw new IllegalArgumentException("Organization not found");
                    }

                    List<Member> members = org.members;
                    if (members == null || members.stream().noneMatch(m -> accountId.equals(m.userId))) {
                        throw new IllegalArgumentException("Not a member of the organization");
                    }

                    return null;
                });
    }

    public Uni<Void> checkAdmin(String orgId, String accountId) {
        return UserOrganization.<UserOrganization>findById(orgId)
                .map(org -> {
                    if (org == null) {
                        throw new IllegalArgumentException("Organization not found");
                    }

                    List<Member> members = org.members;
                    if (members == null || members.stream().noneMatch(m -> accountId.equals(m.userId) && "admin".equals(m.role))) {
                        throw new IllegalArgumentException("Not an admin of the organization");
                    }

                    return null;
                });
    }

    /** 空间只读访问：直接成员（任意角色）或组织成员（X-Org-Id 命中组织条目）。 */
    public Uni<Void> checkSpaceMember(SpaceEntity space, String orgId, String accountId) {
        List<SpaceAccess> accesses = requireAccesses(space);

        if (isDirectMember(accesses, accountId)) {
            return Uni.createFrom().voidItem();
        }

        SpaceAccess orgAccess = orgAccess(accesses, orgId);
        if (orgAccess != null) {
            return checkMember(orgAccess.id, accountId);
        }

        boolean hasOrg = accesses.stream().anyMatch(o -> SpaceAccessType.ORGANIZATION.equals(o.type));
        return Uni.createFrom().failure(new IllegalArgumentException(
                hasOrg ? "OrgId does not match the space" : "No access to this space"));
    }

    /** 空间管理访问：直接管理员（user + role=admin）或组织管理员（X-Org-Id 命中组织条目）。 */
    public Uni<Void> checkSpaceAdmin(SpaceEntity space, String orgId, String accountId) {
        List<SpaceAccess> accesses = requireAccesses(space);

        if (isDirectAdmin(accesses, accountId)) {
            return Uni.createFrom().voidItem();
        }

        SpaceAccess orgAccess = orgAccess(accesses, orgId);
        if (orgAccess != null) {
            return checkAdmin(orgAccess.id, accountId);
        }

        boolean hasOrg = accesses.stream().anyMatch(o -> SpaceAccessType.ORGANIZATION.equals(o.type));
        return Uni.createFrom().failure(new IllegalArgumentException(
                hasOrg ? "OrgId does not match the space" : "No access to this space"));
    }

    /** 根空间成员列表权限：直接成员（任意角色）或组织成员（组织条目命中，无需 X-Org-Id）。 */
    public Uni<Void> checkRootSpaceMember(SpaceEntity space, String rootId, String accountId) {
        List<SpaceAccess> accesses = requireAccesses(space);

        if (isDirectMember(accesses, accountId)) {
            return Uni.createFrom().voidItem();
        }

        SpaceAccess orgAccess = firstOrgAccess(accesses);
        if (orgAccess != null) {
            return checkMember(orgAccess.id, accountId);
        }

        return Uni.createFrom().failure(new IllegalArgumentException("No access to this space"));
    }

    /** 根空间成员管理权限：直接管理员（user + role=admin）或组织管理员（组织条目命中）。 */
    public Uni<Void> checkRootSpaceAdmin(SpaceEntity space, String accountId) {
        List<SpaceAccess> accesses = requireAccesses(space);

        if (isDirectAdmin(accesses, accountId)) {
            return Uni.createFrom().voidItem();
        }

        SpaceAccess orgAccess = firstOrgAccess(accesses);
        if (orgAccess != null) {
            return checkAdmin(orgAccess.id, accountId);
        }

        return Uni.createFrom().failure(new IllegalArgumentException("No access to this space"));
    }

    private List<SpaceAccess> requireAccesses(SpaceEntity space) {
        List<SpaceAccess> accesses = space.accesses;
        if (accesses == null || accesses.isEmpty()) {
            throw new IllegalArgumentException("Space accesses is not set");
        }
        return accesses;
    }

    private boolean isDirectMember(List<SpaceAccess> accesses, String accountId) {
        return accesses.stream().anyMatch(o ->
                SpaceAccessType.USER.equals(o.type) && accountId.equals(o.id));
    }

    private boolean isDirectAdmin(List<SpaceAccess> accesses, String accountId) {
        return accesses.stream().anyMatch(o ->
                SpaceAccessType.USER.equals(o.type)
                        && accountId.equals(o.id)
                        && SpaceAccessRole.ADMIN.equals(o.role));
    }

    private SpaceAccess orgAccess(List<SpaceAccess> accesses, String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return null;
        }
        return accesses.stream()
                .filter(o -> SpaceAccessType.ORGANIZATION.equals(o.type) && orgId.equals(o.id))
                .findFirst()
                .orElse(null);
    }

    private SpaceAccess firstOrgAccess(List<SpaceAccess> accesses) {
        return accesses.stream()
                .filter(o -> SpaceAccessType.ORGANIZATION.equals(o.type))
                .findFirst()
                .orElse(null);
    }
}
