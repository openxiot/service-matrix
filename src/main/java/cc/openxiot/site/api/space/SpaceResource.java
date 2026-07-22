package cc.openxiot.site.api.space;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.site.api.device.DeviceService;
import cc.openxiot.site.api.orgcheck.OrgAccessService;
import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.space.SpaceEntity;
import io.smallrye.mutiny.Uni;
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

@Path("/v1/space")
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

    @GET
    public Uni<Response> hello() {
        logger.info("hello");
        return Uni.createFrom().item(OxResponse.ok("Hello from SpaceResource"));
    }

    @POST
    @Path("/one")
    @RolesAllowed({OxRole.DEVELOPER})
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
                .chain(ignored -> service.create(space).map(OxResponse::ok))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @DELETE
    @Path("/one/{spaceId}")
    @RolesAllowed({OxRole.DEVELOPER})
    public Uni<Response> removeOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId
    ) {
        return getSpaceIfAdmin(spaceId, orgId)
                .chain(ignored -> service.delete(spaceId))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/one")
    @RolesAllowed({OxRole.DEVELOPER})
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> updateOne(
            @HeaderParam("X-Org-Id") String orgId,
            SpaceEntity space
    ) {
        if (space.id == null) {
            return Uni.createFrom().item(OxResponse.error("Space id is required"));
        }

        return getSpaceIfAdmin(space.id.toString(), orgId)
                .chain(ignored -> service.update(space))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/one/{spaceId}")
    @RolesAllowed({OxRole.DEVELOPER})
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
    @RolesAllowed({OxRole.DEVELOPER})
    public Uni<Response> getAll(
            @HeaderParam("X-Org-Id") String orgId
    ) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("X-Org-Id header is required"));
        }

        return orgAccessService.checkMember(orgId, getUserId())
                .chain(ignored -> service.getRootsByOrganizationId(orgId))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/tree/{rootId}")
    @RolesAllowed({OxRole.DEVELOPER})
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
    @RolesAllowed({OxRole.DEVELOPER})
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
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("Space not found: " + spaceId);
                    }

                    if (!orgId.equals(space.organizationId)) {
                        throw new IllegalArgumentException("OrgId does not match the space");
                    }

                    return orgAccessService.checkMember(orgId, getUserId())
                            .map(ignored -> space);
                });
    }

    private Uni<SpaceEntity> getSpaceIfAdmin(String spaceId, String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }

        return service.getById(spaceId)
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("Space not found: " + spaceId);
                    }

                    if (!orgId.equals(space.organizationId)) {
                        throw new IllegalArgumentException("OrgId does not match the space");
                    }

                    return orgAccessService.checkAdmin(orgId, getUserId())
                            .map(ignored -> space);
                });
    }
}
