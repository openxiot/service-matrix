package cc.openxiot.matrix.api.modbus.config;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.codec.ModbusConfigCodec;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * Modbus 设备点表配置 API。org 一律取 {@code X-Org-Id} 请求头，并经
 * {@link OrgAccessService#checkMember} 校验（需为组织成员），口径与 device/space 一致。
 */
@Path("/matrix/v1/modbus/config")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modbus", description = "Modbus 设备点表配置 API")
@RequestScoped
public class ModbusConfigResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    ModbusConfigService service;

    @Inject
    OrgAccessService orgAccessService;

    @GET
    public Uni<Response> hello() {
        logger.info("hello");
        return Uni.createFrom().item(OxResponse.ok("Hello from ModbusConfigResource"));
    }

    @GET
    @Path("/many")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listByOrg(@HeaderParam("X-Org-Id") String orgId) {
        logger.infov("list modbus device configs, org: {0}", orgId);
        return requireMember(orgId)
                .chain(o -> service.listByOrg(o))
                .map(list -> OxResponse.ok(ModbusConfigCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/visible")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getVisible(@HeaderParam("X-Org-Id") String orgId) {
        logger.infov("list visible modbus device configs, org: {0}", orgId);
        return requireMember(orgId)
                .chain(o -> service.listVisible(o))
                .map(list -> OxResponse.ok(ModbusConfigCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/public")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getPublic() {
        logger.info("list public modbus device configs");
        return service.listPublic()
                .map(list -> OxResponse.ok(ModbusConfigCodec.encode(list)))
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
        logger.infov("get modbus device config, org: {0}, id: {1}", orgId, id);
        if (orgId == null || orgId.isBlank()) {
            // 未携带组织头（未选组织浏览）：仅放行公开配置的只读查看
            return service.getPublic(id)
                    .map(config -> OxResponse.ok(ModbusConfigCodec.encode(config)))
                    .onFailure(IllegalArgumentException.class)
                    .recoverWithItem(e -> OxResponse.error(e.getMessage()));
        }
        // 已带组织头：需为成员，可读「本组织配置或任意公开配置」
        return requireMember(orgId)
                .chain(o -> service.get(o, id))
                .map(config -> OxResponse.ok(ModbusConfigCodec.encode(config)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/one")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> createOne(
            @HeaderParam("X-Org-Id") String orgId,
            ModbusConfig cfg
    ) {
        logger.infov("create modbus device config, org: {0}", orgId);
        if (cfg == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        Person who = currentUser();
        return requireMember(orgId)
                .chain(o -> {
                    cfg.orgId = o;
                    cfg.creator = who;
                    cfg.updater = who;
                    return service.create(cfg);
                })
                .map(created -> OxResponse.ok(ModbusConfigCodec.encode(created)))
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
            ModbusConfig cfg
    ) {
        logger.infov("update modbus device config, org: {0}, id: {1}", orgId, id);
        if (cfg == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        cfg.updater = currentUser();
        return requireMember(orgId)
                .chain(o -> service.update(o, id, cfg))
                .map(updated -> OxResponse.ok(ModbusConfigCodec.encode(updated)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 独立流转 lifecycle（预览/发布/下线）。与普通 update 分开：update 永不会修改 lifecycle，
     * 这里只允许 development / preview / released 三个目标值（后端不校验流转，直接覆盖）。
     */
    @PUT
    @Path("/one/{id}/lifecycle/{lifecycle}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> changeLifecycle(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("id") String id,
            @PathParam("lifecycle") String lifecycle
    ) {
        logger.infov("change modbus device config lifecycle, org: {0}, id: {1}, lifecycle: {2}", orgId, id, lifecycle);
        return requireMember(orgId)
                .chain(o -> service.changeLifecycle(o, id, lifecycle, currentUser()))
                .map(updated -> OxResponse.ok(ModbusConfigCodec.encode(updated)))
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
        logger.infov("delete modbus device config, org: {0}, id: {1}", orgId, id);
        return requireMember(orgId)
                .chain(o -> service.delete(o, id))
                .map(v -> OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    private Uni<String> requireMember(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }
        return orgAccessService.checkMember(orgId, getUserId()).map(ignored -> orgId);
    }
}
