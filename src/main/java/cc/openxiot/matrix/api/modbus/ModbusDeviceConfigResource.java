package cc.openxiot.matrix.api.modbus;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.db.modbus.ModbusDeviceConfig;
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
@Path("/matrix/v1/modbus")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modbus", description = "Modbus 设备点表配置 API")
@RequestScoped
public class ModbusDeviceConfigResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    ModbusDeviceConfigService service;

    @Inject
    OrgAccessService orgAccessService;

    @GET
    public Uni<Response> hello() {
        logger.info("hello");
        return Uni.createFrom().item(OxResponse.ok("Hello from ModbusDeviceConfigResource"));
    }

    @GET
    @Path("/many")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listByOrg(@HeaderParam("X-Org-Id") String orgId) {
        logger.infov("list modbus device configs, org: {0}", orgId);
        return requireMember(orgId)
                .chain(o -> service.listByOrg(o))
                .map(list -> OxResponse.ok(ModbusJson.toArray(list)))
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
                .map(list -> OxResponse.ok(ModbusJson.toArray(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/public")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getPublic() {
        logger.info("list public modbus device configs");
        return service.listPublic()
                .map(list -> OxResponse.ok(ModbusJson.toArray(list)))
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
                    .map(config -> OxResponse.ok(ModbusJson.toJson(config)))
                    .onFailure(IllegalArgumentException.class)
                    .recoverWithItem(e -> OxResponse.error(e.getMessage()));
        }
        // 已带组织头：需为成员，可读「本组织配置或任意公开配置」
        return requireMember(orgId)
                .chain(o -> service.get(o, id))
                .map(config -> OxResponse.ok(ModbusJson.toJson(config)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/one")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> createOne(
            @HeaderParam("X-Org-Id") String orgId,
            ModbusDeviceConfig cfg
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
                .map(created -> OxResponse.ok(ModbusJson.toJson(created)))
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
            ModbusDeviceConfig cfg
    ) {
        logger.infov("update modbus device config, org: {0}, id: {1}", orgId, id);
        if (cfg == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        cfg.updater = currentUser();
        return requireMember(orgId)
                .chain(o -> service.update(o, id, cfg))
                .map(updated -> OxResponse.ok(ModbusJson.toJson(updated)))
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
