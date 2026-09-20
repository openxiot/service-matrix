package cc.openxiot.matrix.api.dashboard.web.params;

import cc.openxiot.matrix.db.dashboard.web.WebDashboardWidget;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * 批量取数的请求体（{@code POST /matrix/v1/dashboard/web/render/{spaceId}}）。
 *
 * <p><b>{@code widgets} 可以不传</b>：不传 = 用**已存布局**（正式渲染），传了 = 用这份
 * 尚未保存的草稿（编辑器预览）。这个设计让编辑器不必先保存再预览，也让「预览」与
 * 「正式渲染」走的是同一条取数路径 —— 两套路径迟早会渲染出不一样的东西，
 * 而那时候用户看到的「预览」就不再是承诺。</p>
 *
 * <p>草稿是**成员可用的**（§6.2）：{@code render} 只读，且归属校验照做
 * （{@code DashboardWidgetOwnershipValidator}），所以成员预览不了别人的卡片，这不构成漏洞。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "批量取数的请求体；不传 widgets 则用已保存的布局")
public class WebDashboardRenderRequest {

    @Schema(description = "尚未保存的卡片草稿（编辑器预览用）；不传则渲染已保存的布局")
    public List<WebDashboardWidget> widgets;
}
