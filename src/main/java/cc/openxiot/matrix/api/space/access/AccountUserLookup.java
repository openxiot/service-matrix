package cc.openxiot.matrix.api.space.access;

import cc.openxiot.account.db.AccountUserRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

/**
 * 从 account 库解析名称快照（写库时解析，改名不追）。
 */
@ApplicationScoped
public class AccountUserLookup {

    @Inject
    AccountUserRepository accountUserRepository;

    /** 用户名快照；用户不存在或 id 非法时回退为原 id。 */
    public Uni<String> userName(String userId) {
        if (userId == null || userId.isBlank() || !ObjectId.isValid(userId)) {
            return Uni.createFrom().item(userId);
        }
        return accountUserRepository.findUserById(new ObjectId(userId))
                .map(u -> {
                    if (u == null || u.username == null || u.username.isBlank()) {
                        return userId;
                    }
                    return u.username;
                });
    }

    /** 组织名快照；组织不存在时回退为原 id。 */
    public Uni<String> orgName(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().item(orgId);
        }
        return accountUserRepository.findOrgById(orgId)
                .map(org -> {
                    if (org == null || org.name == null || org.name.isBlank()) {
                        return orgId;
                    }
                    return org.name;
                });
    }
}
