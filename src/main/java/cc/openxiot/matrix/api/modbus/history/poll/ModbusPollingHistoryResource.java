package cc.openxiot.matrix.api.modbus.history.poll;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.modbus.service.ModbusServiceService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.modbus.history.codec.ModbusPollingHistoryCodec;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
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
 * 某段区间内的故障记录（见 Modbus-History.md 第五节）。
 *
 * <p><b>另起一段路径</b>（不挂在 {@code /modbus/service} 之下）：服务定义与采集数据是两回事 ——
 * 前者是用户维护的配置，后者是设备跑出来的时间序列，生命周期与权限粒度将来都可能分开
 * （比如给只读的历史看板单独授权）。</p>
 *
 * <p><b>按空间鉴权</b>，与 {@code ModbusServiceResource.invoke} 同口径：路径上的 {@code spaceId}
 * 只作鉴权锚点，要该空间的**成员**；判定通过后一律按 id 查，不再校验「服务是否属于该空间」。
 * 唯一的例外是故障清单不传 {@code serviceId} 时（见 {@link #failures}）：那种查法没有 id 可依，
 * spaceId 同时决定了「查哪些服务」—— 取该空间及其**子空间**下的全部服务，与空间图同口径。</p>
 *
 * <p>时间参数一律是 **epoch 毫秒**（与响应里的 {@code at} / {@code recordedAt} 同口径），
 * 省掉两端各写一遍格式解析。</p>
 */
@Path("/matrix/v1/modbus/history/polling")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modbus History", description = "Modbus 采集历史 API")
@RequestScoped
public class ModbusPollingHistoryResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    ModbusPollingHistoryService pollingService;

    @Inject
    OrgAccessService orgAccessService;

    /** 空间级故障查询要先把空间下的服务 id 收齐（只读 id，见 ModbusServiceService.subtreeServiceIds）。 */
    @Inject
    ModbusServiceService serviceService;

    @Inject
    SpaceService spaceService;

    /**
     * 某段区间内的故障记录：{@code {serviceId, from, to, truncated, items, summary}}。
     *
     * <p>{@code items} 按时间倒序；{@code summary} 是**这批 items** 按分类（{@code byType}）与
     * 远端原始码（{@code byRemoteCode}）的聚合，两者总是一起看 —— {@code truncated} 为 true
     * 时说明还有更早的故障没取回来，前端应提示缩小时间范围。</p>
     *
     * <p>{@code serviceId} 不传 = **整个空间（含子空间）**：把该空间及其子树下所有服务的失败调用合成
     * 一条时间倒序的清单，每条 item 自带 {@code serviceId}（见 {@code ModbusPollingHistoryCodec}），
     * 前端据此认领归属。这一条顶掉前端「拿服务清单、再逐服务各发一次」的扇出 —— 项目级的历史页
     * 要的正是「这个项目里哪儿在报错」，N 条请求换 1 条，且合并与截断的口径在后端只有一份。
     * 此时响应里**不出 {@code serviceId} 键**（与 Codec 里「null 不出键」同口径）：它不是某个服务的结果。</p>
     *
     * <p>**为什么含子空间**：前端两页的服务清单来自 {@code /space/graph/{rootId}}，那张图取的就是
     * 整棵子树（项目 = 根空间 + 子空间，设备可以挂在任一子空间上）。故障清单若只查根空间自己的服务，
     * 子空间里的服务就会「表里列着、异常栏永远是空的」—— 把「没查」显示成「没问题」。</p>
     *
     * <p>{@code pollIndex} 不传 = 整个服务（空间级时 = 各服务的这个方法序号）；
     * {@code type} 不传 = 所有分类。</p>
     */
    @GET
    @Path("/failures/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> failures(
            @PathParam("spaceId") String spaceId,
            @QueryParam("serviceId") String serviceId,
            @QueryParam("pollIndex") Integer pollIndex,
            @QueryParam("type") String type,
            @QueryParam("from") Long from,
            @QueryParam("to") Long to,
            @QueryParam("limit") Integer limit
    ) {
        logger.infov("query modbus failures, space: {0}, service: {1}, type: {2}",
                spaceId, serviceId, type);
        boolean wholeSpace = serviceId == null || serviceId.isBlank();
        return requireSpaceMember(spaceId)
                .chain(ignored -> wholeSpace
                        ? serviceService.subtreeServiceIds(spaceId)
                        .chain(ids -> pollingService.failuresOfServices(
                                ids, pollIndex, type, millis(from), millis(to), limit))
                        : pollingService.failures(serviceId, pollIndex, type,
                        millis(from), millis(to), limit))
                .map(result -> {
                    JsonObject body = new JsonObject()
                            .put("from", result.from().getTime())
                            .put("to", result.to().getTime())
                            .put("limit", result.limit())
                            .put("truncated", result.truncated())
                            .put("items", ModbusPollingHistoryCodec.encode(result.items()))
                            .put("summary", ModbusPollingHistoryCodec.encodeSummary(result.items()));
                    if (!wholeSpace) {
                        body.put("serviceId", serviceId);
                    }
                    return OxResponse.ok(body);
                })
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