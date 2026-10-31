package cc.openxiot.matrix.api.energy.summary;

import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.energy.AbstractEnergyResource;
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
 * 能源摘要 API：一级页一次取数。
 *
 * <p>固定看项目全量，不收角色 / 类型 / 配置；五张卡、五组曲线与存储状态由服务端
 * 读现有小时桶现算。</p>
 */
@Path("/matrix/v1/energy/summary")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Energy", description = "能源摘要 API")
@RequestScoped
public class EnergySummaryResource extends AbstractEnergyResource {

    @Inject
    EnergySummaryService summaryService;

    /**
     * 摘要页一次取数：五张总计卡 + 五组曲线 + 存储状态。
     *
     * <p>固定看项目全量，不收角色 / 类型 / 配置；粒度由服务端按跨度选。上一期的原始值
     * 由服务端算，变化率交给前端（与 {@code /report} 的环比口径一致）。</p>
     */
    @GET
    @Path("/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> summary(
            @PathParam("spaceId") String spaceId,
            @QueryParam("from") Long from,
            @QueryParam("to") Long to,
            @QueryParam("zone") String zone
    ) {
        logger.infov("query energy summary, space: {0}, from: {1}, to: {2}", spaceId, from, to);
        return zone(zone)
                .chain(summaryZone -> requireSpaceMember(spaceId)
                        .chain(space -> summaryService.summary(space.rootSpaceId(), from, to, summaryZone)))
                .map(summary -> OxResponse.ok(EnergySummaryCodec.encode(summary)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }
}
