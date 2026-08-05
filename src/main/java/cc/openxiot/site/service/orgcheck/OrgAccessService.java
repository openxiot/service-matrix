package cc.openxiot.site.service.orgcheck;

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
}
