package cc.openxiot.matrix.db.modbus.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Modbus 服务的父设备调用坐标：告诉服务「帧要发给谁、填在哪个入参上」。
 *
 * <p>由前端在创建时写入（映射到哪台父设备的哪个方法是确定的，后端不需要去产品定义里推算）。
 * 父设备侧承载数据帧的动作只有一个入参（不同 DTU 型号的 siid / aiid / 入参 piid 各不相同，
 * 例如 s710ym 是 1/2/2、dtu.json 是 1/1/1），故 {@code argument} 提到父级，对该服务的所有方法共用。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "父设备调用坐标")
public class ModbusServiceParent {

    @Schema(description = "父设备 ID（承载 Modbus 数据的 DTU）", required = true)
    public String did;

    @Schema(description = "父设备服务 ID（siid）", examples = {"1"}, required = true)
    public Integer siid;

    @Schema(description = "父设备方法 ID（aiid，通常是 DTU 的“发送”）", examples = {"2"}, required = true)
    public Integer aiid;

    @Schema(description = "父方法入参 piid：16 进制请求帧填在该入参上", examples = {"2"}, required = true)
    public Integer argument;
}
