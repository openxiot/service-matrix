package cc.openxiot.site.api.space;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.site.api.device.DeviceService;
import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.space.SpaceEntity;
import cc.openxiot.site.role.SiteRole;
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

    @GET
    public Response hello() {
        logger.info("hello");
        return OxResponse.ok("Hello from SpaceResource");
    }

    @POST
    @Path("/one")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> addOne(SpaceEntity space) {
        String userId = getUserId();

        Uni<Boolean> accessCheck;
        if (space.parentId != null && !space.parentId.isBlank()) {
            accessCheck = service.noAccess(space.parentId, userId);
        } else {
            accessCheck = Uni.createFrom().item(false);
        }

        return accessCheck.flatMap(noAccess -> {
            if (noAccess) {
                return Uni.createFrom().item(OxResponse.error("No access to parent space"));
            }
            return service.create(space, userId)
                    .map(OxResponse::ok)
                    .onFailure(IllegalArgumentException.class)
                    .recoverWithItem(e -> OxResponse.error(e.getMessage()));
        });
    }

    @DELETE
    @Path("/one/{spaceId}")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Uni<Response> removeOne(
            @PathParam("spaceId") String spaceId
    ) {
        String userId = getUserId();

        return service.noAccess(spaceId, userId)
                .flatMap(noAccess -> {
                    if (noAccess) {
                        return Uni.createFrom().item(OxResponse.error("No access to this space"));
                    }
                    return service.delete(spaceId)
                            .replaceWith(OxResponse.ok())
                            .onFailure(IllegalArgumentException.class)
                            .recoverWithItem(e -> OxResponse.error(e.getMessage()));
                });
    }

    @PUT
    @Path("/one")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> updateOne(SpaceEntity space) {
        String userId = getUserId();

        if (space.id == null) {
            return Uni.createFrom().item(OxResponse.error("Space id is required"));
        }

        return service.noAccess(space.id.toString(), userId)
                .flatMap(noAccess -> {
                    if (noAccess) {
                        return Uni.createFrom().item(OxResponse.error("No access to this space"));
                    }
                    return service.update(space)
                            .map(OxResponse::ok)
                            .onFailure(IllegalArgumentException.class)
                            .recoverWithItem(e -> OxResponse.error(e.getMessage()));
                });
    }

    @GET
    @Path("/one/{spaceId}")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Uni<Response> getOne(
            @PathParam("spaceId") String spaceId
    ) {
        String userId = getUserId();

        return service.noAccess(spaceId, userId)
                .flatMap(noAccess -> {
                    if (noAccess) {
                        return Uni.createFrom().item(OxResponse.error("No access to this space"));
                    }
                    return service.getById(spaceId)
                            .map(space -> {
                                if (space == null) {
                                    return OxResponse.error("Space not found");
                                }
                                return OxResponse.ok(space);
                            });
                });
    }

    @GET
    @Path("/all")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Uni<Response> getAll() {
        String userId = getUserId();
        return service.getRootsByUserId(userId)
                .map(OxResponse::ok);
    }

    @GET
    @Path("/tree/{rootId}")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Uni<Response> getTree(
            @PathParam("rootId") String rootId
    ) {
        String userId = getUserId();

        return service.noAccess(rootId, userId)
                .flatMap(noAccess -> {
                    if (noAccess) {
                        return Uni.createFrom().item(OxResponse.error("No access to this space"));
                    }
                    return service.getDescendants(rootId)
                            .map(OxResponse::ok);
                });
    }

    @GET
    @Path("/graph/{rootId}")
    public Uni<Response> getGraph(
            @PathParam("rootId") String rootId
    ) {
        String userId = getUserId();

        return service.noAccess(rootId, userId)
                .flatMap(noAccess -> {
                    if (noAccess) {
                        return Uni.createFrom().item(OxResponse.error("No access to this space"));
                    }
                    return service.getDescendants(rootId)
                            .flatMap(spaces -> {
                                List<String> spaceIds = spaces.stream()
                                        .map(s -> s.id.toString())
                                        .toList();
                                List<Uni<List<DeviceEntity>>> deviceUnis = spaceIds.stream()
                                        .map(deviceService::getBySpaceId)
                                        .toList();
                                return Uni.join().all(deviceUnis).andFailFast()
                                        .map(deviceLists -> {
                                            List<DeviceEntity> allDevices = deviceLists.stream()
                                                    .flatMap(List::stream)
                                                    .toList();
                                            Map<String, Object> graph = new HashMap<>();
                                            graph.put("spaces", spaces);
                                            graph.put("devices", allDevices);
                                            return OxResponse.ok(graph);
                                        });
                            });
                });
    }
}
