package cc.openxiot.matrix.api.dashboard.web.params;

import cc.openxiot.matrix.db.dashboard.web.widget.WebDashboardWidget;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存布局的请求体（{@code PUT /matrix/v1/dashboard/web/layout/{spaceId}}）。
 *
 * <p><b>为什么不直接绑实体</b>：实体上还有 {@code spaceId} / {@code creator} / {@code updater}
 * 这些只能由服务端定的字段，让客户端提交它们既是多余的攻击面，也容易写出「前端传了别人的 spaceId」
 * 这种要靠额外校验兜住的 bug。<b>spaceId 一律取自路径</b>，人事信息取自 JWT。</p>
 *
 * <p><b>{@code version} 是必填的 {@code Long} 而不是 {@code long}</b>：这样能区分
 * 「没传」（客户端 bug，报错）与「传了 0」（从未保存过，即预置布局的版本号）—— 后者是
 * 首次保存的正常姿势。若用基本类型，漏传会被静默当成 0，把一个客户端 bug 变成一次
 * 「恰好成功」的覆盖写。</p>
 *
 * <p>widgets 直接复用实体的 {@link WebDashboardWidget}，不另建一套 DTO：它的形状就是线上形状
 * （config 是 {@code Map<String,Object>}，没有需要屏蔽的内部字段）。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "保存看板布局的请求体")
public class WebDashboardLayoutDto {

    @Schema(description = "客户端读到的版本号，原样回传；与服务端不一致时报错（乐观锁）。"
            + "从未保存过时传 0", examples = {"0"}, required = true)
    public Long version;

    @Schema(description = "卡片列表（整体替换，不是增量）", required = true)
    public List<WebDashboardWidget> widgets = new ArrayList<>();
}
