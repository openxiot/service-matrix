package cc.openxiot.matrix.api.statistics;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
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
 * 数据看板（首页）的聚合统计（见 API.md「统计」一节）。
 *
 * <p><b>为什么是一个聚合接口而不是让前端拼</b>：首页一屏要设备、服务、告警、故障四类数字，
 * 而现成的清单接口都带 limit（告警 200 / 1000、故障 1000），拿它们算出来的是**被截断的下限**
 * —— 卡片和曲线完全看不出来。逐条说明见 {@link StatisticsQueryService}。</p>
 *
 * <p><b>按空间鉴权</b>，与 {@code ModbusAlarmResource} 同口径：路径上的 {@code spaceId}
 * 只作鉴权锚点，要该空间的**成员**；范围内的数据取该空间及其**子树**
 * （项目 = 根空间 + 子空间，设备与服务可以挂在任一子空间上）。</p>
 *
 * <p>时间参数一律是 **epoch 毫秒**，与前端的 {@code from} / {@code to} 同口径。
 * 窗口由**前端**算（后端没有「最近 N 分钟」这类参数，这是既有约定）：告警曲线要 24 个整点桶，
 * 前端就给「当前整点往前 23 小时」到「现在」；本接口只校验区间合法且不超过 31 天。</p>
 */
@Path("/matrix/v1/statistics")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Statistics", description = "数据看板聚合统计 API")
@RequestScoped
public class StatisticsResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    StatisticsQueryService queryService;

    @Inject
    OrgAccessService orgAccessService;

    @Inject
    SpaceService spaceService;

    /**
     * 某个空间（含子空间）的看板数字：
     * {@code {from, to, devices, fds, alarms, failures}}。
     *
     * <p>{@code devices} 的 {@code byType} 按 URN 第 4 段分组、{@code fds} 的 {@code byConfig}
     * 按所配点表分组、{@code alarms} 的 {@code byText} 按告警文本分组，三份都按**条数降序、同数按名称升序**
     * 排好（同 {@code ModbusAlarmCodec.encodeSummary}），前端拿到就画。</p>
     *
     * <p>{@code hourly} 是**整点桶**且窗口内每个桶都在（没有发生的整点给 0）——
     * 前端因此不必猜「这段是没数据还是没请求」。曲线的右端是**当前这个整点**，它是部分桶。</p>
     *
     * <p>{@code to} 不传 = 现在；{@code from} 必填。区间反了、零宽、或超过 31 天都报错
     * （走 {@code OxResponse.error}，与别的接口一致，不用 4xx）。</p>
     */
    @GET
    @Path("/overview/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> overview(
            @PathParam("spaceId") String spaceId,
            @QueryParam("from") Long from,
            @QueryParam("to") Long to
    ) {
        logger.infov("query statistics overview, space: {0}", spaceId);
        return requireSpaceMember(spaceId)
                .chain(ignored -> queryService.overview(spaceId, millis(from), millis(to)))
                .map(overview -> OxResponse.ok(StatisticsCodec.encode(overview)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    /** 空间成员（看板是只读的，与告警清单同口径：成员即可，不需要管理员）。 */
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
