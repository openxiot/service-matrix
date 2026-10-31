package cc.openxiot.matrix.api.energy.status;

import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.energy.AbstractEnergyResource;
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
 * 能源实时状态 API：储能状态卡需要的「此刻」数据。
 *
 * <p>报表桶里的 SOC 是平均值或末值，给不出「现在」，因此状态卡单独读点位基线。</p>
 */
@Path("/matrix/v1/energy/status/{spaceId}")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Energy", description = "能源实时状态 API")
@RequestScoped
public class EnergyStatusResource extends AbstractEnergyResource {

    @Inject
    EnergyStatusService statusService;

    /**
     * 储能实时状态（状态卡）：当前 SOC / 容量 / 功率 + 今日充放电量。
     *
     * <p>只对 {@code STORAGE} 有意义，其他角色没有这些指标 —— 不传 role，资源按类型直接查存储配置。</p>
     */
    @GET
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
}
