package cc.openxiot.site.api.orgcheck;

import cc.openxiot.account.organization.Organization;
import cc.openxiot.account.organization.member.Member;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class OrgAccessService {

    public Uni<Void> checkMember(String orgId, String accountId) {
        return Organization.<Organization>findById(orgId)
                .map(org -> {
                    if (org == null) {
                        throw new IllegalArgumentException("Organization not found");
                    }

                    List<Member> members = org.members;
                    if (members == null || members.stream().noneMatch(m -> accountId.equals(m.developerId))) {
                        throw new IllegalArgumentException("Not a member of the organization");
                    }

                    return null;
                });
    }

    public Uni<Void> checkAdmin(String orgId, String accountId) {
        return Organization.<Organization>findById(orgId)
                .map(org -> {
                    if (org == null) {
                        throw new IllegalArgumentException("Organization not found");
                    }

                    List<Member> members = org.members;
                    if (members == null || members.stream().noneMatch(m -> accountId.equals(m.developerId) && "admin".equals(m.role))) {
                        throw new IllegalArgumentException("Not an admin of the organization");
                    }

                    return null;
                });
    }
}
