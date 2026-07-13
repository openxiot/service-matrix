package cc.openxiot.common.resource;

import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

public abstract class AbstractResource {

    @Inject
    JsonWebToken jwt;

    protected String getUserId() {
        return jwt.getName();
    }
}
