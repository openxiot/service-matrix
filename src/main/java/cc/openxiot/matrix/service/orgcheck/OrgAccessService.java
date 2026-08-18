package cc.openxiot.matrix.service.orgcheck;

import cc.openxiot.account.user.organization.UserOrganization;
import cc.openxiot.account.user.organization.member.Member;
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
}
