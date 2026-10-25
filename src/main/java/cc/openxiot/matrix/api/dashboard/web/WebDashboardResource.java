package cc.openxiot.matrix.api.dashboard.web;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.dashboard.DashboardAccess;
import cc.openxiot.matrix.api.dashboard.web.catalog.WebDashboardCatalogService;
import cc.openxiot.matrix.api.dashboard.web.layout.WebDashboardLayoutCodec;
import cc.openxiot.matrix.api.dashboard.web.layout.WebDashboardLayoutService;
import cc.openxiot.matrix.api.dashboard.web.layout.WebDashboardLayoutValidator;
import cc.openxiot.matrix.api.dashboard.web.params.WebDashboardLayoutDto;
import cc.openxiot.matrix.api.dashboard.web.params.WebDashboardRenderRequest;
import cc.openxiot.matrix.api.dashboard.web.widget.WebDashboardWidgetStructureValidator;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetDataCodec;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetDataService;
import cc.openxiot.matrix.db.dashboard.web.WebDashboardWidget;
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
 * 自定义数据看板：布局的读写 + 一屏的批量取数。
 *
 * <p>读写**不对称**（§6.2，用户确认）：布局是**空间共享一份**，读是空间成员（只读看板人人可见），
 * 写是空间管理员（改布局影响所有人）。DTO 里因此**不放 `editable`** —— 前端本来就能算：
 * 页面已有的 {@code isAdmin} 惯用法（先看自己在空间成员里的 role，再看所属组织在根空间
 * {@code accesses} 里的 role）就是用在这里的。</p>
 *
 * <p>{@code render} 是成员可用的，包括请求体里带**未保存草稿**的那种（编辑器预览）：
 * 它只读，且归属校验照做，所以成员预览不了别人的卡片（§6.2）。</p>
 *
 * <p>鉴权（空间成员读、管理员写）共用 {@link DashboardAccess} —— 与移动端看板
 * {@code MobileDashboardResource} 同一个 helper，两种看板的权限口径不可能分叉。
 * 「卡片配置本身合不合法」是 {@link WebDashboardWidgetStructureValidator} /
 * {@link WebDashboardLayoutValidator} 的事 —— 两者分开，校验器才能被成员可用的 {@code render} 复用。</p>
 */
@Path("/matrix/v1/dashboard/web")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "WebDashboard", description = "自定义数据看板布局与卡片数据 API")
@RequestScoped
public class WebDashboardResource extends AbstractResource {

    @Inject
    DashboardAccess access;

    @Inject
    WebDashboardLayoutService layoutService;

    @Inject
    WebDashboardLayoutValidator layoutValidator;

    @Inject
    DashboardWidgetDataService widgetDataService;

    @Inject
    WebDashboardCatalogService catalogService;

    @GET
    @Path("/layout/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> layout(@PathParam("spaceId") String spaceId) {
        return access.spaceMember(spaceId)
                // 没有文档时返回内存生成的预置布局（§6.5），不报 404、不落库
                .chain(space -> layoutService.load(space))
                .map(layout -> OxResponse.ok(WebDashboardLayoutCodec.encode(layout)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/layout/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> save(@PathParam("spaceId") String spaceId, WebDashboardLayoutDto body) {
        // 与 ModbusDeviceResource.createOne 一样在链外取：链上的回调可能已经不在请求线程上，
        // 那时安全上下文（当前用户）不一定还在
        Person who = currentUser();
        return access.spaceAdmin(spaceId)
                // 校验要拿到 space（归属判定按根空间），所以校验完把 space 继续往下传
                .chain(space -> layoutValidator.validate(space, body).map(ignored -> space))
                .chain(space -> layoutService.save(space, body, who))
                .map(layout -> OxResponse.ok(WebDashboardLayoutCodec.encode(layout)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 预置布局（只读，无副作用）。「恢复默认」拿它当草稿的起点。
     *
     * <p><b>所以这是个 GET 而不是 DELETE</b>（改造前是 DELETE：点一下就把库里那份删了）。
     * 「恢复默认」现在是**纯前端的一次草稿替换**：装进草稿、标脏，用户再点「保存布局」才落库 ——
     * 在那之前「退出编辑」就等于没发生过。后端因此只提供这一份「预置长什么样」的答案，
     * 由前端决定要不要用它、什么时候写下去。</p>
     *
     * <p>权限与读布局相同（空间成员）：它没有任何副作用，而「预置布局是什么样」本来就是
     * 每个人打开这个页面都会看到的公开信息。</p>
     */
    @GET
    @Path("/layout/{spaceId}/preset")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> preset(@PathParam("spaceId") String spaceId) {
        return access.spaceMember(spaceId)
                .map(space -> OxResponse.ok(WebDashboardLayoutCodec.encode(layoutService.preset(space))))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/render/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> render(@PathParam("spaceId") String spaceId, WebDashboardRenderRequest body) {
        List<WebDashboardWidget> draft = body == null ? null : body.widgets;
        return access.spaceMember(spaceId)
                .chain(space -> draft == null
                        ? renderStored(space)
                        // 草稿的**结构**错误整份拒绝：body 不成形时连「有哪些卡片」都无从谈起，
                        // 报第一处错比逐张标红更好定位（归属错误则相反，逐张隔离，见 build）
                        : failFast(WebDashboardWidgetStructureValidator.validateWidgets(draft))
                        .chain(ignored -> widgetDataService.render(space, draft)))
                .map(data -> OxResponse.ok(DashboardWidgetDataCodec.encode(data)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 编辑器要的候选清单：本项目的设备与服务（含完整服务定义）。
     *
     * <p>成员可读，与 {@link #layout} 同口径：清单里只有**他已经能看见**的设备与服务
     * （同一子树），所以这不是一处新的信息暴露面 —— 他只是省掉了手抄 id。</p>
     */
    @GET
    @Path("/catalog/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> catalog(@PathParam("spaceId") String spaceId) {
        return access.spaceMember(spaceId)
                .chain(catalogService::catalog)
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 不传草稿时用**已存布局**。这里再读一次布局，而不是让前端总是把 widgets 传上来：
     * 前端手里那份可能是几分钟前 GET 的，而「看板自己刷新」应该取当前布局
     * （否则管理员刚改完，别人的页面上还是旧卡片）。
     */
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
