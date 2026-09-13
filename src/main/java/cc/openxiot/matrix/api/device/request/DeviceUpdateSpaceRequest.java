package cc.openxiot.matrix.api.device.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * 批量调整设备所在空间（PUT /matrix/v1/device/many/space）的请求体。
 * spaceId 既是迁移目标空间，也用于空间管理员鉴权；dids 为待迁移设备，缺省或为空表示不迁移任何设备。
 * native 镜像下需注册反射供 Jackson 反序列化。
 *
 * <p>原来还有一个 {@code rootSpaceId}（客户端传目标项目的根空间，写进设备的 {@code space.rootId}）——
 * 已去掉：根空间由服务端从目标空间现算（{@link cc.openxiot.matrix.db.space.SpaceEntity#rootSpaceId()}），
 * 客户端传的只是它自己的认知，对不上就写进去一个错的根。旧客户端多传这个字段也不报错
 * （{@link JsonIgnoreProperties} 忽略未知字段），只是不再被采信。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceUpdateSpaceRequest {

    @Schema(description = "迁移目标空间 ID，同时用于空间管理员鉴权", required = true)
    public String spaceId;

    @Schema(description = "待迁移的设备 ID 列表，缺省或为空表示不迁移")
    public List<String> dids;
}
