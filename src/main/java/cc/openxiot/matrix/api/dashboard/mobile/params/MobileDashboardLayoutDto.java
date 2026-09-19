package cc.openxiot.matrix.api.dashboard.mobile.params;

import cc.openxiot.matrix.db.dashboard.DashboardWidget;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存移动端布局的请求体（{@code PUT /matrix/v1/dashboard/mobile/layout/{spaceId}}）。
 *
 * <p>形状与 Web 的 {@code WebDashboardLayoutDto} 逐字一致，理由也一致：</p>
 * <ul>
 *   <li><b>不直接绑实体</b>：实体上还有 {@code spaceId}/{@code creator}/{@code updater} 这些只能由
 *       服务端定的字段，让客户端提交它们是多余的攻击面。<b>spaceId 一律取自路径</b>，人事信息取自 JWT。</li>
 *   <li><b>{@code version} 是必填的 {@code Long} 而不是 {@code long}</b>：区分「没传」（客户端 bug，报错）
 *       与「传了 0」（从未保存过，即预置布局）—— 否则漏传被静默当 0，把一个 bug 变成一次「恰好成功」的覆盖写。</li>
 *   <li>widgets 直接复用实体 {@link DashboardWidget}，不另建 DTO：它的形状就是线上形状。</li>
 * </ul>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "保存移动端看板布局的请求体")
public class MobileDashboardLayoutDto {

    @Schema(description = "客户端读到的版本号，原样回传；与服务端不一致时报错（乐观锁）。"
            + "从未保存过时传 0", examples = {"0"}, required = true)
    public Long version;

    @Schema(description = "卡片列表（整体替换，不是增量）；数组顺序即竖屏阅读顺序", required = true)
    public List<DashboardWidget> widgets = new ArrayList<>();
}