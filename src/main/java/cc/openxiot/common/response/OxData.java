package cc.openxiot.common.response;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class OxData {

    public boolean success;
    public Object data;

    public OxData(Object data) {
        this.success = true;
        this.data = data;
    }
}
