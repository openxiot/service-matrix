package cc.openxiot.matrix.api.modbus.config;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigOwner;
import cc.openxiot.matrix.db.modbus.config.codec.ModbusConfigCodec;
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
    public Uni<Response> listByOrg(@QueryParam("orgId") String orgId) {
        logger.infov("list modbus device configs, org: {0}", orgId);
        return requireMember(orgId)
                .chain(o -> service.listByOrg(o))
                .map(list -> OxResponse.ok(ModbusConfigCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 当前账号的私有配置（owner.type=user 且 owner.id=当前账号），不查组织成员。 */
    @GET
    @Path("/many/user")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listByUser() {
        logger.info("list my modbus device configs");
        return service.listByUser(getUserId())
                .map(list -> OxResponse.ok(ModbusConfigCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 可见点表：公开的 + 本组织的私有，供设备映射页选源点表。
     *
     * <p>未携带组织参数（未选组织 / 账号没有组织）时**不校验组织成员**，直接回公开集——
     * 映射的是空间（设备）不是组织，不该被「组织成员」挡在门外。
     * 带了组织参数才校验成员：这参数是客户端自己填的，不校验就能拿别人的组织 id 读走其私有配置。</p>
     */
    @GET
    @Path("/visible")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getVisible(@QueryParam("orgId") String orgId) {
        logger.infov("list visible modbus device configs, org: {0}", orgId);
        if (orgId == null || orgId.isBlank()) {
            return service.listVisible(getUserId(), null)
                    .map(list -> OxResponse.ok(ModbusConfigCodec.encode(list)))
                    .onFailure(IllegalArgumentException.class)
                    .recoverWithItem(e -> OxResponse.error(e.getMessage()));
        }
        return requireMember(orgId)
                .chain(o -> service.listVisible(getUserId(), o))
                .map(list -> OxResponse.ok(ModbusConfigCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 公开 + 本人私有（无组织个人访问）。不校验组织成员、不受组织上下文影响。
     */
    @GET
    @Path("/visible/user")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getVisibleByUser() {
        logger.info("list visible modbus device configs for current user");
        return service.listVisible(getUserId(), null)
                .map(list -> OxResponse.ok(ModbusConfigCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 公开 + 该组织私有（精确口径，不含调用者个人私有）。须组织成员（query 的 orgId 是客户端自填的，
     * 不校验就能拿别人组织 id 读走其私有配置）。
     */
    @GET
    @Path("/visible/org")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getVisibleByOrg(@QueryParam("orgId") String orgId) {
        logger.infov("list visible modbus device configs for org: {0}", orgId);
        return requireMember(orgId)
                .chain(o -> service.listVisibleByOrg(o))
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
            @PathParam("id") String id
    ) {
        logger.infov("get modbus device config, id: {0}", id);
        // 归属鉴权在 service 按 owner 判：任意公开可读，或当前账号所属的点表（见 ModbusConfigService.authorizeReadById）
        return service.get(getUserId(), id)
                .map(config -> OxResponse.ok(ModbusConfigCodec.encode(config)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/one")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> createOne(ModbusConfig cfg) {
        logger.info("create modbus device config");
        if (cfg == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        Person who = currentUser();
        return preAuthorizeCreate(cfg)
                .chain(ignored -> {
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
            @PathParam("id") String id,
            ModbusConfig cfg
    ) {
        logger.infov("update modbus device config, id: {0}", id);
        if (cfg == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        cfg.updater = currentUser();
        // 归属鉴权在 service 按 owner 判：仅归属者（本人 或 所有者的组织成员）可改（见 ModbusConfigService.update）
        return service.update(getUserId(), id, cfg)
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
            @PathParam("id") String id,
            @PathParam("lifecycle") String lifecycle
    ) {
        logger.infov("change modbus device config lifecycle, id: {0}, lifecycle: {1}", id, lifecycle);
        return service.changeLifecycle(getUserId(), id, lifecycle, currentUser())
                .map(updated -> OxResponse.ok(ModbusConfigCodec.encode(updated)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @DELETE
    @Path("/one/{id}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> deleteOne(
            @PathParam("id") String id
    ) {
        logger.infov("delete modbus device config, id: {0}", id);
        return service.delete(getUserId(), id)
                .map(v -> OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 创建前的 owner 权限预检：user 归属只能建本人点表，organization 归属须当前账号是该组织成员
     * （以 body 的 owner.id 为锚，不信 X-Org-Id 请求头）。其余（owner 缺失 / 类型非法）交由
     * {@link ModbusConfigService#validate} 兜底报错。
     */
    private Uni<Void> preAuthorizeCreate(ModbusConfig cfg) {
        if (cfg.owner == null) {
            return Uni.createFrom().nullItem();
        }
        String userId = getUserId();
        if (ModbusConfigOwner.TYPE_USER.equals(cfg.owner.type)) {
            if (cfg.owner.id != null && !cfg.owner.id.equals(userId)) {
                return Uni.createFrom().failure(new IllegalArgumentException("cannot create personal config for another user"));
            }
            if (cfg.owner.name == null || cfg.owner.name.isBlank()) {
                cfg.owner.name = currentUser().name;
            }
            return Uni.createFrom().nullItem();
        }
        if (ModbusConfigOwner.TYPE_ORGANIZATION.equals(cfg.owner.type)) {
            if (cfg.owner.id == null || cfg.owner.id.isBlank()) {
                return Uni.createFrom().failure(new IllegalArgumentException("owner.id is required"));
            }
            return orgAccessService.checkMember(cfg.owner.id, userId).replaceWithVoid();
        }
        return Uni.createFrom().nullItem();
    }

    private Uni<String> requireMember(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("orgId is required"));
        }
        return orgAccessService.checkMember(orgId, getUserId()).map(ignored -> orgId);
    }
}
