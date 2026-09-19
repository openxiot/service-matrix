package cc.openxiot.matrix.api.dashboard.mobile;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.dashboard.DashboardAccess;
import cc.openxiot.matrix.api.dashboard.mobile.layout.MobileDashboardLayoutCodec;
import cc.openxiot.matrix.api.dashboard.mobile.layout.MobileDashboardLayoutService;
import cc.openxiot.matrix.api.dashboard.mobile.layout.MobileDashboardLayoutValidator;
import cc.openxiot.matrix.api.dashboard.mobile.params.MobileDashboardLayoutDto;
import cc.openxiot.matrix.api.dashboard.mobile.params.MobileDashboardRenderRequest;
import cc.openxiot.matrix.api.dashboard.mobile.widget.MobileDashboardWidgetStructureValidator;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetDataCodec;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetDataService;
import cc.openxiot.matrix.db.dashboard.DashboardWidget;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * 移动端自定义数据看板（iPhone & Android，竖屏）：布局的读写 + 一屏的批量取数。
 *
 * <p>镜像 {@code WebDashboardResource}，且<b>刻意保持同一套权限与取数口径</b>：
 * <ul>
 *   <li><b>读写不对称，与 Web 逐字一致</b>：布局是**项目共享一份**，读是空间成员（只读看板人人可见），
 *       写是空间管理员。鉴权共用 {@link DashboardAccess}（同一个 helper，两种看板不可能分叉）。</li>
 *   <li><b>取数走同一个共享引擎</b> {@link DashboardWidgetDataService}：移动端卡片与 Web 卡片是同一种
 *       东西（同一 {@link DashboardWidget} 实体、同一 config 语义），取数结果也复用同一份
 *       {@link DashboardWidgetDataCodec}。移动端布局只是另一种实体（竖排堆叠、{@code FULL/HALF} 档位、
 *       无坐标），喂给同一个引擎。</li>
 * </ul>
 *
 * <p>{@code render} 是成员可用的，包括请求体里带**未保存草稿**的那种（预览）：只读，且归属校验照做
 * （{@code DashboardWidgetOwnershipValidator}），所以成员预览不了别人的卡片。</p>
 */
@Path("/matrix/v1/dashboard/mobile")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "MobileDashboard", description = "移动端看板布局与卡片数据 API")
@RequestScoped
public class MobileDashboardResource extends AbstractResource {

    @Inject
    DashboardAccess access;

    @Inject
    MobileDashboardLayoutService layoutService;

    @Inject
    MobileDashboardLayoutValidator layoutValidator;

    @Inject
    DashboardWidgetDataService widgetDataService;

    @GET
    @Path("/layout/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> layout(@PathParam("spaceId") String spaceId) {
        return access.spaceMember(spaceId)
                // 没有文档时返回内存生成的预置布局（不落库），不报 404
                .chain(space -> layoutService.load(space))
                .map(layout -> OxResponse.ok(MobileDashboardLayoutCodec.encode(layout)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/layout/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> save(@PathParam("spaceId") String spaceId, MobileDashboardLayoutDto body) {
        Person who = currentUser();
        return access.spaceAdmin(spaceId)
                // 校验要拿到 space（归属判定按根空间），所以校验完把 space 继续往下传
                .chain(space -> layoutValidator.validate(space, body).map(ignored -> space))
                .chain(space -> layoutService.save(space, body, who))
                .map(layout -> OxResponse.ok(MobileDashboardLayoutCodec.encode(layout)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 预置布局（只读，无副作用）。「恢复默认」拿它当草稿的起点。 */
    @GET
    @Path("/layout/{spaceId}/preset")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> preset(@PathParam("spaceId") String spaceId) {
        return access.spaceMember(spaceId)
                .map(space -> OxResponse.ok(
                        MobileDashboardLayoutCodec.encode(layoutService.preset(space))))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/render/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> render(@PathParam("spaceId") String spaceId, MobileDashboardRenderRequest body) {
        List<DashboardWidget> draft = body == null ? null : body.widgets;
        return access.spaceMember(spaceId)
                .chain(space -> draft == null
                        ? renderStored(space)
                        : failFast(MobileDashboardWidgetStructureValidator.validateWidgets(draft))
                        .chain(ignored -> widgetDataService.render(space, draft)))
                .map(data -> OxResponse.ok(DashboardWidgetDataCodec.encode(data)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 不传草稿时用**已存布局**。这里再读一次布局（前端手里那份可能是几分钟前 GET 的）。 */
    private Uni<DashboardWidgetDataService.WidgetData> renderStored(SpaceEntity space) {
        return layoutService.load(space)
                .chain(layout -> widgetDataService.render(space, layout.widgets));
    }

    private static Uni<Void> failFast(String error) {
        return error == null
                ? Uni.createFrom().voidItem()
                : Uni.createFrom().failure(new IllegalArgumentException(error));
    }
}