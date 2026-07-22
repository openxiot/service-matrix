package cc.openxiot.site.api.device;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.site.api.orgcheck.OrgAccessService;
import cc.openxiot.site.api.space.SpaceService;
import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.device.DeviceEntityMapper;
import cc.openxiot.site.db.space.SpaceEntity;
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
    @Path("/one/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> addOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            String body
    ) {
        return requireSpaceAdmin(spaceId, orgId)
                .chain(space -> addDevice(orgId, body, space))
                .chain(entity -> deviceService.save(entity))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    private Uni<DeviceEntity> addDevice(String orgId, String body, SpaceEntity space) {
        return manipulationService.addDevice(orgId, body)
                .map(device -> DeviceEntityMapper.from(device, space));
    }

    @DELETE
    @Path("/one/{spaceId}/{did}")
    public Uni<Response> removeOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @PathParam("did") String did
    ) {
        return requireSpaceAdmin(spaceId, orgId)
                .chain(ignored -> manipulationService.removeDevice(orgId, did))
                .chain(ignore -> deviceService.deleteByDeviceIds(List.of(did)))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/one/{spaceId}/{did}")
    public Uni<Response> getOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @PathParam("did") String did
    ) {
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> deviceService.getByDeviceId(did))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/many/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> addMany(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            String body
    ) {
        return requireSpaceAdmin(spaceId, orgId)
                .chain(space -> addDevices(orgId, body, space))
                .chain(entities -> deviceService.saveAll(entities))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    private Uni<List<DeviceEntity>> addDevices(String orgId, String body, SpaceEntity space) {
        return manipulationService.addDevices(orgId, body)
                .map(devices -> DeviceEntityMapper.from(devices, space));
    }

    @DELETE
    @Path("/many/{spaceId}")
    public Uni<Response> removeDevices(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @QueryParam("did") List<String> did
    ) {
        return requireSpaceAdmin(spaceId, orgId)
                .chain(ignored -> manipulationService.removeDevices(orgId, did))
                .chain(ignore -> deviceService.deleteByDeviceIds(did))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
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

        return requireSpaceAdmin(spaceId, orgId)
                .chain(ignored -> updateSpaceDevices(obj, spaceId))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/many/{spaceId}")
    public Uni<Response> getDevices(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId
    ) {
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> deviceService.getBySpaceId(spaceId))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/shadows/{spaceId}")
    public Uni<Response> getShadows(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @QueryParam("did") List<String> did
    ) {
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> fetchShadows(orgId, spaceId, did))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/properties/{spaceId}")
    public Uni<Response> getProperties(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @QueryParam("pid") List<String> pid
    ) {
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> manipulationService.getProperties(orgId, pid))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/properties/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> setProperties(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            String body
    ) {
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> manipulationService.setProperties(orgId, body))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/actions/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> invokeActions(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            String body
    ) {
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> manipulationService.invokeActions(orgId, body))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --
    private Uni<JsonArray> fetchShadows(String orgId, String spaceId, List<String> did) {
        if (did != null && !did.isEmpty()) {
            return manipulationService.getShadows(orgId, did);
        }

        return deviceService.getBySpaceId(spaceId)
                .chain(devices -> {
                    List<String> deviceIds = devices.stream().map(d -> d.did).toList();
                    if (deviceIds.isEmpty()) {
                        return Uni.createFrom().item(new JsonArray());
                    }

                    return manipulationService.getShadows(orgId, deviceIds);
                });
    }

    private Uni<Response> updateSpaceDevices(JsonObject obj, String spaceId) {
        String rootSpaceId = obj.getString("rootSpaceId");
        JsonArray dids = obj.getJsonArray("dids");
        if (dids != null) {
            List<Uni<Void>> updates = new ArrayList<>();
            for (int i = 0; i < dids.size(); i++) {
                updates.add(deviceService.updateSpace(dids.getString(i), spaceId, rootSpaceId));
            }
            return Uni.join().all(updates).andFailFast().replaceWith(OxResponse.ok());
        }

        return Uni.createFrom().item(OxResponse.ok());
    }

    private Uni<SpaceEntity> requireSpaceAdmin(String spaceId, String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }

        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null || space.organizationId == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }

                    if (!orgId.equals(space.organizationId)) {
                        throw new IllegalArgumentException("OrgId does not match the space");
                    }

                    return orgAccessService.checkAdmin(orgId, getUserId())
                            .map(ignored -> space);
                });
    }

    private Uni<SpaceEntity> requireSpaceMember(String spaceId, String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }

        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null || space.organizationId == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }

                    if (!orgId.equals(space.organizationId)) {
                        throw new IllegalArgumentException("OrgId does not match the space");
                    }

                    return orgAccessService.checkMember(orgId, getUserId())
                            .map(ignored -> space);
                });
    }
}
