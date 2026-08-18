package cc.openxiot.matrix.api.member;

import cc.openxiot.account.user.organization.UserOrganization;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.space.SpaceService;
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
 * 成员关系以"虚拟组织"存储于 account（user-organizations，code = rootSpaceId）。
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

    @Inject
    AccountService accountService;

    /** 添加项目成员（真实组织管理员） */
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
                .chain(space -> accountService.upsertVirtual(rootId, space.name, req.memberId, getUserId())
                        .invoke(ignored -> logger.infov("addMember, root: {0}, member: {1}, creator: {2}",
                                rootId, req.memberId, getUserId()))
                        .replaceWith(OxResponse.ok()))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 移除项目成员（真实组织管理员） */
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
                .chain(space -> accountService.deleteVirtual(rootId, memberId)
                        .invoke(ignored -> logger.infov("removeMember, root: {0}, member: {1}", rootId, memberId))
                        .replaceWith(OxResponse.ok()))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 列项目成员（真实组织成员可见） */
    @GET
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listMembers(
            @PathParam("rootId") String rootId
    ) {
        return requireRootSpaceMember(rootId, getUserId())
                .chain(space -> UserOrganization.<UserOrganization>findById(rootId)
                        .map(org -> {
                            List<?> members = org == null ? List.of() : org.members;
                            return OxResponse.ok(members);
                        }))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    private Uni<SpaceEntity> requireRootSpaceAdmin(String rootId, String accountId) {
        return service.getById(rootId)
                .chain(Unchecked.function(space -> {
                    if (space == null || space.parentId != null) {
                        throw new IllegalArgumentException("Root space not found: " + rootId);
                    }
                    return orgAccessService.checkAdmin(space.organizationId, accountId)
                            .map(ignored -> space);
                }));
    }

    private Uni<SpaceEntity> requireRootSpaceMember(String rootId, String accountId) {
        return service.getById(rootId)
                .chain(Unchecked.function(space -> {
                    if (space == null || space.parentId != null) {
                        throw new IllegalArgumentException("Root space not found: " + rootId);
                    }
                    // 项目成员列表对两类人可见：真实组织成员，或项目（虚拟组织）成员
                    return orgAccessService.checkProjectMember(space.organizationId, rootId, accountId)
                            .map(ignored -> space);
                }));
    }
}
