package cc.openxiot.matrix.api.modbus.service;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.modbus.service.params.InvokeModbusServiceRequest;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.codec.ModbusServiceCodec;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;


/**
 * Modbus 服务 API：把点表映射成可直接调用的服务（不生成设备实例，见 MODBUS.md）。
 *
 * <p><b>按空间鉴权</b>（与 {@code DeviceResource} 同口径）：路径上的 {@code spaceId} 是当前项目空间，
 * 增删改要该空间的**管理员**、查询与 {@code invoke} 要该空间的**成员**，判定见
 * {@link OrgAccessService#checkSpaceAdmin} / {@link OrgAccessService#checkSpaceMember}
 * （空间 accesses 里的组织条目 + {@code X-Org-Id} 命中时，组织管理员 / 组织成员同样通过）。</p>
 *
 * <p>{@code spaceId} 只作**鉴权锚点**：判定通过后一律按 id 操作，不再校验「服务是否真属于该空间」
 * ——与 {@code DeviceResource} 的 {@code spaceId} 一样（前端统一传当前项目根空间）。
 * {@code X-Org-Id} 头因此在增删改上不再是必填，只在空间判定里作为组织条目的匹配依据。</p>
 *
 * <p>invoke 一次调用一个 function（一次父设备往返），返回「字段 → 值」；写方法没有读值，
 * 返回空对象。</p>
 */
@Path("/matrix/v1/modbus/service")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modbus Service", description = "Modbus Service API")
@RequestScoped
public class ModbusServiceResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    ModbusServiceService service;

    @Inject
    OrgAccessService orgAccessService;

    @Inject
    SpaceService spaceService;

    @GET
    public Uni<Response> hello() {
        logger.info("hello");
        return Uni.createFrom().item(OxResponse.ok("Hello from ModbusServiceResource"));
    }

    /** 列出设备落点在该空间的服务（设备落点 = 服务里记录的 {@code device.space.spaceId}）。 */
    @GET
    @Path("/many/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listBySpace(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId
    ) {
        logger.infov("list modbus services, space: {0}", spaceId);
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> service.listBySpaceId(spaceId))
                .map(list -> OxResponse.ok(ModbusServiceCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 按依赖设备 did 查：该设备下挂的全部服务。 */
    @GET
    @Path("/parent/{spaceId}/{did}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listByParent(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @PathParam("did") String did
    ) {
        logger.infov("list modbus services, space: {0}, parent: {1}", spaceId, did);
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> service.listByParent(did))
                .map(list -> OxResponse.ok(ModbusServiceCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/one/{spaceId}/{id}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @PathParam("id") String id
    ) {
        logger.infov("get modbus service, space: {0}, id: {1}", spaceId, id);
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> service.get(id))
                .map(found -> OxResponse.ok(ModbusServiceCodec.encode(found)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/one/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> createOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            ModbusService body
    ) {
        logger.infov("create modbus service, space: {0}", spaceId);
        if (body == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        Person who = currentUser();
        return requireSpaceAdmin(spaceId, orgId)
                .chain(ignored -> {
                    // 鉴权按空间判，X-Org-Id 可以不传；服务本身也不再记 org（归属是设备的根空间，见 ManipulationService）
                    body.creator = who;
                    body.updater = who;
                    return service.create(body);
                })
                .map(created -> OxResponse.ok(ModbusServiceCodec.encode(created)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/one/{spaceId}/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> updateOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @PathParam("id") String id,
            ModbusService body
    ) {
        logger.infov("update modbus service, space: {0}, id: {1}", spaceId, id);
        if (body == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        body.updater = currentUser();
        return requireSpaceAdmin(spaceId, orgId)
                .chain(ignored -> service.update(id, body))
                .map(updated -> OxResponse.ok(ModbusServiceCodec.encode(updated)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @DELETE
    @Path("/one/{spaceId}/{id}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> deleteOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @PathParam("id") String id
    ) {
        logger.infov("delete modbus service, space: {0}, id: {1}", spaceId, id);
        return requireSpaceAdmin(spaceId, orgId)
                .chain(ignored -> service.deleteById(id))
                .map(v -> OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 调用一个方法：body {@code {"service": "<服务 id>", "function": <方法序号>}}。
     * 返回该方法的「字段 → 值」（写方法为空对象）。
     *
     * <p>传给 manipulation 的归属用**根空间 id**（不是请求头里的 orgId）：设备归属是按根空间记的，
     * 见 {@link cc.openxiot.matrix.api.device.manipulation.ManipulationService}。这里的 spaceId 可以
     * 是根下的任意一级子空间，取根即可。</p>
     */
    @POST
    @Path("/invoke/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> invoke(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            InvokeModbusServiceRequest req
    ) {
        if (req == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        logger.infov("invoke modbus service, space: {0}, service: {1}, function: {2}",
                spaceId, req.service, req.function);
        return requireSpaceMember(spaceId, orgId)
                .chain(space -> service.invoke(space.rootSpaceId(), req.service, req.function))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    /** 空间管理员（增删改）：空间不存在按「无权限」处理，不区分 404（与 DeviceResource 同口径）。 */
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

    /** 空间成员（查询与 invoke）。 */
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
