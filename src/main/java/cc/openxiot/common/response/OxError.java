package cc.openxiot.common.response;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class OxError {

    public boolean success;
    public String message;

    public OxError(String message) {
        this.success = false;
        this.message = message;
    }
}
