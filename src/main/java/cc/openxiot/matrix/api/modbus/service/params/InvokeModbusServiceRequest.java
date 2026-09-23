package cc.openxiot.matrix.api.modbus.service.params;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

/**
 * 调用 Modbus 服务方法的请求体：
 * {@code {"service": "<服务 id>", "function": <方法序号>, "values": {"设定温度": 70}}}。
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "调用 Modbus 服务方法")
public class InvokeModbusServiceRequest {

    @Schema(description = "Modbus 服务 ID（十六进制字符串）", required = true)
    public String service;

    @Schema(description = "方法序号（服务内 function 的 index）", examples = {"1"}, required = true)
    public Integer function;

    @Schema(description = "写入值（字段名 → 原始值，如 {\"开机\": true, \"设定温度\": 70}）："
            + "写方法未给值的字段回落其定义的缺省值；读方法必须不传",
            examples = {"{\"开机\":true}"})
    public Map<String, Object> values;
}
