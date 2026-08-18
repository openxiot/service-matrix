package cc.openxiot.matrix.api.member;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.space.SpaceOwnerType;
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
 * 项目成员管理：给项目（根空间）添加/移除成员。
 * 成员关系直接存于矩阵空间的 owners 列表（type = user 条目）。
 */
@Path("/matrix/v1/space/{rootId}/member")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Member", description = "Project member API")
@RequestScoped
public class MemberResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    SpaceService service;

    @Inject
    OrgAccessService orgAccessService;

    /** 添加项目成员（空间管理员：用户管理员或组织管理员） */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> addMember(
            @PathParam("rootId") String rootId,
            MemberAddRequest req
    ) {
        if (req == null || req.memberId == null || req.memberId.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("memberId is required"));
        }

        return requireRootSpaceAdmin(rootId, getUserId())
                .chain(space -> service.addMember(rootId, req.memberId, req.role, req.remark))
                .invoke(ignored -> logger.infov("addMember, root: {0}, member: {1}, role: {2}",
                        rootId, req.memberId, req.role))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 移除项目成员（空间管理员） */
    @DELETE
    @RolesAllowed({OxRole.USER})
    public Uni<Response> removeMember(
            @PathParam("rootId") String rootId,
            @QueryParam("memberId") String memberId
    ) {
        if (memberId == null || memberId.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("memberId is required"));
        }

        return requireRootSpaceAdmin(rootId, getUserId())
                .chain(space -> service.removeMember(rootId, memberId))
                .invoke(ignored -> logger.infov("removeMember, root: {0}, member: {1}", rootId, memberId))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 列项目成员（空间成员：用户成员或组织成员） */
    @GET
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listMembers(
            @PathParam("rootId") String rootId
    ) {
        return requireRootSpaceMember(rootId, getUserId())
                .map(space -> OxResponse.ok(memberViews(space)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helpers --

    private List<MemberView> memberViews(SpaceEntity space) {
        if (space.owners == null) {
            return List.of();
        }
        return space.owners.stream()
                .filter(o -> SpaceOwnerType.USER.equals(o.type))
                .map(o -> {
                    MemberView view = new MemberView();
                    view.userId = o.id;
                    view.name = o.name;
                    view.role = o.role;
                    view.remark = o.remark;
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
                    return orgAccessService.checkRootSpaceAdmin(space, accountId)
                            .map(ignored -> space);
                }));
    }

    private Uni<SpaceEntity> requireRootSpaceMember(String rootId, String accountId) {
        return service.getById(rootId)
                .chain(Unchecked.function(space -> {
                    if (space == null || space.parentId != null) {
                        throw new IllegalArgumentException("Root space not found: " + rootId);
                    }
                    return orgAccessService.checkRootSpaceMember(space, rootId, accountId)
                            .map(ignored -> space);
                }));
    }
}
