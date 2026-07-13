package cc.openxiot.site.api.device;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.site.api.space.SpaceService;
import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.device.DeviceSpace;
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

    @HeaderParam("Authorization")
    String authorization;

    @GET
    public Response hello() {
        logger.info("hello");
        return OxResponse.ok("Hello from DeviceResource");
    }

    @POST
    @Path("/many/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addDevices(
            @PathParam("spaceId") String spaceId,
            String body
    ) {
        String userId = getUserId();

        if (spaceService.noAccess(spaceId, userId)) {
            return OxResponse.error("No access to this space");
        }

        JsonArray arr;
        try {
            arr = new JsonArray(body);
        } catch (Exception e) {
            return OxResponse.error("Invalid JSON body");
        }

        manipulationService.addDevices(authorization, body);

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
        deviceService.saveAll(devices);

        return OxResponse.ok();
    }

    @DELETE
    @Path("/many/{spaceId}")
    public Response removeDevices(
            @PathParam("spaceId") String spaceId,
            @QueryParam("did") List<String> did
    ) {
        String userId = getUserId();

        if (spaceService.noAccess(spaceId, userId)) {
            return OxResponse.error("No access to this space");
        }

        manipulationService.removeDevices(authorization, did);
        deviceService.deleteByDeviceIds(did);

        return OxResponse.ok();
    }

    @PUT
    @Path("/many/space")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateSpace(String body) {
        String userId = getUserId();

        JsonObject obj;
        try {
            obj = new JsonObject(body);
        } catch (Exception e) {
            return OxResponse.error("Invalid JSON body");
        }

        String spaceId = obj.getString("spaceId");
        if (spaceService.noAccess(spaceId, userId)) {
            return OxResponse.error("No access to this space");
        }

        String rootSpaceId = obj.getString("rootSpaceId");
        JsonArray dids = obj.getJsonArray("dids");
        if (dids != null) {
            for (int i = 0; i < dids.size(); i++) {
                deviceService.updateSpace(dids.getString(i), spaceId, rootSpaceId);
            }
        }

        return OxResponse.ok();
    }

    @GET
    @Path("/many/{spaceId}")
    public Response getDevices(
            @PathParam("spaceId") String spaceId
    ) {
        String userId = getUserId();

        if (spaceService.noAccess(spaceId, userId)) {
            return OxResponse.error("No access to this space");
        }

        List<DeviceEntity> devices = deviceService.getBySpaceId(spaceId);
        return OxResponse.ok(devices);
    }

    @GET
    @Path("/shadows/{spaceId}")
    public Response getShadows(
            @PathParam("spaceId") String spaceId,
            @QueryParam("did") List<String> did
    ) {
        String userId = getUserId();

        if (spaceService.noAccess(spaceId, userId)) {
            return OxResponse.error("No access to this space");
        }

        List<String> deviceIds = did;
        if (deviceIds == null || deviceIds.isEmpty()) {
            List<DeviceEntity> devices = deviceService.getBySpaceId(spaceId);
            deviceIds = devices.stream().map(d -> d.did).toList();
        }

        if (deviceIds.isEmpty()) {
            return OxResponse.ok(List.of());
        }

        JsonObject result = manipulationService.getShadows(authorization, deviceIds);
        return OxResponse.ok(result);
    }

    @GET
    @Path("/properties/{spaceId}")
    public Response getProperties(
            @PathParam("spaceId") String spaceId,
            @QueryParam("pid") List<String> pid
    ) {
        String userId = getUserId();

        if (spaceService.noAccess(spaceId, userId)) {
            return OxResponse.error("No access to this space");
        }

        JsonObject result = manipulationService.getProperties(authorization, pid);
        return OxResponse.ok(result);
    }

    @POST
    @Path("/properties/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setProperties(
            @PathParam("spaceId") String spaceId,
            String body
    ) {
        String userId = getUserId();

        if (spaceService.noAccess(spaceId, userId)) {
            return OxResponse.error("No access to this space");
        }

        JsonObject result = manipulationService.setProperties(authorization, body);
        return OxResponse.ok(result);
    }

    @POST
    @Path("/actions/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response invokeActions(
            @PathParam("spaceId") String spaceId,
            String body
    ) {
        String userId = getUserId();

        if (spaceService.noAccess(spaceId, userId)) {
            return OxResponse.error("No access to this space");
        }

        JsonObject result = manipulationService.invokeActions(authorization, body);
        return OxResponse.ok(result);
    }
}
