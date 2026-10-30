package cc.openxiot.matrix.api.card.device.params;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * 保存设备卡片配置的请求体（{@code PUT /matrix/v1/card/device/{spaceId}/{did}}）。
 *
 * <p><b>为什么不直接绑实体</b>：实体上的 {@code spaceId} / {@code did} / {@code creator} /
 * {@code updater} 只能由服务端定。{@code spaceId} 取自路径（并规范成根空间），{@code did} 也取自路径
 * —— 让客户端提交它们，只会多出「前端把设备塞进别的项目」这种要靠额外校验兜住的 bug。</p>
 *
 * <p>{@code services} 缺省（{@code null}）按空清单处理：那是一次「清空卡片」的保存，
 * 与显式 {@code []} 同义。从未配置过的状态不是靠这个表达，而是「文档不存在」（DELETE 恢复默认）。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "保存设备卡片配置的请求体")
public class DeviceCardDto {

    @Schema(description = "要显示的服务及其只读属性，顺序即显示顺序；空清单表示不显示任何服务", required = false)
    public List<ServiceSelection> services;

    /** 一个服务上的选择。与实体内嵌类形状相同，但分成两套：请求体只含客户端能决定的字段。 */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "设备卡片上的一个服务选择")
    public static class ServiceSelection {

        @Schema(description = "服务 ID（siid）", examples = {"1"}, required = true)
        public Integer siid;

        @Schema(description = "要显示的只读属性 ID（piid）列表，顺序即显示顺序", required = false)
        public List<Integer> piids;
    }
}
