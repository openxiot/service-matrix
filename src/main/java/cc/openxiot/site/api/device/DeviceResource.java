package cc.openxiot.site.api.device;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.site.api.orgcheck.OrgAccessService;
import cc.openxiot.site.api.space.SpaceService;
import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.device.DeviceSpace;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@Path("/v1/device")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Device", description = "Device API")
@RequestScoped
public class DeviceResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    DeviceService deviceService;

    @Inject
    SpaceService spaceService;

    @Inject
    ManipulationService manipulationService;

    @Inject
    OrgAccessService orgAccessService;

    @GET
    public Uni<Response> hello() {
        logger.info("hello");
        return Uni.createFrom().item(OxResponse.ok("Hello from DeviceResource"));
    }

    @POST
    @Path("/many/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> addDevices(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            String body
    ) {
        JsonArray arr;
        try {
            arr = new JsonArray(body);
        } catch (Exception e) {
            return Uni.createFrom().item(OxResponse.error("Invalid JSON body"));
        }

        List<DeviceEntity> devices = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.getJsonObject(i);
            DeviceEntity device = new DeviceEntity();
            device.did = obj.getString("did");
            device.type = obj.getString("type");
            DeviceSpace ds = new DeviceSpace();
            ds.spaceId = spaceId;
            device.space = ds;
            devices.add(device);
        }

        return requireAdmin(spaceId, orgId)
                .flatMap(error -> {
                    if (error != null) return Uni.createFrom().item(error);
                    return manipulationService.addDevices(orgId, body)
                            .flatMap(ignore -> deviceService.saveAll(devices))
                            .replaceWith(OxResponse.ok());
                });
    }

    @DELETE
    @Path("/many/{spaceId}")
    public Uni<Response> removeDevices(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @QueryParam("did") List<String> did
    ) {
        return requireAdmin(spaceId, orgId)
                .flatMap(error -> {
                    if (error != null) return Uni.createFrom().item(error);
                    return manipulationService.removeDevices(orgId, did)
                            .flatMap(ignore -> deviceService.deleteByDeviceIds(did))
                            .replaceWith(OxResponse.ok());
                });
    }

    @PUT
    @Path("/many/space")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> updateSpace(
            @HeaderParam("X-Org-Id") String orgId,
            String body
    ) {
        JsonObject obj;
        try {
            obj = new JsonObject(body);
        } catch (Exception e) {
            return Uni.createFrom().item(OxResponse.error("Invalid JSON body"));
        }

        String spaceId = obj.getString("spaceId");

        return requireAdmin(spaceId, orgId)
                .flatMap(error -> {
                    if (error != null) return Uni.createFrom().item(error);

                    String rootSpaceId = obj.getString("rootSpaceId");
                    JsonArray dids = obj.getJsonArray("dids");
                    if (dids != null) {
                        List<Uni<Void>> updates = new ArrayList<>();
                        for (int i = 0; i < dids.size(); i++) {
                            updates.add(deviceService.updateSpace(
                                    dids.getString(i), spaceId, rootSpaceId));
                        }
                        return Uni.join().all(updates).andFailFast().replaceWith(OxResponse.ok());
                    }

                    return Uni.createFrom().item(OxResponse.ok());
                });
    }

    @GET
    @Path("/many/{spaceId}")
    public Uni<Response> getDevices(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId
    ) {
        return requireMember(spaceId, orgId)
                .flatMap(error -> {
                    if (error != null) return Uni.createFrom().item(error);
                    return deviceService.getBySpaceId(spaceId)
                            .map(OxResponse::ok);
                });
    }

    @GET
    @Path("/shadows/{spaceId}")
    public Uni<Response> getShadows(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @QueryParam("did") List<String> did
    ) {
        return requireMember(spaceId, orgId)
                .flatMap(error -> {
                    if (error != null) return Uni.createFrom().item(error);

                    if (did != null && !did.isEmpty()) {
                        return manipulationService.getShadows(orgId, did)
                                .map(OxResponse::ok);
                    }

                    return deviceService.getBySpaceId(spaceId)
                            .flatMap(devices -> {
                                List<String> deviceIds = devices.stream()
                                        .map(d -> d.did).toList();
                                if (deviceIds.isEmpty()) {
                                    return Uni.createFrom().item(OxResponse.ok(List.of()));
                                }
                                return manipulationService.getShadows(orgId, deviceIds)
                                        .map(OxResponse::ok);
                            });
                });
    }

    @GET
    @Path("/properties/{spaceId}")
    public Uni<Response> getProperties(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @QueryParam("pid") List<String> pid
    ) {
        return requireMember(spaceId, orgId)
                .flatMap(error -> {
                    if (error != null) return Uni.createFrom().item(error);
                    return manipulationService.getProperties(orgId, pid)
                            .map(OxResponse::ok);
                });
    }

    @POST
    @Path("/properties/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> setProperties(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            String body
    ) {
        return requireMember(spaceId, orgId)
                .flatMap(error -> {
                    if (error != null) return Uni.createFrom().item(error);
                    return manipulationService.setProperties(orgId, body)
                            .map(OxResponse::ok);
                });
    }

    @POST
    @Path("/actions/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> invokeActions(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            String body
    ) {
        return requireMember(spaceId, orgId)
                .flatMap(error -> {
                    if (error != null) return Uni.createFrom().item(error);
                    return manipulationService.invokeActions(orgId, body)
                            .map(OxResponse::ok);
                });
    }

    // -- helper methods --

    private Uni<Response> requireAdmin(String spaceId, String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("X-Org-Id header is required"));
        }
        return spaceService.getById(spaceId)
                .flatMap(space -> {
                    if (space == null || space.organizationId == null) {
                        return Uni.createFrom().item(OxResponse.error("No access to this space"));
                    }
                    if (!orgId.equals(space.organizationId)) {
                        return Uni.createFrom().item(OxResponse.error("OrgId does not match the space"));
                    }
                    return orgAccessService.isAdmin(orgId, getUserId())
                            .map(admin -> admin ? null : OxResponse.error("Not an admin"));
                });
    }

    private Uni<Response> requireMember(String spaceId, String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("X-Org-Id header is required"));
        }
        return spaceService.getById(spaceId)
                .flatMap(space -> {
                    if (space == null || space.organizationId == null) {
                        return Uni.createFrom().item(OxResponse.error("No access to this space"));
                    }
                    if (!orgId.equals(space.organizationId)) {
                        return Uni.createFrom().item(OxResponse.error("OrgId does not match the space"));
                    }
                    return orgAccessService.isMember(orgId, getUserId())
                            .map(member -> member ? null : OxResponse.error("Not a member"));
                });
    }
}
