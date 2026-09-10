package cc.openxiot.matrix.db.space;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 设备所在的空间，供「依赖某台设备」的实体记录其落点：设备实体（{@code DeviceEntity.space}）与
 * Modbus 服务（{@code DeviceOn.space}）共用，后者据此按空间反查服务。
 *
 * <p>只记 id 不记整个 {@link SpaceEntity}：空间会改名、移动，依赖方不该跟着烂掉；
 * 由写方（设备侧是同步空间时）从 {@link SpaceEntity} 现取现填。</p>
 */
@RegisterForReflection
@Schema(description = "设备所在的空间")
public class SpaceOn {

    @Schema(description = "空间ID", required = false)
    public String spaceId;

    @Schema(description = "根空间ID", required = false)
    public String rootId;

    public SpaceOn() {
    }

    public SpaceOn(SpaceEntity entity) {
        spaceId = entity.id.toString();
        rootId = entity.rootId;
    }
}
