package cc.openxiot.matrix.api.modbus.history.registry;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.modbus.history.ModbusHistoryRangeCodec;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
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
import org.jboss.logging.Logger;

import java.util.Date;

/**
 * 一个点位的区间曲线（见 Modbus-History.md 第五节）。
 *
 * <p><b>另起一段路径</b>（不挂在 {@code /modbus/service} 之下）：服务定义与采集数据是两回事 ——
 * 前者是用户维护的配置，后者是设备跑出来的时间序列，生命周期与权限粒度将来都可能分开
 * （比如给只读的历史看板单独授权）。</p>
 *
 * <p><b>按空间鉴权</b>，与 {@code ModbusServiceResource.invoke} 同口径：路径上的 {@code spaceId}
 * 只作鉴权锚点，要该空间的**成员**；判定通过后一律按 id 查，不再校验「服务是否属于该空间」。</p>
 *
 * <p>时间参数一律是 **epoch 毫秒**（与响应里的 {@code at} / {@code recordedAt} 同口径），
 * 省掉两端各写一遍格式解析。</p>
 */
@Path("/matrix/v1/modbus/history/registry")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modbus History", description = "Modbus 采集历史 API")
@RequestScoped
public class ModbusRegistryHistoryResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    ModbusRegistryHistoryService registryService;

    @Inject
    OrgAccessService orgAccessService;

    @Inject
    SpaceService spaceService;

    /**
     * 一个点位的区间曲线：{@code {serviceId, pollIndex, field, from, to, maxPoints, downsampled,
     * carryIn, points}}。
     *
     * <p>{@code carryIn} 是区间起点之前最近的那一条（可能没有）：不带它，前端不知道 {@code from}
     * 时刻的值，曲线从起点就是空白。{@code downsampled} 决定 {@code points} 怎么读：false 是原始样本
     * （{@code {at, value, keepalive}}），true 是降采样桶（{@code {at, until, count, first, last, min, max, avg}}）。</p>
     */
    @GET
    @Path("/range/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> range(
            @PathParam("spaceId") String spaceId,
            @QueryParam("serviceId") String serviceId,
            @QueryParam("pollIndex") Integer pollIndex,
            @QueryParam("field") String field,
            @QueryParam("from") Long from,
            @QueryParam("to") Long to,
            @QueryParam("maxPoints") Integer maxPoints
    ) {
        logger.infov("query modbus history, space: {0}, service: {1}, polling: {2}, field: {3}",
                spaceId, serviceId, pollIndex, field);
        return requireSpaceMember(spaceId)
                .chain(ignored -> registryService.range(serviceId, pollIndex, field,
                        millis(from), millis(to), maxPoints))
                .map(result -> OxResponse.ok(
                        ModbusHistoryRangeCodec.encode(serviceId, pollIndex, field, result)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    /** 空间成员（读历史与 invoke 同口径，见 {@code ModbusServiceResource}）。 */
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

    private static Date millis(Long value) {
        return value == null ? null : new Date(value);
    }
}