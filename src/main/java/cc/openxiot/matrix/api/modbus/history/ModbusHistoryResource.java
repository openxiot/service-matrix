package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.modbus.service.ModbusServiceService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistory;
import cc.openxiot.matrix.db.modbus.history.codec.ModbusServiceFailureCodec;
import cc.openxiot.matrix.db.modbus.history.codec.ModbusServiceHistoryCodec;
import cc.openxiot.matrix.db.modbus.history.codec.ModbusServiceShadowCodec;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.Date;
import java.util.List;

/**
 * Modbus 采集历史的读取（见 Modbus-History.md 第五节）：当前值、区间曲线、故障列表。
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
@Path("/matrix/v1/modbus/history")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modbus History", description = "Modbus 采集历史 API")
@RequestScoped
public class ModbusHistoryResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    ModbusHistoryQueryService queryService;

    @Inject
    OrgAccessService orgAccessService;

    /** 空间级故障查询要先把空间下的服务 id 收齐（只读 id，见 ModbusServiceService.getBriefBySpaceId）。 */
    @Inject
    ModbusServiceService serviceService;

    @Inject
    SpaceService spaceService;

    /**
     * 一个服务下所有方法的当前值：{@code {serviceId, functions: [...]}}。
     *
     * <p>每条带 {@code fields}（最后已知值）、{@code recordedAt}（那个值有多旧）、
     * {@code lastError} / {@code errorAt}（从哪一刻起就不再更新）—— 前端要能显示
     * 「最后已知值 + 它从哪一刻起不再更新」，而不是把陈旧值当最新值画。
     * 没有影子的方法不在结果里：那是「从没调用过」，与「有值但很旧」不是一回事。</p>
     */
    @GET
    @Path("/current/{spaceId}/{serviceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> current(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @PathParam("serviceId") String serviceId
    ) {
        logger.infov("get modbus current values, space: {0}, service: {1}", spaceId, serviceId);
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> queryService.currentValues(serviceId))
                .map(shadows -> OxResponse.ok(new JsonObject()
                        .put("serviceId", serviceId)
                        .put("functions", ModbusServiceShadowCodec.encode(shadows))))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 一个点位的区间曲线：{@code {serviceId, functionIndex, field, from, to, maxPoints, downsampled,
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
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @QueryParam("serviceId") String serviceId,
            @QueryParam("functionIndex") Integer functionIndex,
            @QueryParam("field") String field,
            @QueryParam("from") Long from,
            @QueryParam("to") Long to,
            @QueryParam("maxPoints") Integer maxPoints
    ) {
        logger.infov("query modbus history, space: {0}, service: {1}, function: {2}, field: {3}",
                spaceId, serviceId, functionIndex, field);
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> queryService.range(serviceId, functionIndex, field,
                        millis(from), millis(to), maxPoints))
                .map(result -> OxResponse.ok(rangeBody(serviceId, functionIndex, field, result)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 某段区间内的故障记录：{@code {serviceId, from, to, truncated, items, summary}}。
     *
     * <p>{@code items} 按时间倒序；{@code summary} 是**这批 items** 按分类（{@code byType}）与
     * 远端原始码（{@code byRemoteCode}）的聚合，两者总是一起看 —— {@code truncated} 为 true
     * 时说明还有更早的故障没取回来，前端应提示缩小时间范围。</p>
     *
     * <p>{@code serviceId} 不传 = **整个空间（含子空间）**：把该空间及其子树下所有服务的故障合成
     * 一条时间倒序的清单，每条 item 自带 {@code serviceId}（见 {@code ModbusServiceFailureCodec}），
     * 前端据此认领归属。这一条顶掉前端「拿服务清单、再逐服务各发一次」的扇出 —— 项目级的历史页
     * 要的正是「这个项目里哪儿在报错」，N 条请求换 1 条，且合并与截断的口径在后端只有一份。
     * 此时响应里**不出 {@code serviceId} 键**（与 Codec 里「null 不出键」同口径）：它不是某个服务的结果。</p>
     *
     * <p>**为什么含子空间**：前端两页的服务清单来自 {@code /space/graph/{rootId}}，那张图取的就是
     * 整棵子树（项目 = 根空间 + 子空间，设备可以挂在任一子空间上）。故障清单若只查根空间自己的服务，
     * 子空间里的服务就会「表里列着、异常栏永远是空的」—— 把「没查」显示成「没问题」。</p>
     *
     * <p>{@code functionIndex} 不传 = 整个服务（空间级时 = 各服务的这个方法序号）；
     * {@code type} 不传 = 所有分类。</p>
     */
    @GET
    @Path("/failures/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> failures(
            @HeaderParam("X-Org-Id") String orgId,
            @PathParam("spaceId") String spaceId,
            @QueryParam("serviceId") String serviceId,
            @QueryParam("functionIndex") Integer functionIndex,
            @QueryParam("type") String type,
            @QueryParam("from") Long from,
            @QueryParam("to") Long to,
            @QueryParam("limit") Integer limit
    ) {
        logger.infov("query modbus failures, space: {0}, service: {1}, type: {2}",
                spaceId, serviceId, type);
        boolean wholeSpace = serviceId == null || serviceId.isBlank();
        return requireSpaceMember(spaceId, orgId)
                .chain(ignored -> wholeSpace
                        ? subtreeServiceIds(spaceId)
                        .chain(ids -> queryService.failuresOfServices(
                                ids, functionIndex, type, millis(from), millis(to), limit))
                        : queryService.failures(serviceId, functionIndex, type,
                        millis(from), millis(to), limit))
                .map(result -> {
                    JsonObject body = new JsonObject()
                            .put("from", result.from().getTime())
                            .put("to", result.to().getTime())
                            .put("limit", result.limit())
                            .put("truncated", result.truncated())
                            .put("items", ModbusServiceFailureCodec.encode(result.items()))
                            .put("summary", ModbusServiceFailureCodec.encodeSummary(result.items()));
                    if (!wholeSpace) {
                        body.put("serviceId", serviceId);
                    }
                    return OxResponse.ok(body);
                })
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    /**
     * 空间（含子空间）下的服务 id —— 与 {@code SpaceResource.getGraph} 同一条路：
     * 先取整棵子树，再逐空间取服务，最后拍平成一张 id 表。
     *
     * <p>逐空间查而不是一条跨空间查询，是因为服务的「按空间查」索引在
     * {@code device.space.spaceId} 上（见 {@code ModbusServiceRepository.ensureIndexes}）；
     * 一个项目下的空间只有几个，与空间图付的是同一笔账。</p>
     *
     * <p>只取 id，故走精简投影：{@code functions / response} 不上网络。</p>
     */
    private Uni<List<String>> subtreeServiceIds(String spaceId) {
        return spaceService.getDescendants(spaceId)
                .chain(spaces -> Uni.join()
                        .all(spaces.stream()
                                .map(space -> serviceService.getBriefBySpaceId(space.id.toString()))
                                .toList())
                        .andFailFast())
                .map(groups -> groups.stream()
                        .flatMap(List::stream)
                        .filter(service -> service != null && service.id != null)
                        .map(service -> service.id.toHexString())
                        // 去重是保险：万一同一个服务在子树里被数到两次，合并出来的故障会重复占 limit
                        .distinct()
                        .toList());
    }

    private JsonObject rangeBody(String serviceId, Integer functionIndex, String field,
                                 ModbusHistoryQueryService.RangeResult result) {
        ModbusHistoryWindow.Window window = result.window();
        JsonArray points = new JsonArray();
        if (window.downsampled()) {
            for (ModbusHistoryWindow.Bucket bucket : window.buckets()) {
                points.add(ModbusServiceHistoryCodec.encode(bucket));
            }
        } else {
            for (ModbusServiceHistory sample : window.samples()) {
                points.add(ModbusServiceHistoryCodec.encode(sample));
            }
        }

        JsonObject body = new JsonObject()
                .put("serviceId", serviceId)
                .put("functionIndex", functionIndex)
                .put("field", field)
                .put("from", result.from().getTime())
                .put("to", result.to().getTime())
                .put("maxPoints", result.maxPoints())
                .put("downsampled", window.downsampled())
                .put("total", window.samples().size())
                .put("points", points);
        // 没有更早的样本时不出键（与 Codec 里「null 不出键」同口径）：它不是一条空样本
        if (window.carryIn() != null) {
            body.put("carryIn", ModbusServiceHistoryCodec.encode(window.carryIn()));
        }
        return body;
    }

    /** 空间成员（读历史与 invoke 同口径，见 {@code ModbusServiceResource}）。 */
    private Uni<SpaceEntity> requireSpaceMember(String spaceId, String orgId) {
        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }
                    return orgAccessService.checkSpaceMember(space, orgId, getUserId())
                            .map(ignored -> space);
                });
    }

    private static Date millis(Long value) {
        return value == null ? null : new Date(value);
    }
}
