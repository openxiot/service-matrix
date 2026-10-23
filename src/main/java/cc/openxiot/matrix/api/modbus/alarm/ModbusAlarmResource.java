package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.modbus.service.ModbusServiceService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.modbus.alarm.codec.ModbusAlarmCodec;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.Date;

/**
 * Modbus 阈值告警的读取与处理（见 Modbus-Alarm.md 第九节）。
 *
 * <p><b>另起一段路径</b>（不挂在 {@code /modbus/history} 或 {@code /modbus/service} 之下）：
 * 告警是第三件事 —— 它既不是用户维护的配置，也不是设备跑出来的时间序列，而是**判定出来的事件**，
 * 且它有自己的权限粒度（成员可处理，不需要管理员）与保留口径（永久保留，不挂 TTL）。
 * 与历史接口当初另起一段同理。</p>
 *
 * <p><b>按空间鉴权</b>，与 {@code ModbusTransactionHistoryResource} 同口径：路径上的 {@code spaceId}
 * 只作鉴权锚点，要该空间的**成员**；判定通过后一律按 id 查，不再校验「告警是否属于该空间」。
 * 唯一例外是不传 {@code serviceId} 时（见 {@link #many}）：那种查法没有 id 可依，
 * spaceId 同时决定了「查哪些服务」—— 取该空间及其**子空间**下的全部服务，与空间图同口径。</p>
 *
 * <p>时间参数一律是 **epoch 毫秒**（与响应里的 {@code at} / {@code recoveredAt} 同口径），
 * 省掉两端各写一遍格式解析。</p>
 *
 * <p>{@code text} / {@code field} / {@code state} / {@code unit} 都是**数据**（用户填的、点表里的），
 * 原样下发、**永不翻译**。</p>
 */
@Path("/matrix/v1/modbus/alarm")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modbus Alarm", description = "Modbus 阈值告警 API")
@RequestScoped
public class ModbusAlarmResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    ModbusAlarmQueryService queryService;

    @Inject
    ModbusAlarmService alarmService;

    /** 空间级查询要先把空间下的服务 id 收齐，处理时要判归属（见 ModbusServiceService.subtreeServiceIds）。 */
    @Inject
    ModbusServiceService serviceService;

    @Inject
    OrgAccessService orgAccessService;

    @Inject
    SpaceService spaceService;

    /**
     * 某段区间内的告警：{@code {serviceId?, from, to, limit, truncated, items, summary}}。
     *
     * <p>{@code items} 按 {@code at} 倒序；{@code summary} 是**这批 items** 的
     * {@code {total, open, unhandled, byLevel, byText}} —— {@code truncated} 为 true 时说明还有
     * 更早的没取回来，前端应提示缩小时间范围。</p>
     *
     * <p>{@code serviceId} 不传 = **整个空间（含子空间）**：把该空间及其子树下所有服务的告警合成
     * 一条时间倒序的清单，每条 item 自带 {@code serviceId}（见 {@code ModbusAlarmCodec}），
     * 前端据此认领归属。这一条顶掉前端「拿服务清单、再逐服务各发一次」的扇出 ——
     * 告警页要的正是「这个项目现在哪儿在告警」，N 条请求换 1 条，且合并与截断的口径在后端只有一份。
     * 此时响应里**不出 {@code serviceId} 键**：它不是某个服务的结果。</p>
     *
     * <p>**为什么含子空间**：前端两页的服务清单来自 {@code /space/graph/{rootId}}，那张图取的就是
     * 整棵子树（项目 = 根空间 + 子空间，设备可以挂在任一子空间上）。告警清单若只查根空间自己的服务，
     * 子空间里的服务就会「表里列着、告警栏永远是空的」—— 把「没查」显示成「没问题」。</p>
     *
     * <p>{@code open} / {@code handled} 不传 = 不限；{@code level} 不传 = 所有级别。
     * 三个「不传」都是**不限**而不是「都查」的语义差别：只有 {@code open} / {@code handled} 是布尔，
     * 它们的 null 与 false 必须区分开（「只看已恢复」不能等于「不限」），所以类型用 {@code Boolean}
     * 而不是 {@code boolean}。</p>
     */
    @GET
    @Path("/many/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> many(
            @PathParam("spaceId") String spaceId,
            @QueryParam("serviceId") String serviceId,
            @QueryParam("transactionIndex") Integer transactionIndex,
            @QueryParam("field") String field,
            @QueryParam("level") String level,
            @QueryParam("open") Boolean open,
            @QueryParam("handled") Boolean handled,
            @QueryParam("from") Long from,
            @QueryParam("to") Long to,
            @QueryParam("limit") Integer limit
    ) {
        logger.infov("query modbus alarms, space: {0}, service: {1}, level: {2}",
                spaceId, serviceId, level);
        boolean wholeSpace = serviceId == null || serviceId.isBlank();
        return requireSpaceMember(spaceId)
                .chain(ignored -> wholeSpace
                        ? serviceService.subtreeServiceIds(spaceId)
                        .chain(ids -> queryService.alarmsOfServices(ids, transactionIndex, field, level,
                                open, handled, millis(from), millis(to), limit))
                        : queryService.alarms(serviceId, transactionIndex, field, level,
                        open, handled, millis(from), millis(to), limit))
                .map(result -> {
                    JsonObject body = new JsonObject()
                            .put("from", result.from().getTime())
                            .put("to", result.to().getTime())
                            .put("limit", result.limit())
                            .put("truncated", result.truncated())
                            .put("items", ModbusAlarmCodec.encode(result.items()))
                            .put("summary", ModbusAlarmCodec.encodeSummary(result.items()));
                    if (!wholeSpace) {
                        body.put("serviceId", serviceId);
                    }
                    return OxResponse.ok(body);
                })
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /**
     * 处理一条告警：无 body，处理人取当前账号；返回**更新后的那条**（页面就地替换，不整页刷新）。
     *
     * <p><b>为什么要在写之前判一次归属</b>：路径上的 {@code spaceId} 只证明「你是这个空间的成员」，
     * 不证明「这条告警属于这个空间」。不判的话，拿任何一个空间的成员身份 + 一条从别处看来的告警 id
     * 就能改别人的数据。判法是「这条告警的 {@code serviceId} 在传入空间的子树里吗」——
     * 与列表查询用的是同一个 {@code subtreeServiceIds}，所以「列表里看得到的就能点」恰好成立。</p>
     *
     * <p>重复点击 / 并发点击算成功（理由见 {@link ModbusAlarmService#handle}）；
     * 行不在了才是 not found。</p>
     */
    @POST
    @Path("/handle/{spaceId}/{id}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> handle(
            @PathParam("spaceId") String spaceId,
            @PathParam("id") String id
    ) {
        logger.infov("handle modbus alarm, space: {0}, id: {1}", spaceId, id);
        // 先挡非法 id：Panache 的 findById(String) 会在构造 ObjectId 时就抛，
        // 而那是个普通 RuntimeException，落不到下面那个 IllegalArgumentException 的兜底里
        if (id == null || !ObjectId.isValid(id)) {
            return Uni.createFrom().item(OxResponse.error("unknown modbus alarm id: " + id));
        }
        ObjectId alarmId = new ObjectId(id);
        Person who = currentUser();

        return requireSpaceMember(spaceId)
                .chain(ignored -> queryService.findById(alarmId))
                .chain(row -> {
                    if (row == null) {
                        return Uni.createFrom().failure(
                                new IllegalArgumentException("modbus alarm not found: " + id));
                    }
                    return serviceService.subtreeServiceIds(spaceId)
                            .map(ids -> {
                                if (row.serviceId == null || !ids.contains(row.serviceId)) {
                                    throw new IllegalArgumentException("No access to this alarm");
                                }
                                return row;
                            });
                })
                .chain(ignored -> alarmService.handle(alarmId, who))
                .map(updated -> OxResponse.ok(ModbusAlarmCodec.encode(updated)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    /** 空间成员（告警的读与处理同口径：回执不是改配置，与 invoke 一样只要求成员）。 */
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
