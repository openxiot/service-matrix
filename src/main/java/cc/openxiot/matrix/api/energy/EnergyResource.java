package cc.openxiot.matrix.api.energy;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergyType;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.time.ZoneId;
import java.util.Locale;

/**
 * 能源统计 API：配置的增删改查 + 报表。
 *
 * <p><b>按空间鉴权</b>，与 Modbus 现场设备/告警同口径：读需空间成员，写需空间管理员。
 * 路径上的 {@code spaceId} 可以是项目下任意一级空间，服务端统一归一成**根空间**——
 * 能源按项目统计，配置也只属于项目（点位照样可以挂在子空间的设备上）。</p>
 *
 * <p>时间参数一律是 <b>epoch 毫秒</b>（{@code from} 含、{@code to} 不含），与看板/告警接口同口径。
 * 错误走 {@code OxResponse.error}（HTTP 200 + {@code success:false}），与全仓一致，不用 4xx。</p>
 */
@Path("/matrix/v1/energy")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Energy", description = "能源统计 API")
@RequestScoped
public class EnergyResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    EnergyConfigService configService;

    @Inject
    EnergyReportService reportService;

    @Inject
    EnergyStatusService statusService;

    @Inject
    OrgAccessService orgAccessService;

    @Inject
    SpaceService spaceService;

    // -- 配置 --

    /** 项目下的配置；{@code role} / {@code type} 可选（页面按标签页时两个都给）。 */
    @GET
    @Path("/configs/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listConfigs(
            @PathParam("spaceId") String spaceId,
            @QueryParam("role") String role,
            @QueryParam("type") String type
    ) {
        logger.infov("list energy configs, space: {0}, role: {1}, type: {2}", spaceId, role, type);
        return requireSpaceMember(spaceId)
                .chain(space -> configService.list(space.rootSpaceId(),
                        optional(EnergyRole.class, role), optional(EnergyType.class, type)))
                .map(list -> OxResponse.ok(EnergyConfigCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/configs/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> createConfig(
            @PathParam("spaceId") String spaceId,
            EnergyConfigCodec.Request body
    ) {
        logger.infov("create energy config, space: {0}", spaceId);
        if (body == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        return requireSpaceAdmin(spaceId)
                .chain(space -> configService.create(space.rootSpaceId(), currentUser(),
                        EnergyConfigCodec.decode(body)))
                .map(config -> OxResponse.ok(EnergyConfigCodec.encode(config)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/configs/{spaceId}/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> updateConfig(
            @PathParam("spaceId") String spaceId,
            @PathParam("id") String id,
            EnergyConfigCodec.Request body
    ) {
        logger.infov("update energy config, space: {0}, id: {1}", spaceId, id);
        if (body == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        return requireSpaceAdmin(spaceId)
                .chain(space -> configService.update(space.rootSpaceId(), id, currentUser(),
                        EnergyConfigCodec.decode(body)))
                .map(config -> OxResponse.ok(EnergyConfigCodec.encode(config)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @DELETE
    @Path("/configs/{spaceId}/{id}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> deleteConfig(
            @PathParam("spaceId") String spaceId,
            @PathParam("id") String id
    ) {
        logger.infov("delete energy config, space: {0}, id: {1}", spaceId, id);
        return requireSpaceAdmin(spaceId)
                .chain(space -> configService.delete(space.rootSpaceId(), id))
                .map(deleted -> OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- 报表 --

    /**
     * 一个角色 × 一个类型在 {@code [from, to)} 的报表。
     *
     * <p>返回展示桶 + 本期合计 + 环比（上一等长窗口）+ 同比（去年同一本地时间段）。
     * 粒度由服务端按跨度自动选，前端不传。</p>
     */
    @GET
    @Path("/report/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> report(
            @PathParam("spaceId") String spaceId,
            @QueryParam("role") String role,
            @QueryParam("type") String type,
            @QueryParam("from") Long from,
            @QueryParam("to") Long to,
            @QueryParam("configId") String configId,
            @QueryParam("zone") String zone
    ) {
        logger.infov("query energy report, space: {0}, role: {1}, type: {2}, from: {3}, to: {4}",
                spaceId, role, type, from, to);
        return Uni.combine().all().unis(
                        required(EnergyRole.class, role, "role"),
                        required(EnergyType.class, type, "type"),
                        zone(zone))
                .asTuple()
                .chain(tuple -> requireSpaceMember(spaceId)
                        .chain(space -> reportService.report(space.rootSpaceId(),
                                tuple.getItem1(), tuple.getItem2(), from, to, configId, tuple.getItem3())))
                .map(report -> OxResponse.ok(EnergyReportCodec.encode(report)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- 实时状态 --

    /**
     * 储能实时状态（状态卡）：当前 SOC / 容量 / 功率 + 今日充放电量。
     *
     * <p>只对 {@code STORAGE} 有意义，其他角色没有这些指标 —— 不传 role，资源按类型直接查存储配置。</p>
     */
    @GET
    @Path("/status/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> status(
            @PathParam("spaceId") String spaceId,
            @QueryParam("type") String type,
            @QueryParam("zone") String zone
    ) {
        logger.infov("query energy status, space: {0}, type: {1}", spaceId, type);
        return Uni.combine().all().unis(
                        required(EnergyType.class, type, "type"),
                        zone(zone))
                .asTuple()
                .chain(tuple -> requireSpaceMember(spaceId)
                        .chain(space -> statusService.status(space.rootSpaceId(),
                                tuple.getItem1(), tuple.getItem2())))
                .map(status -> OxResponse.ok(EnergyStatusCodec.encode(status)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    /** 空间成员（读）。空间不存在按「无权限」处理，不区分 404（与 DeviceResource 同口径）。 */
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

    /** 空间管理员（写）。 */
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

    private static <E extends Enum<E>> Uni<E> required(Class<E> type, String value, String name) {
        if (value == null || value.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException(name + " is required"));
        }
        return Uni.createFrom().item(parse(type, value, name));
    }

    private static <E extends Enum<E>> E optional(Class<E> type, String value) {
        return value == null || value.isBlank() ? null : parse(type, value, type.getSimpleName());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
    }

    private static Uni<ZoneId> zone(String value) {
        if (value == null || value.isBlank()) {
            return Uni.createFrom().item(ZoneId.systemDefault());
        }
        try {
            return Uni.createFrom().item(ZoneId.of(value.trim()));
        } catch (Exception e) {
            return Uni.createFrom().failure(new IllegalArgumentException("invalid zone: " + value));
        }
    }
}
