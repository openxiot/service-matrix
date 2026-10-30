package cc.openxiot.matrix.api.card.fd.params;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * 保存现场设备卡片配置的请求体（{@code PUT /matrix/v1/card/fd/{spaceId}/{fdid}}）。
 *
 * <p>只有 {@code registers}：{@code spaceId} / {@code fdid} 取自路径，{@code creator} /
 * {@code updater} 取自 JWT —— 实体上那些字段一律不让客户端提交。</p>
 *
 * <p>{@code registers} 缺省（{@code null}）按空清单处理：那是一次「清空卡片」的保存。
 * 从未配置过的状态不是靠这个表达，而是「文档不存在」（DELETE 恢复默认）。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "保存现场设备卡片配置的请求体")
public class FdCardDto {

    @Schema(description = "要显示的寄存器序号（ModbusRegistry.index），顺序即显示顺序；空清单表示不显示任何寄存器",
            required = false)
    public List<Integer> registers;
}
