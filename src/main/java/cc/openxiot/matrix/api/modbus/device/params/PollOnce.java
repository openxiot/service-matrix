package cc.openxiot.matrix.api.modbus.device.params;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

/**
 * 轮询一次：
 * {@code {"service": "<服务 id>", "pollIndex": <方法序号>, "values": {"设定温度": 70}}}。
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "轮询一次")
public class PollOnce {

    @Schema(description = "Modbus设备 ID（十六进制字符串）", required = true)
    public String service;

    @Schema(description = "轮询序号（ModbusPoll.index）", examples = {"1"}, required = true)
    public Integer pollIndex;

    @Schema(description = "写入值（字段名 → 原始值，如 {\"开机\": true, \"设定温度\": 70}）："
            + "写方法未给值的字段回落其定义的缺省值；读方法必须不传",
            examples = {"{\"开机\":true}"})
    public Map<String, Object> values;
}
