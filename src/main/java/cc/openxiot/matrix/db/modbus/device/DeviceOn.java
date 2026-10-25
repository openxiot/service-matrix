package cc.openxiot.matrix.db.modbus.device;

import cc.openxiot.matrix.db.space.SpaceOn;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Modbus设备依赖的那台设备：告诉服务「帧要发给谁、填在哪个入参上」，以及那台设备落在哪个空间。
 *
 * <p>调用坐标（did / siid / aiid / argument）由前端在创建时写入：映射到哪台设备、哪个方法是确定的，
 * 后端不需要去产品定义里推算。设备侧承载数据帧的动作只有一个入参（不同 DTU 型号的 siid / aiid /
 * 入参 piid 各不相同，例如 s710ym 是 1/2/2、dtu.json 是 1/1/1），故 {@code argument} 提到这一级，
 * 对该服务的所有方法共用。</p>
 *
 * <p>{@link #space} 记录该设备所在的空间，供「按空间查服务」用（空间图里一次取一个空间的服务），
 * 口径与 {@code DeviceEntity.space} 相同。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Modbus设备依赖的设备（父设备调用坐标 + 所在空间）")
public class DeviceOn {

    @Schema(description = "设备 ID（承载 Modbus 数据的 DTU）", required = true)
    public String did;

    @Schema(description = "该设备服务 ID（siid）", examples = {"1"}, required = true)
    public Integer siid;

    @Schema(description = "该设备方法 ID（aiid，通常是 DTU 的“发送”）", examples = {"2"}, required = true)
    public Integer aiid;

    @Schema(description = "该设备方法的入参 piid：16 进制请求帧填在该入参上", examples = {"2"}, required = true)
    public Integer argument;

    @Schema(description = "该设备所在的空间（按空间查服务用）")
    public SpaceOn space;
}
