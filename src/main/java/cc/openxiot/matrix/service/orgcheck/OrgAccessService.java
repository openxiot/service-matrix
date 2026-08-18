package cc.openxiot.matrix.service.orgcheck;

import cc.openxiot.account.user.organization.UserOrganization;
import cc.openxiot.account.user.organization.member.Member;
import cc.openxiot.matrix.db.space.SpaceOwnerType;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.SpaceOwner;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

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

    /**
     * 该组织是否为虚拟组织（项目成员关系）。
     */
    public Uni<Boolean> isVirtual(String orgId) {
        return UserOrganization.<UserOrganization>findById(orgId)
                .map(org -> org != null && org.isVirtual());
    }

    /**
     * 项目成员列表权限：真实组织成员 或 项目（虚拟组织）成员 均可查看。
     * <p>
     * 先查真实组织（项目所属组织）成员关系，失败再回退查虚拟组织（code = 根空间 id）。
     * 项目成员可能不是真实组织成员，但同样需要能查看项目成员列表。
     *
     * @param realOrgId      项目所属真实组织 id
     * @param virtualOrgCode 项目虚拟组织 code（= 根空间 id）
     * @param accountId      当前账号 id
     */
    public Uni<Void> checkProjectMember(String realOrgId, String virtualOrgCode, String accountId) {
        return checkMember(realOrgId, accountId)
                .onFailure(IllegalArgumentException.class)
                .recoverWithUni(() -> checkMember(virtualOrgCode, accountId));
    }

    /**
     * 空间只读访问：组织空间要求 X-Org-Id 且为该组织成员（含虚拟组织作用域），
     * 个人空间只允许拥有者本人（忽略 X-Org-Id，直接校验 JWT 用户）。
     */
    public Uni<Void> checkSpaceMember(SpaceEntity space, String orgId, String accountId) {
        SpaceOwner owner = requireOwner(space);

        if (SpaceOwnerType.USER.equals(owner.type)) {
            if (!accountId.equals(owner.id)) {
                return Uni.createFrom().failure(new IllegalArgumentException("No access to this space"));
            }
            return Uni.createFrom().voidItem();
        }

        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }

        return checkMember(orgId, accountId)
                .chain(() -> isVirtual(orgId))
                .map(virtual -> {
                    if (Boolean.TRUE.equals(virtual)) {
                        // 虚拟组织作用域 = 以 orgId 为根的整棵树
                        boolean inScope = space.id.toString().equals(orgId)
                                || (space.ancestors != null && space.ancestors.contains(orgId));
                        if (!inScope) {
                            throw new IllegalArgumentException("No access to this space");
                        }
                    } else if (!orgId.equals(owner.id)) {
                        throw new IllegalArgumentException("OrgId does not match the space");
                    }
                    return null;
                });
    }

    /**
     * 空间管理访问：组织空间要求 X-Org-Id 且为该组织管理员，
     * 个人空间只允许拥有者本人（拥有者即管理员）。
     */
    public Uni<Void> checkSpaceAdmin(SpaceEntity space, String orgId, String accountId) {
        SpaceOwner owner = requireOwner(space);

        if (SpaceOwnerType.USER.equals(owner.type)) {
            if (!accountId.equals(owner.id)) {
                return Uni.createFrom().failure(new IllegalArgumentException("No access to this space"));
            }
            return Uni.createFrom().voidItem();
        }

        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }

        if (!orgId.equals(owner.id)) {
            return Uni.createFrom().failure(new IllegalArgumentException("OrgId does not match the space"));
        }

        return checkAdmin(orgId, accountId);
    }

    /**
     * 项目成员列表权限：真实组织成员 或 项目（虚拟组织）成员 可查看；
     * 个人空间只允许拥有者本人。
     */
    public Uni<Void> checkRootSpaceMember(SpaceEntity space, String rootId, String accountId) {
        SpaceOwner owner = requireOwner(space);

        if (SpaceOwnerType.USER.equals(owner.type)) {
            if (!accountId.equals(owner.id)) {
                return Uni.createFrom().failure(new IllegalArgumentException("No access to this space"));
            }
            return Uni.createFrom().voidItem();
        }

        return checkProjectMember(owner.id, rootId, accountId);
    }

    /** 项目成员管理权限：真实组织管理员；个人空间只允许拥有者本人。 */
    public Uni<Void> checkRootSpaceAdmin(SpaceEntity space, String accountId) {
        SpaceOwner owner = requireOwner(space);

        if (SpaceOwnerType.USER.equals(owner.type)) {
            if (!accountId.equals(owner.id)) {
                return Uni.createFrom().failure(new IllegalArgumentException("No access to this space"));
            }
            return Uni.createFrom().voidItem();
        }

        return checkAdmin(owner.id, accountId);
    }

    private SpaceOwner requireOwner(SpaceEntity space) {
        SpaceOwner owner = space.owner;
        if (owner == null || owner.id == null || owner.id.isBlank()
                || owner.type == null || owner.type.isBlank()) {
            throw new IllegalArgumentException("Space owner is not set");
        }
        return owner;
    }
}
