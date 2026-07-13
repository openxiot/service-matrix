package cc.openxiot.site.db.space;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "空间用户角色")
public enum SpaceUserRole {

    MANAGER("manager"),
    OPERATOR("operator"),
    BROWSER("browser");

    private final String value;

    SpaceUserRole(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
