package cc.openxiot.matrix.api.space;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.device.DeviceService;
import cc.openxiot.matrix.api.modbus.service.ModbusServiceService;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.codec.ModbusServiceCodec;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import cc.openxiot.matrix.typedef.model.ModelAnchor;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.space.access.SpaceAccessRole;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.access.SpaceAccess;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Path("/matrix/v1/space")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Space", description = "Space API")
@RequestScoped
public class SpaceResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    SpaceService service;

    @Inject
    DeviceService deviceService;

    @Inject
    ModbusServiceService modbusServiceService;

    @Inject
    OrgAccessService orgAccessService;

    @GET
    public Uni<Response> hello() {
        logger.info("hello");
        return Uni.createFrom().item(OxResponse.ok("Hello from SpaceResource"));
    }

    @POST
    @Path("/one")
    @RolesAllowed({OxRole.USER})
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> addOne(
            @HeaderParam("X-Org-Id") String orgId,
            SpaceEntity space
    ) {
        if (space.parentId != null && !space.parentId.isBlank()) {
            return getSpaceIfAdmin(space.parentId)
                    .chain(ignored -> service.create(space).map(OxResponse::ok))
                    .onFailure(IllegalArgumentException.class)
                    .recoverWithItem(e -> OxResponse.error(e.getMessage()));
        }

        // 根空间：无 X-Org-Id → 个人创建（用户是管理员）；带 X-Org-Id → 组织创建（组织是管理员）
        if (orgId == null || orgId.isBlank()) {
            SpaceAccess owner = new SpaceAccess(getUserId(), SpaceAccessType.USER, SpaceAccessRole.ADMIN);
            return service.createWithAccess(space, owner)
                    .map(OxResponse::ok)
                    .onFailure(IllegalArgumentException.class)
                    .recoverWithItem(e -> OxResponse.error(e.getMessage()));
        }

        SpaceAccess owner = new SpaceAccess(orgId, SpaceAccessType.ORGANIZATION, SpaceAccessRole.ADMIN);
        return orgAccessService.checkAdmin(orgId, getUserId())
                .chain(() -> service.createWithAccess(space, owner).map(OxResponse::ok))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @DELETE
    @Path("/one/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> removeOne(
            @PathParam("spaceId") String spaceId
    ) {
        return getSpaceIfAdmin(spaceId)
                .chain(existing -> service.delete(spaceId))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/one")
    @RolesAllowed({OxRole.USER})
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> updateOne(
            SpaceEntity space
    ) {
        if (space.id == null) {
            return Uni.createFrom().item(OxResponse.error("Space id is required"));
        }

        return getSpaceIfAdmin(space.id.toString())
                .chain(existing -> service.update(space))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 写入该空间在 3D 模型里的位置。
     *
     * <p>刻意不走 {@link #updateOne}：那个接口是整体语义，不认识 {@code anchor} 的客户端
     * （比如安卓只发 id + name）改一次空间名就会把锚点抹成 null。这里只动 {@code anchor} 一个字段。</p>
     */
    @PUT
    @Path("/one/{spaceId}/anchor")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> setAnchor(
            @PathParam("spaceId") String spaceId,
            ModelAnchor anchor
    ) {
        // model / rev 是判定锚点是否还有效的依据，缺了这条锚点就永远不可能被渲染
        if (anchor == null || anchor.model == null || anchor.model.isBlank()
                || anchor.rev == null || anchor.rev.isBlank()) {
            return Uni.createFrom().item(OxResponse.error("anchor.model and anchor.rev are required"));
        }

        return getSpaceIfAdmin(spaceId)
                .chain(space -> service.setAnchor(spaceId, anchor))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 清除该空间的 3D 位置（只删字段，不删空间）。 */
    @DELETE
    @Path("/one/{spaceId}/anchor")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> clearAnchor(
            @PathParam("spaceId") String spaceId
    ) {
        return getSpaceIfAdmin(spaceId)
                .chain(space -> service.clearAnchor(spaceId))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/one/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getOne(
            @PathParam("spaceId") String spaceId
    ) {
        return getSpaceIfMember(spaceId)
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/all")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getAll(
            @HeaderParam("X-Org-Id") String orgId
    ) {
        if (orgId == null || orgId.isBlank()) {
            // 无 X-Org-Id → 属于该用户的根空间（管理员或普通成员，accesses 含该用户）
            return service.getRootsByAccess(getUserId(), SpaceAccessType.USER)
                    .map(OxResponse::ok)
                    .onFailure(IllegalArgumentException.class)
                    .recoverWithItem(e -> OxResponse.error(e.getMessage()));
        }

        return orgAccessService.checkMember(orgId, getUserId())
                .chain(() -> service.getRootsByAccess(orgId, SpaceAccessType.ORGANIZATION))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/tree/{rootId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getTree(
            @PathParam("rootId") String rootId
    ) {
        return getSpaceIfMember(rootId)
                .chain(ignored -> service.getDescendants(rootId))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @GET
    @Path("/graph/{rootId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getGraph(
            @PathParam("rootId") String rootId
    ) {
        return getSpaceIfMember(rootId)
                .chain(ignored -> service.getDescendants(rootId))
                .chain(spaces -> {
                    // 设备与服务都按空间取：同一个空间一次查询，两个列表并行，再各自拍平成一张表。
                    // 服务只出精简视图（id/name/type）：投影在 Mongo 侧就把 functions/response 挡掉了，只回来 id/name。
                    List<String> spaceIds = spaces.stream().map(s -> s.id.toString()).toList();
                    Uni<List<DeviceEntity>> devices = bySpace(spaceIds, deviceService::getBySpaceId);
                    Uni<List<ModbusService>> services = bySpace(spaceIds, modbusServiceService::getBriefBySpaceId);
                    return Uni.combine().all().unis(devices, services).asTuple()
                            .map(tuple -> {
                                Map<String, Object> graph = new HashMap<>();
                                graph.put("spaces", spaces);
                                graph.put("devices", tuple.getItem1());
                                graph.put("services", ModbusServiceCodec.encodeBrief(tuple.getItem2()));
                                return OxResponse.ok(graph);
                            });
                })
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    /** 按空间逐个并行取，再拍平成一张表（spaces / devices / services 走同一条路径）。 */
    private static <T> Uni<List<T>> bySpace(List<String> spaceIds, Function<String, Uni<List<T>>> loader) {
        return Uni.join()
                .all(spaceIds.stream().map(loader).toList())
                .andFailFast()
                .map(lists -> lists.stream().flatMap(List::stream).toList());
    }

    private Uni<SpaceEntity> getSpaceIfMember(String spaceId) {
        return service.getById(spaceId)
                .chain(Unchecked.function(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("Space not found: " + spaceId);
                    }
                    return orgAccessService.checkSpaceMember(space, getUserId())
                            .map(ignored -> space);
                }));
    }

    private Uni<SpaceEntity> getSpaceIfAdmin(String spaceId) {
        return service.getById(spaceId)
                .chain(Unchecked.function(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("Space not found: " + spaceId);
                    }
                    return orgAccessService.checkSpaceAdmin(space, getUserId())
                            .map(ignored -> space);
                }));
    }
}
