package cc.openxiot.site.api.space;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.space.SpaceEntity;
import cc.openxiot.site.role.SiteRole;
import cc.openxiot.site.api.device.DeviceService;
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
    public Response addOne(SpaceEntity space) {
        String userId = getUserId();

        if (space.parentId != null && !space.parentId.isBlank()) {
            if (service.noAccess(space.parentId, userId)) {
                return OxResponse.error("No access to parent space");
            }
        }

        try {
            SpaceEntity created = service.create(space, userId);
            return OxResponse.ok(created);
        } catch (IllegalArgumentException e) {
            return OxResponse.error(e.getMessage());
        }
    }

    @DELETE
    @Path("/one/{spaceId}")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Response removeOne(
            @PathParam("spaceId") String spaceId
    ) {
        String userId = getUserId();

        if (service.noAccess(spaceId, userId)) {
            return OxResponse.error("No access to this space");
        }

        try {
            service.delete(spaceId);
            return OxResponse.ok();
        } catch (IllegalArgumentException e) {
            return OxResponse.error(e.getMessage());
        }
    }

    @PUT
    @Path("/one")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateOne(SpaceEntity space) {
        String userId = getUserId();

        if (space.id == null) {
            return OxResponse.error("Space id is required");
        }

        if (service.noAccess(space.id.toString(), userId)) {
            return OxResponse.error("No access to this space");
        }

        try {
            SpaceEntity updated = service.update(space);
            return OxResponse.ok(updated);
        } catch (IllegalArgumentException e) {
            return OxResponse.error(e.getMessage());
        }
    }

    @GET
    @Path("/one/{spaceId}")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Response getOne(
            @PathParam("spaceId") String spaceId
    ) {
        String userId = getUserId();

        if (service.noAccess(spaceId, userId)) {
            return OxResponse.error("No access to this space");
        }

        SpaceEntity space = service.getById(spaceId);
        if (space == null) {
            return OxResponse.error("Space not found");
        }
        return OxResponse.ok(space);
    }

    @GET
    @Path("/all")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Response getAll() {
        String userId = getUserId();
        List<SpaceEntity> roots = service.getRootsByUserId(userId);
        return OxResponse.ok(roots);
    }

    @GET
    @Path("/tree/{rootId}")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Response getTree(
            @PathParam("rootId") String rootId
    ) {
        String userId = getUserId();

        if (service.noAccess(rootId, userId)) {
            return OxResponse.error("No access to this space");
        }

        List<SpaceEntity> spaces = service.getDescendants(rootId);
        return OxResponse.ok(spaces);
    }

    @GET
    @Path("/graph/{rootId}")
    public Response getGraph(
            @PathParam("rootId") String rootId
    ) {
        String userId = getUserId();

        if (service.noAccess(rootId, userId)) {
            return OxResponse.error("No access to this space");
        }

        List<SpaceEntity> spaces = service.getDescendants(rootId);
        List<String> spaceIds = spaces.stream().map(s -> s.id.toString()).toList();

        List<DeviceEntity> devices = spaceIds.stream()
                .flatMap(sid -> deviceService.getBySpaceId(sid).stream())
                .toList();

        Map<String, Object> graph = new HashMap<>();
        graph.put("spaces", spaces);
        graph.put("devices", devices);

        return OxResponse.ok(graph);
    }
}
