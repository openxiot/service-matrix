package cc.openxiot.site.api.orgcheck;

import cc.openxiot.account.organization.Organization;
import cc.openxiot.account.organization.member.Member;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class OrgAccessService {

    public Uni<Boolean> isMember(String orgId, String accountId) {
        return Organization.<Organization>findById(orgId)
                .map(org -> {
                    if (org == null) return false;
                    List<Member> members = org.members;
                    if (members == null) return false;
                    return members.stream()
                            .anyMatch(m -> accountId.equals(m.developerId));
                });
    }

    public Uni<Boolean> isAdmin(String orgId, String accountId) {
        return Organization.<Organization>findById(orgId)
                .map(org -> {
                    if (org == null) return false;
                    List<Member> members = org.members;
                    if (members == null) return false;
                    return members.stream()
                            .anyMatch(m -> accountId.equals(m.developerId)
                                    && "admin".equals(m.role));
                });
    }
}
