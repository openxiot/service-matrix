package cc.openxiot.matrix.api.modbus.service.params;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 调用 Modbus 服务方法的请求体：{@code {"service": "<服务 id>", "function": <方法序号>}}。
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "调用 Modbus 服务方法")
public class InvokeModbusServiceRequest {

    @Schema(description = "Modbus 服务 ID（十六进制字符串）", required = true)
    public String service;

    @Schema(description = "方法序号（服务内 function 的 index）", examples = {"1"}, required = true)
    public Integer function;
}
