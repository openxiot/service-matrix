package cc.openxiot.matrix.api.device;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.device.manipulation.ManipulationService;
import cc.openxiot.matrix.api.device.request.DeviceAddRequest;
import cc.openxiot.matrix.api.device.request.DeviceUpdateSpaceRequest;
import cc.openxiot.matrix.api.modbus.service.ModbusServiceService;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceEntityMapper;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@Path("/matrix/v1/device")
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

    /** 设备操作入口：写设备/读影子/读写属性/执行方法都转发到 manipulation 服务 */
    @Inject
    ManipulationService manipulationService;

    /** 删设备时级联清理该设备下的 Modbus 服务（按 device.did 匹配） */
    @Inject
    ModbusServiceService modbusServiceService;

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
    @RolesAllowed({OxRole.USER})
    public Uni<Response> addOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            DeviceAddRequest req
    ) {
        if (req == null || req.did == null || req.did.isBlank() || req.key == null || req.key.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("did and key are required"));
        }

        return requireSpaceAdmin(spaceId, orgId)
                .chain(space -> addDevice(orgId, req, space))
                .chain(entity -> deviceService.save(entity))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    private Uni<DeviceEntity> addDevice(String orgId, DeviceAddRequest req, SpaceEntity space) {
        return manipulationService.addDevice(orgId, req)
                .map(device -> DeviceEntityMapper.from(device, space));
    }

    @DELETE
    @Path("/one/{spaceId}/{did}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> removeOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @PathParam("did") String did
    ) {
        return requireSpaceAdmin(spaceId, orgId)
                .chain(ignored -> manipulationService.removeDevice(orgId, did))
                // 级联清理该设备下的 Modbus 服务（按 device.did 匹配，不限组织）：
                // 服务先删、设备行后删，任一步失败整链失败还能重试，不会留下删不掉的服务
                .chain(ignore -> modbusServiceService.deleteByDeviceDids(List.of(did)))
                .chain(ignore -> deviceService.deleteByDeviceIds(List.of(did)))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/one/{spaceId}/{did}")
    @RolesAllowed({OxRole.USER})
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
    @RolesAllowed({OxRole.USER})
    public Uni<Response> addMany(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            List<DeviceAddRequest> req
    ) {
        if (req == null) {
            return Uni.createFrom().item(OxResponse.error("devices are required"));
        }

        return requireSpaceAdmin(spaceId, orgId)
                .chain(space -> addDevices(orgId, req, space))
                .chain(entities -> deviceService.saveAll(entities))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 批量添加：req 为 DeviceAddRequest 数组，逐台向 manipulation 校验归属后落矩阵设备 */
    private Uni<List<DeviceEntity>> addDevices(String orgId, List<DeviceAddRequest> req, SpaceEntity space) {
        return manipulationService.addDevices(orgId, req)
                .map(devices -> DeviceEntityMapper.from(devices, space));
    }

    @DELETE
    @Path("/many/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> removeDevices(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @QueryParam("did") List<String> did
    ) {
        return requireSpaceAdmin(spaceId, orgId)
                .chain(ignored -> manipulationService.removeDevices(orgId, did))
                // 级联清理这批设备下的 Modbus 服务（一次删完，不限组织）
                .chain(ignore -> modbusServiceService.deleteByDeviceDids(did))
                .chain(ignore -> deviceService.deleteByDeviceIds(did))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/many/space")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> updateSpace(
            @HeaderParam("X-Org-Id") String orgId,
            DeviceUpdateSpaceRequest req
    ) {
        if (req == null || req.spaceId == null || req.spaceId.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("spaceId is required"));
        }

        return requireSpaceAdmin(req.spaceId, orgId)
                .chain(ignored -> updateSpaceDevices(req))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/many/{spaceId}")
    @RolesAllowed({OxRole.USER})
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
    @RolesAllowed({OxRole.USER})
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
    @RolesAllowed({OxRole.USER})
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
    @RolesAllowed({OxRole.USER})
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
    @RolesAllowed({OxRole.USER})
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

    /** 逐台改写设备归属；dids 缺省表示不迁移任何设备，直接成功 */
    private Uni<Response> updateSpaceDevices(DeviceUpdateSpaceRequest req) {
        if (req.dids != null) {
            List<Uni<Void>> updates = new ArrayList<>();
            for (String did : req.dids) {
                updates.add(deviceService.updateSpace(did, req.spaceId, req.rootSpaceId));
            }
            return Uni.join().all(updates).andFailFast().replaceWith(OxResponse.ok());
        }

        return Uni.createFrom().item(OxResponse.ok());
    }

    private Uni<SpaceEntity> requireSpaceAdmin(String spaceId, String orgId) {
        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }
                    return orgAccessService.checkSpaceAdmin(space, orgId, getUserId())
                            .map(ignored -> space);
                });
    }

    private Uni<SpaceEntity> requireSpaceMember(String spaceId, String orgId) {
        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }
                    return orgAccessService.checkSpaceMember(space, orgId, getUserId())
                            .map(ignored -> space);
                });
    }
}
