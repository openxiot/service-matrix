package cc.openxiot.site.db.device;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "设备所在的空间")
@RegisterForReflection
public class DeviceSpace {

    @Schema(description = "空间ID", required = false)
    public String spaceId;

    @Schema(description = "根空间ID", required = false)
    public String rootId;
}
