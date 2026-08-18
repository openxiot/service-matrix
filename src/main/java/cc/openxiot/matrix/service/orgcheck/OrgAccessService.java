package cc.openxiot.matrix.service.orgcheck;

import cc.openxiot.account.user.organization.UserOrganization;
import cc.openxiot.account.user.organization.member.Member;
import cc.openxiot.matrix.db.space.SpaceOwnerRole;
import cc.openxiot.matrix.db.space.SpaceOwnerType;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.SpaceOwner;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * 空间访问控制。根空间持有 owners 列表（组织条目为管理员；用户条目角色可为 admin / member）：
 * <ul>
 *     <li>读：owners 含「该用户」的 user 条目（任意角色）；或 X-Org-Id 命中某组织条目且该用户在组织中。</li>
 *     <li>管：owners 含「该用户 + role=admin」的 user 条目；或 X-Org-Id 命中某组织条目且该用户是组织管理员。</li>
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
        List<SpaceOwner> owners = requireOwners(space);

        if (isDirectMember(owners, accountId)) {
            return Uni.createFrom().voidItem();
        }

        SpaceOwner orgOwner = orgOwner(owners, orgId);
        if (orgOwner != null) {
            return checkMember(orgOwner.id, accountId);
        }

        boolean hasOrg = owners.stream().anyMatch(o -> SpaceOwnerType.ORGANIZATION.equals(o.type));
        return Uni.createFrom().failure(new IllegalArgumentException(
                hasOrg ? "OrgId does not match the space" : "No access to this space"));
    }

    /** 空间管理访问：直接管理员（user + role=admin）或组织管理员（X-Org-Id 命中组织条目）。 */
    public Uni<Void> checkSpaceAdmin(SpaceEntity space, String orgId, String accountId) {
        List<SpaceOwner> owners = requireOwners(space);

        if (isDirectAdmin(owners, accountId)) {
            return Uni.createFrom().voidItem();
        }

        SpaceOwner orgOwner = orgOwner(owners, orgId);
        if (orgOwner != null) {
            return checkAdmin(orgOwner.id, accountId);
        }

        boolean hasOrg = owners.stream().anyMatch(o -> SpaceOwnerType.ORGANIZATION.equals(o.type));
        return Uni.createFrom().failure(new IllegalArgumentException(
                hasOrg ? "OrgId does not match the space" : "No access to this space"));
    }

    /** 根空间成员列表权限：直接成员（任意角色）或组织成员（组织条目命中，无需 X-Org-Id）。 */
    public Uni<Void> checkRootSpaceMember(SpaceEntity space, String rootId, String accountId) {
        List<SpaceOwner> owners = requireOwners(space);

        if (isDirectMember(owners, accountId)) {
            return Uni.createFrom().voidItem();
        }

        SpaceOwner orgOwner = firstOrgOwner(owners);
        if (orgOwner != null) {
            return checkMember(orgOwner.id, accountId);
        }

        return Uni.createFrom().failure(new IllegalArgumentException("No access to this space"));
    }

    /** 根空间成员管理权限：直接管理员（user + role=admin）或组织管理员（组织条目命中）。 */
    public Uni<Void> checkRootSpaceAdmin(SpaceEntity space, String accountId) {
        List<SpaceOwner> owners = requireOwners(space);

        if (isDirectAdmin(owners, accountId)) {
            return Uni.createFrom().voidItem();
        }

        SpaceOwner orgOwner = firstOrgOwner(owners);
        if (orgOwner != null) {
            return checkAdmin(orgOwner.id, accountId);
        }

        return Uni.createFrom().failure(new IllegalArgumentException("No access to this space"));
    }

    private List<SpaceOwner> requireOwners(SpaceEntity space) {
        List<SpaceOwner> owners = space.owners;
        if (owners == null || owners.isEmpty()) {
            throw new IllegalArgumentException("Space owners is not set");
        }
        return owners;
    }

    private boolean isDirectMember(List<SpaceOwner> owners, String accountId) {
        return owners.stream().anyMatch(o ->
                SpaceOwnerType.USER.equals(o.type) && accountId.equals(o.id));
    }

    private boolean isDirectAdmin(List<SpaceOwner> owners, String accountId) {
        return owners.stream().anyMatch(o ->
                SpaceOwnerType.USER.equals(o.type)
                        && accountId.equals(o.id)
                        && SpaceOwnerRole.ADMIN.equals(o.role));
    }

    private SpaceOwner orgOwner(List<SpaceOwner> owners, String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return null;
        }
        return owners.stream()
                .filter(o -> SpaceOwnerType.ORGANIZATION.equals(o.type) && orgId.equals(o.id))
                .findFirst()
                .orElse(null);
    }

    private SpaceOwner firstOrgOwner(List<SpaceOwner> owners) {
        return owners.stream()
                .filter(o -> SpaceOwnerType.ORGANIZATION.equals(o.type))
                .findFirst()
                .orElse(null);
    }
}
