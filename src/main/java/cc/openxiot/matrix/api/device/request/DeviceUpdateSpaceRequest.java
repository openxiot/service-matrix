package cc.openxiot.matrix.api.device.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * 批量调整设备所在空间（PUT /matrix/v1/device/many/space）的请求体。
 * spaceId 既是迁移目标空间，也用于空间管理员鉴权；rootSpaceId 为目标项目的根空间，
 * 写入设备的 space.rootId；dids 为待迁移设备，缺省或为空表示不迁移任何设备。
 * native 镜像下需注册反射供 Jackson 反序列化。
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceUpdateSpaceRequest {

    @Schema(description = "迁移目标空间 ID，同时用于空间管理员鉴权", required = true)
    public String spaceId;

    @Schema(description = "目标项目的根空间 ID，写入设备的 space.rootId", required = true)
    public String rootSpaceId;

    @Schema(description = "待迁移的设备 ID 列表，缺省或为空表示不迁移")
    public List<String> dids;
}
