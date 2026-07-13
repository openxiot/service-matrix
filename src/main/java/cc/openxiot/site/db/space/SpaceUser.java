package cc.openxiot.site.db.space;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "空间用户")
@RegisterForReflection
public class SpaceUser {

    @Schema(description = "用户ID", required = true)
    public String userId;

    @Schema(description = "角色", required = true)
    public String role;
}
