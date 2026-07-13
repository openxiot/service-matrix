package cc.openxiot.common.resource;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;

public abstract class AbstractResource {

    @Inject
    SecurityIdentity securityIdentity;

    protected String getUserId() {
        return securityIdentity.getPrincipal().getName();
    }
}
