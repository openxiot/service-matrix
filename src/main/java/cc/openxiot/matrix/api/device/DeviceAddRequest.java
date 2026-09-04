package cc.openxiot.matrix.api.device;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 添加单个设备（扫码/录入，did + key）的请求体。
 * 后端只消费 did、key 两个字段用于向 manipulation 校验并注册设备归属，
 * 多余字段会被忽略。native 镜像下需注册反射供 Jackson 反序列化。
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceAddRequest {

    @Schema(description = "设备 ID", required = true)
    public String did;

    @Schema(description = "设备访问密钥（AccessKey），用于向 manipulation 校验设备归属", required = true)
    public String key;
}
