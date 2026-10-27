package cc.openxiot.matrix.api.modbus.fd;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.modbus.fd.params.PollOnce;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.modbus.fd.codec.FieldDeviceCodec;
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
 * 现场设备 API：把Modbus点表映射成可直接调用的现场设备（不生成设备实例，见 MODBUS.md）。
 *
 * <p><b>按空间鉴权</b>（与 {@code DeviceResource} 同口径）：路径上的 {@code spaceId} 是当前项目空间，
 * 增删改要该空间的**管理员**、查询与 {@code invoke} 要该空间的**成员**，判定见
 * {@link OrgAccessService#checkSpaceAdmin} / {@link OrgAccessService#checkSpaceMember}
 * （空间 accesses 里的组织条目说了这个项目属于哪个组织，组织管理员 / 组织成员据此通过）。</p>
 *
 * <p>{@code spaceId} 只作**鉴权锚点**：判定通过后一律按 id 操作，不再校验「服务是否真属于该空间」
 * ——与 {@code DeviceResource} 的 {@code spaceId} 一样（前端统一传当前项目根空间）。
 * 本资源**不收** {@code X-Org-Id}：判定只读空间自己的 accesses。</p>
 *
 * <p>invoke 一次调用一个 function（一次父设备往返），返回「字段 → 值」；写方法没有读值，
 * 返回空对象。</p>
 */
@Path("/matrix/v1/modbus/fd")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Field Device (Modbus)", description = "Field Device API (Modbus)")
@RequestScoped
public class FieldDeviceResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    FieldDeviceService fdService;

    @Inject
    OrgAccessService orgAccessService;

    @Inject
    SpaceService spaceService;

    @GET
    public Uni<Response> hello() {
        logger.info("hello");
        return Uni.createFrom().item(OxResponse.ok("Hello from FieldDeviceResource"));
    }

    /** 列出设备落点在该空间的服务（设备落点 = 服务里记录的 {@code device.space.spaceId}）。 */
    @GET
    @Path("/many/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listBySpace(
            @PathParam("spaceId") String spaceId
    ) {
        logger.infov("list modbus devices, space: {0}", spaceId);
        return requireSpaceMember(spaceId)
                .chain(ignored -> fdService.listBySpaceId(spaceId))
                .map(list -> OxResponse.ok(FieldDeviceCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 按依赖设备 did 查：该设备下挂的全部服务。 */
    @GET
    @Path("/parent/{spaceId}/{did}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listByParent(
            @PathParam("spaceId") String spaceId,
            @PathParam("did") String did
    ) {
        logger.infov("list modbus devices, space: {0}, parent: {1}", spaceId, did);
        return requireSpaceMember(spaceId)
                .chain(ignored -> fdService.listByParent(did))
                .map(list -> OxResponse.ok(FieldDeviceCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/one/{spaceId}/{id}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getOne(
            @PathParam("spaceId") String spaceId,
            @PathParam("id") String id
    ) {
        logger.infov("get modbus device, space: {0}, id: {1}", spaceId, id);
        return requireSpaceMember(spaceId)
                .chain(ignored -> fdService.get(id))
                .map(found -> OxResponse.ok(FieldDeviceCodec.encode(found)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/one/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> createOne(
            @PathParam("spaceId") String spaceId,
            FieldDevice body
    ) {
        logger.infov("create modbus device, space: {0}", spaceId);
        if (body == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        Person who = currentUser();
        return requireSpaceAdmin(spaceId)
                .chain(ignored -> {
                    // 服务本身不记 org：归属是设备的根空间，见 ManipulationService
                    body.creator = who;
                    body.updater = who;
                    return fdService.create(body);
                })
                .map(created -> OxResponse.ok(FieldDeviceCodec.encode(created)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/one/{spaceId}/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> updateOne(
            @PathParam("spaceId") String spaceId,
            @PathParam("id") String id,
            FieldDevice body
    ) {
        logger.infov("update modbus device, space: {0}, id: {1}", spaceId, id);
        if (body == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        body.updater = currentUser();
        return requireSpaceAdmin(spaceId)
                .chain(ignored -> fdService.update(id, body))
                .map(updated -> OxResponse.ok(FieldDeviceCodec.encode(updated)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @DELETE
    @Path("/one/{spaceId}/{id}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> deleteOne(
            @PathParam("spaceId") String spaceId,
            @PathParam("id") String id
    ) {
        logger.infov("delete modbus device, space: {0}, id: {1}", spaceId, id);
        return requireSpaceAdmin(spaceId)
                .chain(ignored -> fdService.deleteById(id))
                .map(v -> OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 调用一个方法：body
     * {@code {"fdid": "<现场设备 id>", "pollIndex": <轮询序号>, "values": {...}}}。
     * 返回该方法的「字段 → 值」（写方法为空对象）。
     *
     * <p>{@code values}（字段名 → 原始值）只有写方法用得上：未给值的字段回落其定义的缺省值，
     * 给了未声明的字段名会被拒。读方法传了 values 一样会被拒 —— 静默忽略一个用户以为生效了的载荷，
     * 比报错糟得多。</p>
     *
     * <p>传给 manipulation 的归属用**根空间 id**（不是调用方所在的组织）：设备归属是按根空间记的，
     * 见 {@link cc.openxiot.matrix.api.device.manipulation.ManipulationService}。这里的 spaceId 可以
     * 是根下的任意一级子空间，取根即可。</p>
     */
    @POST
    @Path("/invoke/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> invoke(
            @PathParam("spaceId") String spaceId,
            PollOnce req
    ) {
        if (req == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        logger.infov("invoke field device, space: {0}, service: {1}, function: {2}, values: {3}",
                spaceId, req.fdid, req.pollIndex, req.values == null ? "-" : req.values.keySet());
        return requireSpaceMember(spaceId)
                .chain(space -> fdService.invoke(space.rootSpaceId(), req.fdid, req.pollIndex, req.values))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    /** 空间管理员（增删改）：空间不存在按「无权限」处理，不区分 404（与 DeviceResource 同口径）。 */
    private Uni<SpaceEntity> requireSpaceAdmin(String spaceId) {
        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }
                    return orgAccessService.checkSpaceAdmin(space, getUserId())
                            .map(ignored -> space);
                });
    }

    /** 空间成员（查询与 invoke）。 */
    private Uni<SpaceEntity> requireSpaceMember(String spaceId) {
        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }
                    return orgAccessService.checkSpaceMember(space, getUserId())
                            .map(ignored -> space);
                });
    }
}
