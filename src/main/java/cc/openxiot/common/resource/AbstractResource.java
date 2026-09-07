package cc.openxiot.common.resource;

import cc.openxiot.common.person.Person;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

public abstract class AbstractResource {

    @Inject
    SecurityIdentity securityIdentity;

    protected String getUserId() {
        return securityIdentity.getPrincipal().getName();
    }

    /** 当前账号 ID（JWT subject，等同 userId）。 */
    protected String getAccountId() {
        return getUserId();
    }

    /** 当前账号显示名（JWT claim "username"），缺失时可能为 null。 */
    protected String getUserName() {
        if (securityIdentity.getPrincipal() instanceof JsonWebToken jwt) {
            return jwt.getClaim("username");
        }
        return null;
    }

    /** 以当前用户构造 Person（含当前时间）。 */
    protected Person currentUser() {
        return Person.of(getAccountId(), getUserName());
    }
}
