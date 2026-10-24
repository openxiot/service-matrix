package cc.openxiot.matrix.api.modbus.polling;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.modbus.service.ModbusServiceService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.modbus.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.polling.ModbusPollingRepository;
import cc.openxiot.matrix.db.modbus.polling.codec.ModbusPollingCodec;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轮询任务的查询（见 Modbus-Service-Poller.md 第八节）：某个空间下服务的任务与它们的运行时状态。
 *
 * <p><b>另起一段路径</b>（{@code /matrix/v1/modbus/poll}，不挂在 service 或 history 之下）：
 * 任务表是「系统正在替我做什么」的视图，与「我配了什么」（定义）、「采到了什么」（历史）
 * 都是不同的东西，将来给运维单独授权时不用把另外两样一起放开。</p>
 *
 * <p><b>按空间鉴权</b>，与 {@code invoke} / 历史读同口径：路径上的 {@code spaceId} 只要**成员**。</p>
 *
 * <p>响应里的内存指标（{@code enabled} / {@code instanceId} / {@code queueSize} / {@code busyDevices} /
 * {@code inFlight} / {@code lastTickAt}）是**本进程**的读数，不是集群的：轮询器与 api 是同一份代码的
 * 两个部署，问到 api 副本时 {@code items} 照旧是对的（读库），但 {@code queueSize} 一定是 0
 * —— 它不跑调度。看「谁在采」要问 poller 那个副本，或者看每条任务的 {@code leaseOwner}。</p>
 */
@Path("/matrix/v1/modbus/polling")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modbus Polling", description = "Modbus 轮询任务 API")
@RequestScoped
public class ModbusPollingResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    ModbusServiceService serviceService;

    @Inject
    ModbusPollingRepository pollRepo;

    @Inject
    ModbusPollingScheduler scheduler;

    @Inject
    OrgAccessService orgAccessService;

    @Inject
    SpaceService spaceService;

    /**
     * 该空间下所有服务的轮询任务：{@code {spaceId, enabled, ..., items: [...]}}。
     *
     * <p>先取该空间的服务清单（只读 id / name，整份定义不上网络），再按 serviceId 批量取任务；
     * 服务名一起带在每条任务上，前端不必再查一遍定义才知道这条任务是谁的。</p>
     *
     * <p>任务表里没有的方法**不出现**在结果里 —— 没配周期或被 {@code polling=false} 关掉的方法
     * 就不该有任务，与「有任务但很久没跑」不是一回事（后者的 {@code lastRunAt} 会说话）。</p>
     */
    @GET
    @Path("/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> list(
            @PathParam("spaceId") String spaceId
    ) {
        logger.infov("list modbus poll tasks, space: {0}", spaceId);
        return requireSpaceMember(spaceId)
                .chain(ignored -> serviceService.getBriefBySpaceId(spaceId))
                .chain(briefs -> {
                    Map<String, String> nameById = new HashMap<>();
                    List<String> serviceIds = new java.util.ArrayList<>(briefs.size());
                    for (ModbusService brief : briefs) {
                        serviceIds.add(brief.id.toHexString());
                        nameById.put(brief.id.toHexString(), brief.name);
                    }
                    return pollRepo.listByServiceIds(serviceIds)
                            .map(polls -> body(spaceId, nameById, polls));
                })
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    // -- helper methods --

    private JsonObject body(String spaceId, Map<String, String> nameById, List<ModbusPolling> polls) {
        JsonArray items = new JsonArray();
        for (ModbusPolling poll : polls) {
            JsonObject item = ModbusPollingCodec.encode(poll);
            String name = poll.serviceId == null ? null : nameById.get(poll.serviceId);
            if (name != null) {
                item.put("serviceName", name);
            }
            items.add(item);
        }
        JsonObject body = new JsonObject()
                .put("spaceId", spaceId)
                // 这一项常常是 false：api 副本本来就不该跑调度，看到 false 不代表没人采
                .put("enabled", scheduler.isEnabled())
                .put("instanceId", scheduler.instanceId())
                .put("queueSize", scheduler.queueSize())
                .put("busyDevices", scheduler.busyDeviceCount())
                .put("inFlight", scheduler.inFlightCount())
                .put("items", items);
        if (scheduler.lastTickAt() > 0) {
            body.put("lastTickAt", scheduler.lastTickAt());
        }
        return body;
    }

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
}
