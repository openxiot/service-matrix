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
import cc.openxiot.matrix.db.ModelAnchor;
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
                .chain(space -> addDevice(req, space))
                .chain(entity -> deviceService.save(entity))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 归属按**根空间**记（不是「谁在调」，见 {@link ManipulationService}）：设备注册在哪一级子空间都行，ownerId 都取根。 */
    private Uni<DeviceEntity> addDevice(DeviceAddRequest req, SpaceEntity space) {
        return manipulationService.addDevice(space.rootSpaceId(), req)
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
                .chain(space -> manipulationService.removeDevice(space.rootSpaceId(), did))
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

    /**
     * 写入该设备在 3D 模型里的位置。
     *
     * <p>本实体除了 {@code space} 之外没有任何写接口，所以标锚点必须单开一个 —— 也用不着
     * 走整体语义，只动 {@code anchor} 一个字段。</p>
     */
    @PUT
    @Path("/one/{spaceId}/{did}/anchor")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> setAnchor(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @PathParam("did") String did,
            ModelAnchor anchor
    ) {
        // model / rev 是判定锚点是否还有效的依据，缺了这条锚点就永远不可能被渲染
        if (anchor == null || anchor.model == null || anchor.model.isBlank()
                || anchor.rev == null || anchor.rev.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("anchor.model and anchor.rev are required"));
        }

        return requireSpaceAdmin(spaceId, orgId)
                .chain(ignored -> requireDevice(did))
                .chain(ignored -> deviceService.setAnchor(did, anchor))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 清除该设备的 3D 位置，退回它所属空间的锚点。 */
    @DELETE
    @Path("/one/{spaceId}/{did}/anchor")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> clearAnchor(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @PathParam("did") String did
    ) {
        return requireSpaceAdmin(spaceId, orgId)
                .chain(ignored -> requireDevice(did))
                .chain(ignored -> deviceService.clearAnchor(did))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 设备不存在就让整条链失败，别静默返回「成功」—— $set 匹配不到时 modifiedCount 是 0，看不出来。 */
    private Uni<DeviceEntity> requireDevice(String did) {
        return deviceService.getByDeviceId(did)
                .map(device -> {
                    if (device == null) {
                        throw new IllegalArgumentException("Device not found: " + did);
                    }
                    return device;
                });
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
                .chain(space -> addDevices(req, space))
                .chain(entities -> deviceService.saveAll(entities))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 批量添加：req 为 DeviceAddRequest 数组，逐台向 manipulation 校验归属后落矩阵设备 */
    private Uni<List<DeviceEntity>> addDevices(List<DeviceAddRequest> req, SpaceEntity space) {
        return manipulationService.addDevices(space.rootSpaceId(), req)
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
                .chain(space -> manipulationService.removeDevices(space.rootSpaceId(), did))
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
                .chain(space -> updateSpaceDevices(req, space.rootSpaceId()))
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
                .chain(space -> fetchShadows(space, did))
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
                .chain(space -> manipulationService.getProperties(space.rootSpaceId(), pid))
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
                .chain(space -> manipulationService.setProperties(space.rootSpaceId(), body))
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
                .chain(space -> manipulationService.invokeActions(space.rootSpaceId(), body))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    /** 取了 did 就直接查；否则按空间取该空间下的设备再查。归属一律用**根空间 id**。 */
    private Uni<JsonArray> fetchShadows(SpaceEntity space, List<String> did) {
        String ownerId = space.rootSpaceId();
        if (did != null && !did.isEmpty()) {
            return manipulationService.getShadows(ownerId, did);
        }

        return deviceService.getBySpaceId(space.id.toString())
                .chain(devices -> {
                    List<String> deviceIds = devices.stream().map(d -> d.did).toList();
                    if (deviceIds.isEmpty()) {
                        return Uni.createFrom().item(new JsonArray());
                    }

                    return manipulationService.getShadows(ownerId, deviceIds);
                });
    }

    /**
     * 逐台改写设备落点；dids 缺省表示不迁移任何设备，直接成功。
     *
     * <p>根空间由服务端从目标空间算（请求体里不再要 {@code rootSpaceId}）：客户端传的根是它自己的认知，
     * 一旦和目标空间对不上，写进设备的就是个错的根。</p>
     */
    private Uni<Response> updateSpaceDevices(DeviceUpdateSpaceRequest req, String rootSpaceId) {
        if (req.dids != null) {
            List<Uni<Void>> updates = new ArrayList<>();
            for (String did : req.dids) {
                updates.add(deviceService.updateSpace(did, req.spaceId, rootSpaceId));
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
                    // orgId 不再进判定：组织从空间自己的 accesses 读（见 OrgAccessService）。
                    // 参数与请求头一起摘掉是后续单独一步，先留着不动签名。
                    return orgAccessService.checkSpaceAdmin(space, getUserId())
                            .map(ignored -> space);
                });
    }

    private Uni<SpaceEntity> requireSpaceMember(String spaceId, String orgId) {
        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }
                    // 同上：orgId 不再进判定。
                    return orgAccessService.checkSpaceMember(space, getUserId())
                            .map(ignored -> space);
                });
    }
}
