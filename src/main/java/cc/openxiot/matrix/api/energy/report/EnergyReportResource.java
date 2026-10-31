package cc.openxiot.matrix.api.energy.report;

import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.energy.AbstractEnergyResource;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergyType;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * 能源报表 API：一个角色 × 一个类型在 {@code [from, to)} 的报表。
 *
 * <p>时间参数是 epoch 毫秒（{@code from} 含、{@code to} 不含），与看板 / 告警接口同口径；
 * 展示桶的粒度由服务端按跨度自动选，前端不传。</p>
 */
@Path("/matrix/v1/energy/report/{spaceId}")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Energy", description = "能源报表 API")
@RequestScoped
public class EnergyReportResource extends AbstractEnergyResource {

    @Inject
    EnergyReportService reportService;

    /**
     * 一个角色 × 一个类型在 {@code [from, to)} 的报表。
     *
     * <p>返回展示桶 + 本期合计 + 环比（上一等长窗口）+ 同比（去年同一本地时间段）。
     * 粒度由服务端按跨度自动选，前端不传。</p>
     */
    @GET
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
}
