package cc.openxiot.matrix.db.modbus.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Modbus 服务里的一个方法：一次父设备调用 = 一帧请求 + 一条应答解析规则。
 *
 * <p>读方法（点表 fc 01/02/03/04）有 {@code response}；写方法（fc 05/06/0F/10）的应答是请求回显、
 * 没有读值，{@code response} 为空数组。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Modbus 服务方法")
public class ModbusServiceFunction {

    @Schema(description = "方法序号（1 起自然数，服务内唯一；对应点表功能码动作的 index）", examples = {"1"}, required = true)
    public Integer index;

    @Schema(description = "方法名称（展示用，如 读蒸发器进水温度）", required = true)
    public String name;

    @Schema(description = "请求帧：完整的 Modbus RTU 帧 16 进制字符串（含 CRC16），由前端生成，原样交给父设备发送",
            examples = {"010300000001840A"}, required = true)
    public String request;

    @Schema(description = "应答解析规则：按 index 顺序、以 bytes 依次累加偏移切分应答数据区；空数组表示写方法")
    public List<ModbusServiceField> response;
}
