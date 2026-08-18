package cc.openxiot.matrix.api.space;

import cc.openxiot.account.user.organization.UserOrganization;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.device.DeviceService;
import cc.openxiot.matrix.api.member.AccountService;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.space.SpaceEntity;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/matrix/v1/space")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Space", description = "Space API")
@RequestScoped
public class SpaceResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    SpaceService service;

    @Inject
    DeviceService deviceService;

    @Inject
    OrgAccessService orgAccessService;

    @Inject
    AccountService accountService;

    @GET
    public Uni<Response> hello() {
        logger.info("hello");
        return Uni.createFrom().item(OxResponse.ok("Hello from SpaceResource"));
    }

    @POST
    @Path("/one")
    @RolesAllowed({OxRole.USER})
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> addOne(
            @HeaderParam("X-Org-Id") String orgId,
            SpaceEntity space
    ) {
        if (space.parentId != null && !space.parentId.isBlank()) {
            return getSpaceIfAdmin(space.parentId, orgId)
                    .chain(ignored -> service.create(space).map(OxResponse::ok))
                    .onFailure(IllegalArgumentException.class)
                    .recoverWithItem(e -> OxResponse.error(e.getMessage()));
        }

        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("X-Org-Id header is required for root space"));
        }

        space.organizationId = orgId;

        return orgAccessService.checkAdmin(orgId, getUserId())
                .chain(() -> orgAccessService.isVirtual(orgId))
                .invoke(Unchecked.consumer(virtual -> {
                    if (virtual) {
                        // 虚拟组织下不能创建根空间，保证一个虚拟组织 = 一个项目
                        throw new IllegalArgumentException("虚拟组织下不能创建根空间");
                    }
                }))
                .chain(ignored -> service.create(space).map(OxResponse::ok))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @DELETE
    @Path("/one/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> removeOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId
    ) {
        return getSpaceIfAdmin(spaceId, orgId)
                .chain(existing -> service.delete(spaceId).chain(deleted -> cleanupVirtualOrg(existing)))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/one")
    @RolesAllowed({OxRole.USER})
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> updateOne(
            @HeaderParam("X-Org-Id") String orgId,
            SpaceEntity space
    ) {
        if (space.id == null) {
            return Uni.createFrom().item(OxResponse.error("Space id is required"));
        }

        return getSpaceIfAdmin(space.id.toString(), orgId)
                .chain(existing -> service.update(space).chain(updated -> syncVirtualOrgName(existing, space.name)))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/one/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId
    ) {
        return getSpaceIfMember(spaceId, orgId)
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/all")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getAll(
            @HeaderParam("X-Org-Id") String orgId
    ) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("X-Org-Id header is required"));
        }

        return orgAccessService.checkMember(orgId, getUserId())
                .chain(() -> orgAccessService.isVirtual(orgId))
                .chain(virtual -> virtual
                        ? service.getRootsById(List.of(orgId))
                        : service.getRootsByOrganizationId(orgId))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/tree/{rootId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getTree(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("rootId") String rootId
    ) {
        return getSpaceIfMember(rootId, orgId)
                .chain(ignored -> service.getDescendants(rootId))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/graph/{rootId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getGraph(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("rootId") String rootId
    ) {
        return getSpaceIfMember(rootId, orgId)
                .chain(ignored -> service.getDescendants(rootId))
                .chain(spaces -> {
                    List<String> spaceIds = spaces.stream().map(s -> s.id.toString()).toList();
                    List<Uni<List<DeviceEntity>>> deviceUnis = spaceIds.stream().map(deviceService::getBySpaceId).toList();
                    return Uni.join().all(deviceUnis).andFailFast()
                            .map(deviceLists -> {
                                List<DeviceEntity> allDevices = deviceLists.stream().flatMap(List::stream).toList();
                                Map<String, Object> graph = new HashMap<>();
                                graph.put("spaces", spaces);
                                graph.put("devices", allDevices);
                                return OxResponse.ok(graph);
                            });
                })
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    private Uni<SpaceEntity> getSpaceIfMember(String spaceId, String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }

        return service.getById(spaceId)
                .chain(Unchecked.function(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("Space not found: " + spaceId);
                    }

                    return orgAccessService.checkMember(orgId, getUserId())
                            .chain(() -> orgAccessService.isVirtual(orgId))
                            .map(Unchecked.function(virtual -> {
                                if (virtual) {
                                    // 虚拟组织作用域 = 以 orgId 为根的整棵树
                                    boolean inScope = space.id.toString().equals(orgId)
                                            || (space.ancestors != null && space.ancestors.contains(orgId));
                                    if (!inScope) {
                                        throw new IllegalArgumentException("No access to this space");
                                    }
                                } else if (!orgId.equals(space.organizationId)) {
                                    throw new IllegalArgumentException("OrgId does not match the space");
                                }
                                return space;
                            }));
                }));
    }

    private Uni<SpaceEntity> getSpaceIfAdmin(String spaceId, String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }

        return service.getById(spaceId)
                .chain(Unchecked.function(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("Space not found: " + spaceId);
                    }

                    if (!orgId.equals(space.organizationId)) {
                        throw new IllegalArgumentException("OrgId does not match the space");
                    }

                    return orgAccessService.checkAdmin(orgId, getUserId())
                            .map(ignored -> space);
                }));
    }

    /** 根空间改名时同步虚拟组织（成员项目）名；虚拟组织不存在则跳过，避免普通项目改名误创建 */
    private Uni<SpaceEntity> syncVirtualOrgName(SpaceEntity space, String newName) {
        if (space.parentId != null && !space.parentId.isBlank()) {
            return Uni.createFrom().item(space);
        }

        return UserOrganization.<UserOrganization>findById(space.id.toString())
                .chain(org -> {
                    if (org == null || !org.isVirtual()) {
                        return Uni.createFrom().item(space);
                    }
                    return bestEffort(accountService.upsertVirtual(space.id.toString(), newName, null, null),
                            space, "syncVirtualOrgName");
                });
    }

    /** 删除根空间时清理其虚拟组织 */
    private Uni<SpaceEntity> cleanupVirtualOrg(SpaceEntity space) {
        if (space.parentId != null && !space.parentId.isBlank()) {
            return Uni.createFrom().item(space);
        }

        return UserOrganization.<UserOrganization>findById(space.id.toString())
                .chain(org -> {
                    if (org == null || !org.isVirtual()) {
                        return Uni.createFrom().item(space);
                    }
                    return bestEffort(accountService.deleteVirtual(space.id.toString(), null),
                            space, "cleanupVirtualOrg");
                });
    }

    /** 虚拟组织同步尽力而为：业务失败（IllegalArgumentException）记 warning 继续，网络失败照常上抛 */
    private Uni<SpaceEntity> bestEffort(Uni<Void> call, SpaceEntity space, String op) {
        return call
                .onFailure(IllegalArgumentException.class)
                .invoke(e -> logger.warnv("{0} failed: {1}", op, e.getMessage()))
                .onFailure(IllegalArgumentException.class)
                .recoverWithUni(Uni.createFrom().voidItem())
                .replaceWith(space);
    }
}
