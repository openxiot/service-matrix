package cc.openxiot.matrix.api.space.access;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * 根空间访问条目管理：给根空间添加/移除用户访问条目（成员）。
 * 访问条目直接存于矩阵空间的 accesses 列表（type = user 条目）。
 */
@Path("/matrix/v1/space/{rootId}/access")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "SpaceAccess", description = "Root space access / member API")
@RequestScoped
public class SpaceAccessResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    SpaceService service;

    @Inject
    OrgAccessService orgAccessService;

    /** 添加根空间用户访问条目（空间管理员：用户管理员或组织管理员） */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> addAccess(
            @PathParam("rootId") String rootId,
            SpaceAccessAddRequest req
    ) {
        if (req == null || req.memberId == null || req.memberId.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("memberId is required"));
        }

        return requireRootSpaceAdmin(rootId, getUserId())
                .chain(space -> service.addAccess(rootId, req.memberId, req.role, req.remark))
                .invoke(ignored -> logger.infov("addAccess, root: {0}, member: {1}, role: {2}",
                        rootId, req.memberId, req.role))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 调整成员角色 / 备注（空间管理员，两者至少提供其一） */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> updateAccess(
            @PathParam("rootId") String rootId,
            SpaceAccessAddRequest req
    ) {
        if (req == null || req.memberId == null || req.memberId.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("memberId is required"));
        }
        boolean roleProvided = req.role != null && !req.role.isBlank();
        boolean remarkProvided = req.remark != null;
        if (!roleProvided && !remarkProvided) {
            return Uni.createFrom().item(OxResponse.error("role or remark is required"));
        }

        return requireRootSpaceAdmin(rootId, getUserId())
                .chain(space -> {
                    Uni<Void> result = Uni.createFrom().voidItem();
                    if (roleProvided) {
                        result = result.chain(ignored -> service.updateAccessRole(rootId, req.memberId, req.role));
                    }
                    if (remarkProvided) {
                        result = result.chain(ignored -> service.updateAccessRemark(rootId, req.memberId, req.remark));
                    }
                    return result;
                })
                .invoke(ignored -> logger.infov("updateAccess, root: {0}, member: {1}, role: {2}, remark: {3}",
                        rootId, req.memberId, req.role, req.remark))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 移除根空间用户访问条目：管理员移除其他成员，或成员本人退出项目 */
    @DELETE
    @RolesAllowed({OxRole.USER})
    public Uni<Response> removeAccess(
            @PathParam("rootId") String rootId,
            @QueryParam("memberId") String memberId
    ) {
        if (memberId == null || memberId.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("memberId is required"));
        }

        return requireRootSpaceAdminOrSelf(rootId, getUserId(), memberId)
                .chain(space -> service.removeAccess(rootId, memberId))
                .invoke(ignored -> logger.infov("removeAccess, root: {0}, member: {1}", rootId, memberId))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 列根空间用户访问条目（空间成员：用户成员或组织成员） */
    @GET
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listAccesses(
            @PathParam("rootId") String rootId
    ) {
        return requireRootSpaceMember(rootId, getUserId())
                .map(space -> OxResponse.ok(accessViews(space)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helpers --

    private List<SpaceAccessView> accessViews(SpaceEntity space) {
        if (space.accesses == null) {
            return List.of();
        }
        return space.accesses.stream()
                .filter(a -> SpaceAccessType.USER.equals(a.type))
                .map(a -> {
                    SpaceAccessView view = new SpaceAccessView();
                    view.userId = a.id;
                    view.name = a.name;
                    view.role = a.role;
                    view.remark = a.remark;
                    return view;
                })
                .toList();
    }

    private Uni<SpaceEntity> requireRootSpaceAdmin(String rootId, String accountId) {
        return service.getById(rootId)
                .chain(Unchecked.function(space -> {
                    if (space == null || space.parentId != null) {
                        throw new IllegalArgumentException("Root space not found: " + rootId);
                    }
                    // 与其余空间接口用的是同一个判定：组织从空间自己的 accesses 读。
                    // （原先这里单独有一份 checkRootSpaceAdmin —— 差别只在「组织怎么挑」，
                    // 那份不看请求头，其余接口那份看，已合并，见 OrgAccessService。）
                    return orgAccessService.checkSpaceAdmin(space, accountId)
                            .map(ignored -> space);
                }));
    }

    private Uni<SpaceEntity> requireRootSpaceMember(String rootId, String accountId) {
        return service.getById(rootId)
                .chain(Unchecked.function(space -> {
                    if (space == null || space.parentId != null) {
                        throw new IllegalArgumentException("Root space not found: " + rootId);
                    }
                    return orgAccessService.checkSpaceMember(space, accountId)
                            .map(ignored -> space);
                }));
    }

    /** 管理员可操作任意成员；成员本人可退出项目（需项目成员权限）。 */
    private Uni<SpaceEntity> requireRootSpaceAdminOrSelf(String rootId, String accountId, String memberId) {
        return service.getById(rootId)
                .chain(Unchecked.function(space -> {
                    if (space == null || space.parentId != null) {
                        throw new IllegalArgumentException("Root space not found: " + rootId);
                    }
                    if (memberId.equals(accountId)) {
                        return orgAccessService.checkSpaceMember(space, accountId)
                                .map(ignored -> space);
                    }
                    return orgAccessService.checkSpaceAdmin(space, accountId)
                            .map(ignored -> space);
                }));
    }
}
