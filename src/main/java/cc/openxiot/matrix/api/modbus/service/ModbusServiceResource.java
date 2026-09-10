package cc.openxiot.matrix.api.modbus.service;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.modbus.service.params.InvokeModbusServiceRequest;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.codec.ModbusServiceCodec;
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
 * Modbus 服务 API（MODBUS2.md 的方案二：把点表映射成可直接调用的服务，不再生成设备实例）。
 *
 * <p>org 取 {@code X-Org-Id} 请求头：创建 / 修改 / 删除经 {@link OrgAccessService#checkAdmin}
 * 校验（需为组织管理员），查询与 {@code invoke} 经 {@link OrgAccessService#checkMember}
 * 校验（组织成员即可）。全部按组织隔离：查不到 / 不属于本组织一律视为 not found。</p>
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

    @GET
    public Uni<Response> hello() {
        logger.info("hello");
        return Uni.createFrom().item(OxResponse.ok("Hello from ModbusServiceResource"));
    }

    @GET
    @Path("/many")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listByOrg(@HeaderParam("X-Org-Id") String orgId) {
        logger.infov("list modbus services, org: {0}", orgId);
        return requireMember(orgId)
                .chain(o -> service.listByOrg(o))
                .map(list -> OxResponse.ok(ModbusServiceCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 按父设备 did 查：该父设备下挂的全部服务。 */
    @GET
    @Path("/parent/{did}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listByParent(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("did") String did
    ) {
        logger.infov("list modbus services, org: {0}, parent: {1}", orgId, did);
        return requireMember(orgId)
                .chain(o -> service.listByParent(o, did))
                .map(list -> OxResponse.ok(ModbusServiceCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/one/{id}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("id") String id
    ) {
        logger.infov("get modbus service, org: {0}, id: {1}", orgId, id);
        return requireMember(orgId)
                .chain(o -> service.get(o, id))
                .map(found -> OxResponse.ok(ModbusServiceCodec.encode(found)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/one")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> createOne(
            @HeaderParam("X-Org-Id") String orgId,
            ModbusService body
    ) {
        logger.infov("create modbus service, org: {0}", orgId);
        if (body == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        Person who = currentUser();
        return requireAdmin(orgId)
                .chain(o -> {
                    body.orgId = o;
                    body.creator = who;
                    body.updater = who;
                    return service.create(body);
                })
                .map(created -> OxResponse.ok(ModbusServiceCodec.encode(created)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/one/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> updateOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("id") String id,
            ModbusService body
    ) {
        logger.infov("update modbus service, org: {0}, id: {1}", orgId, id);
        if (body == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        body.updater = currentUser();
        return requireAdmin(orgId)
                .chain(o -> service.update(o, id, body))
                .map(updated -> OxResponse.ok(ModbusServiceCodec.encode(updated)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @DELETE
    @Path("/one/{id}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> deleteOne(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("id") String id
    ) {
        logger.infov("delete modbus service, org: {0}, id: {1}", orgId, id);
        return requireAdmin(orgId)
                .chain(o -> service.delete(o, id))
                .map(v -> OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 调用一个方法：body {@code {"service": "<服务 id>", "function": <方法序号>}}。
     * 返回该方法的「字段 → 值」（写方法为空对象）。
     */
    @POST
    @Path("/invoke")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> invoke(
            @HeaderParam("X-Org-Id") String orgId,
            InvokeModbusServiceRequest req
    ) {
        if (req == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        logger.infov("invoke modbus service, org: {0}, service: {1}, function: {2}",
                orgId, req.service, req.function);
        return requireMember(orgId)
                .chain(o -> service.invoke(o, req.service, req.function))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    private Uni<String> requireAdmin(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }
        return orgAccessService.checkAdmin(orgId, getUserId()).map(ignored -> orgId);
    }

    private Uni<String> requireMember(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }
        return orgAccessService.checkMember(orgId, getUserId()).map(ignored -> orgId);
    }
}
