package cc.openxiot.matrix.api.modbus.service;

import cc.openxiot.matrix.api.device.manipulation.ManipulationService;
import cc.openxiot.matrix.api.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.api.modbus.failure.ModbusInvokeException;
import cc.openxiot.matrix.api.modbus.history.ModbusHistoryService;
import cc.openxiot.matrix.api.modbus.poll.ModbusPollBaselines;
import cc.openxiot.matrix.api.modbus.poll.ModbusPollKey;
import cc.openxiot.matrix.api.modbus.poll.ModbusPollReconciler;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import cc.openxiot.matrix.db.modbus.service.DeviceOn;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import cn.geekcity.xiot.spec.codec.vertx.operation.ActionOperationCodec;
import cn.geekcity.xiot.spec.operation.ActionOperation;
import cn.geekcity.xiot.spec.operation.ArgumentOperation;
import cn.geekcity.xiot.spec.xid.ActionID;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Modbus 服务的业务逻辑：定义维护（增删改查）+ 方法调用。
 *
 * <p>调用只读一次 {@link ModbusServiceRepository}：拿到的文档里已经有「父设备调用坐标 + 请求帧 +
 * 应答解析规则」，据此组装成父设备的 {@code ActionOperation} 交给 manipulation 服务执行
 * （DTU 的“发送”方法把帧透传到 RS485），再把回传的应答帧按 response 规则解成「字段 → 值」。</p>
 *
 * <p>调用只经 manipulation 服务、不读别的库：定义的「坐标 + 请求帧 + 应答规则」在创建时已展开落库，
 * 故调用不碰点表配置，也不需要产品实例定义。</p>
 *
 * <p>定义内容的校验不在这里，见 {@link ModbusServiceValidator}。</p>
 */
@ApplicationScoped
public class ModbusServiceService {

    /** 定义格式版本号：当前只认 1（function 内部结构演进时递增）。 */
    private static final int VERSION = 1;

    @Inject
    ModbusServiceRepository repo;

    @Inject
    ManipulationService manipulationService;

    @Inject
    ModbusHistoryService historyService;

    /** 轮询任务表的对账（写入路径挂这里，见 Modbus-Service-Poller.md 第三节）。 */
    @Inject
    ModbusPollReconciler pollReconciler;

    @Inject
    Logger logger;

    @ConfigProperty(name = "modbus.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    /** 启动只做建索引（device.did / device.space.spaceId），沿用点表那个开关。 */
    void init(@Observes StartupEvent event) {
        if (!indexEnabled) {
            return;
        }
        repo.ensureIndexes().await().indefinitely();
    }

    // ===== 定义维护 =====

    public Uni<ModbusService> create(ModbusService service) {
        String err = ModbusServiceValidator.validate(service);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        if (service.version == null) {
            service.version = VERSION;
        }
        // id 不在此生成：主键由 Panache/Mongo 在落库时生成（见 ModbusService extends ReactivePanacheMongoEntity）
        return repo.insert(service)
                .chain(ignored -> reconcilePolls(pollReconciler.applyService(service)))
                .replaceWith(service);
    }

    public Uni<ModbusService> update(String id, ModbusService patch) {
        String err = checkId(id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        if (patch == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("body is required"));
        }

        return repo.findById(id)
                .onItem().transformToUni(existing -> {
                    if (existing == null) {
                        return Uni.createFrom().failure(
                                new IllegalArgumentException("modbus service not found: " + id));
                    }
                    // 校验一份「合并后」的视图：patch 里缺省的字段保留既有值（与点表 update 同口径，
                    // 避免只改一部分的请求体把其余内容清空），再整体写回。
                    ModbusService merged = merge(existing, patch);
                    String contentErr = ModbusServiceValidator.validate(merged);
                    if (contentErr != null) {
                        return Uni.createFrom().failure(new IllegalArgumentException(contentErr));
                    }
                    if (patch.updater != null) {
                        merged.updater = patch.updater;
                    }
                    // 定义变了要跟着改两样派生数据：轮询任务（interval / 方法增删）与影子（废弃字段 / 孤儿方法）
                    return repo.replace(merged)
                            .chain(ignored -> reconcilePolls(pollReconciler.applyService(merged)))
                            .chain(ignored -> reconcilePolls(pollReconciler.reconcileShadows(merged)))
                            .replaceWith(merged);
                });
    }

    /**
     * 按 id 取单条（不带 org 约束，鉴权在资源层）。查不到按 not found 失败。
     */
    public Uni<ModbusService> get(String id) {
        String err = checkId(id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return repo.findById(id)
                .onItem().transformToUni(existing -> existing == null
                        ? Uni.createFrom().failure(new IllegalArgumentException("modbus service not found: " + id))
                        : Uni.createFrom().item(existing));
    }

    /** 列出设备落点在该空间的服务（空间口径的清单，鉴权在资源层）。 */
    public Uni<List<ModbusService>> listBySpaceId(String spaceId) {
        if (spaceId == null || spaceId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("spaceId is required"));
        }
        return repo.listBySpaceId(spaceId);
    }

    public Uni<List<ModbusService>> listByParent(String did) {
        if (did == null || did.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("did is required"));
        }
        return repo.listByParent(did);
    }

    /**
     * 按空间列出服务的精简信息（空间图用）：只读 id 与 name，整份定义（functions / response）不上网络。
     *
     * <p>返回的 {@code ModbusService} 只有 {@code id} / {@code name} 有值，别当完整定义用。</p>
     */
    public Uni<List<ModbusService>> getBriefBySpaceId(String spaceId) {
        if (spaceId == null || spaceId.isBlank()) {
            return Uni.createFrom().item(List.of());
        }
        return repo.findBriefBySpaceId(spaceId);
    }

    /**
     * 按 id 删除（不带 org 约束）：权限（该空间的管理员）已在资源层判过，这里只管删。
     * 删除条数为 0 时按 not found 失败。服务的影子与历史一并清掉（见 Modbus-History.md 第六节），
     * 轮询任务同理 —— 定义没了，任务再去采就是拿一份不存在的定义去调设备。
     */
    public Uni<Long> deleteById(String id) {
        String err = checkId(id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return repo.deleteById(id)
                .onItem().transformToUni(count -> count == null || count == 0L
                        ? Uni.createFrom().failure(new IllegalArgumentException("modbus service not found: " + id))
                        : purgeHistory(List.of(id))
                                .chain(ignored -> reconcilePolls(pollReconciler.applyRemoval(List.of(id))))
                                .replaceWith(count));
    }

    /**
     * 按依赖设备批量删除（不限组织）：删除设备时级联清理该设备下的服务与其采集历史，
     * 返回删除条数（该设备下没有服务时为 0，不算失败）。
     *
     * <p>先收下服务 id 再删服务行：删完就查不出来了，而历史的清理需要这些 id ——
     * 轮询任务的清理也要（任务表按 serviceId 删）。</p>
     */
    public Uni<Long> deleteByDeviceDids(List<String> dids) {
        if (dids == null || dids.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return repo.findIdsByDeviceDids(dids)
                .chain(ids -> repo.deleteByDeviceDids(dids)
                        .chain(count -> purgeHistory(ids)
                                .chain(ignored -> reconcilePolls(pollReconciler.applyRemoval(ids)))
                                .replaceWith(count)));
    }

    // ===== 方法调用 =====

    /**
     * 调用一个方法：读一次服务定义 → 组装父设备调用 → 按 response 规则解应答。
     *
     * <p>返回「字段 → 值」；写方法（response 为空）应答是请求回显、没有读值，返回空对象。
     * 父设备报错、帧非法、CRC 不符、异常码等一律以 {@link IllegalArgumentException} 抛出，
     * 由 Resource 转成失败响应 —— **记录失败不改变这个响应**，见 {@link #recordFailure}。</p>
     *
     * <p>{@code ownerId} 只往下传给 manipulation（设备归属，是**设备所在根空间 id**，见
     * {@link cc.openxiot.matrix.api.device.manipulation.ManipulationService}），取服务本身不按 org 约束：
     * 鉴权（该空间的成员）已在资源层判过。</p>
     */
    public Uni<JsonObject> invoke(String ownerId, String serviceId, Integer functionIndex) {
        return invoke(ownerId, serviceId, functionIndex, null);
    }

    /**
     * 轮询调用（见 Modbus-Service-Poller.md）：与手动 {@link #invoke} **完全同一条执行路径**，
     * 只多一个内存基准（{@code baselines}，null = 不吃内存基准、每次读库），
     * 少一次鉴权（轮询没有请求方，归属由轮询器按设备解析）。
     *
     * <p>写成一个可空参数而不是另起一条路径：两种调用的差别**只有这一处**，
     * 分成两套反而会让「轮询采到的值」和「手动点一下采到的值」落库口径慢慢分叉。</p>
     */
    public Uni<JsonObject> invokePolled(String ownerId, String serviceId, Integer functionIndex,
                                        ModbusPollBaselines baselines) {
        return invoke(ownerId, serviceId, functionIndex, baselines);
    }

    private Uni<JsonObject> invoke(String ownerId, String serviceId, Integer functionIndex,
                                   ModbusPollBaselines baselines) {
        String err = checkId(serviceId);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        if (functionIndex == null || functionIndex < 1) {
            return Uni.createFrom().failure(new IllegalArgumentException("function is required"));
        }

        return repo.findById(serviceId).onItem()
                .transformToUni(service -> service == null
                        ? Uni.createFrom().failure(new IllegalArgumentException("modbus service not found: " + serviceId))
                        : invokeFunction(ownerId, service, functionIndex, baselines));
    }

    private Uni<JsonObject> invokeFunction(String ownerId, ModbusService service, int functionIndex,
                                           ModbusPollBaselines baselines) {
        ModbusServiceFunction function = findFunction(service, functionIndex);
        if (function == null) {
            // 方法不存在是请求写错了，不算「执行失败」：没有可归因的 function，不进故障记录
            return Uni.createFrom().failure(new IllegalArgumentException("modbus service function not found: index=" + functionIndex));
        }

        return execute(ownerId, service, function, baselines)
                .onFailure().call(error -> recordFailure(service, function, error, baselines));
    }

    /**
     * 真正执行一次调用：调用坐标检查 → 交给 manipulation → 解应答 → 记历史。
     *
     * <p>全过程**不抛同步异常**（连请求帧的组装都放进 {@code Uni} 里）：上面那层
     * {@code onFailure().call(...)} 只拦得住经 {@code Uni} 流转的失败，同步抛出的异常会直接绕过去 ——
     * 那次失败就既不进故障记录、也不在影子上留故障态。</p>
     *
     * <p>每个失败点都抛带分类的 {@link ModbusInvokeException}：分类只有在这里定得准
     * （是链路失败、父设备报错还是无应答，各自的排查方向完全不同），见 {@link ModbusFailureType}。</p>
     */
    private Uni<JsonObject> execute(String ownerId, ModbusService service, ModbusServiceFunction function,
                                    ModbusPollBaselines baselines) {
        String coordinateErr = ModbusServiceValidator.checkDevice(service.device);
        if (coordinateErr != null) {
            return Uni.createFrom().failure(
                    ModbusInvokeException.of(ModbusFailureType.CONFIG_ERROR, coordinateErr));
        }

        return Uni.createFrom().item(() -> requestFrame(service, function))
                .chain(body -> manipulationService.invokeActions(ownerId, body)
                        .onFailure().transform(ModbusServiceService::transportFailure))
                .map(ActionOperationCodec.RESULT::decode)
                .map(results -> results.isEmpty() ? null : results.getFirst())
                .map(result -> decodeFields(function, result))
                .chain(values -> recordHistory(service, function, values, baselines).replaceWith(values));
    }

    /**
     * 调用链路本身的失败（manipulation 不可用、请求发不出去）：与设备侧故障分开 ——
     * 前者查服务间调用，后者查现场，合并成一类会把排查方向带偏。
     *
     * <p>包一层是为了保证异常**一定带 message**：链路异常常常没有 message，
     * 落到故障记录里就是一串 null，什么也说明不了。</p>
     */
    private static ModbusInvokeException transportFailure(Throwable cause) {
        if (cause instanceof ModbusInvokeException e) {
            return e;
        }
        String message = cause.getMessage();
        return ModbusInvokeException.of(ModbusFailureType.TRANSPORT_ERROR,
                message == null || message.isBlank() ? cause.getClass().getSimpleName() : message,
                cause);
    }

    /** 一次调用一帧：依赖设备的动作只有一个入参，请求帧原样填进去。 */
    private static String requestFrame(ModbusService service, ModbusServiceFunction function) {
        DeviceOn device = service.device;
        ActionOperation action = new ActionOperation(new ActionID(device.did, device.siid, device.aiid));
        action.in().put(device.argument, new ArgumentOperation(device.argument, function.request));
        return ActionOperationCodec.QUERY.encode(List.of(action)).toString();
    }

    /**
     * 把这次读到的值记进采集历史（见 Modbus-History.md）。
     *
     * <p>历史是**副作用**：写失败只记日志，不让 invoke 跟着失败 —— 值已经拿到了。
     * 影子因此停在旧值，下次采集会把同一次变化再判一遍并重试，不会丢。</p>
     *
     * <p><b>内存基准只在这一个地方分叉</b>（见 Modbus-Service-Poller.md 第四节）：
     * {@code baselines == null}（手动调用）时基准永远从库里读；轮询时缓存里那份可用就直接用，
     * 不可用（没缓存 / 故障态）回退到读库。写完把新基准放回缓存 —— 返回 null
     * （这次什么都没写）时保留旧的，库里的状态并没有变。</p>
     *
     * <p>单副本前提下才这么省：api 副本（N 个）上这个缓存永远是空的，所以手动 invoke 自然就是每次读库，
     * 不需要额外判断「我在哪个副本上」。</p>
     */
    private Uni<Void> recordHistory(ModbusService service, ModbusServiceFunction function, JsonObject values,
                                    ModbusPollBaselines baselines) {
        ModbusPollKey key = baselines == null
                ? null
                : new ModbusPollKey(service.id.toHexString(), function.index);
        ModbusServiceShadow cached = baselines == null ? null : baselines.get(key);

        Uni<ModbusServiceShadow> recorded = cached != null
                ? historyService.record(service, function, values, cached)
                : historyService.record(service, function, values);
        return recorded
                .onItem().invoke(next -> {
                    if (baselines != null) {
                        baselines.put(key, next);
                    }
                })
                .onFailure().invoke(e -> logger.warnv(
                        "record modbus history failed, service: {0}, function: {1}, error: {2}",
                        service.id, function.index, e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * 记一次执行失败（见 Modbus-History.md 第二节）。
     *
     * <p>同样是**副作用**：记录写失败只记日志，不让原错误被替换 —— 调用方该看到的失败原因必须原样传下去，
     * 不能因为「记录失败」而变成另一个错误。</p>
     *
     * <p>基准的处理与 {@link #recordHistory} 同一处口径。注意故障态下的基准**必须**回退到读库：
     * 缓存的 {@code lastError} 可能已经被 api 副本上的手动调用清掉了，拿它当基准会把一次新的同类故障
     * 并进上一次的账上（见 {@code ModbusPollBaselines.usable}）。</p>
     */
    private Uni<Void> recordFailure(ModbusService service, ModbusServiceFunction function, Throwable error,
                                    ModbusPollBaselines baselines) {
        ModbusPollKey key = baselines == null
                ? null
                : new ModbusPollKey(service.id.toHexString(), function.index);
        ModbusServiceShadow cached = baselines == null ? null : baselines.get(key);

        Uni<ModbusServiceShadow> recorded = cached != null
                ? historyService.recordFailure(service, function, error, cached)
                : historyService.recordFailure(service, function, error);
        return recorded
                .onItem().invoke(next -> {
                    if (baselines != null) {
                        baselines.put(key, next);
                    }
                })
                .onFailure().invoke(e -> logger.warnv(
                        "record modbus failure failed, service: {0}, function: {1}, error: {2}",
                        service.id, function.index, e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * 级联清理采集历史与失败记录（删除服务 / 删除设备时）：同样只记日志 ——
     * 服务行已经删掉了，清理失败留下的是存储垃圾，不该把这次删除回滚成失败。
     */
    private Uni<Void> purgeHistory(List<String> serviceIds) {
        return historyService.purge(serviceIds)
                .onFailure().invoke(e -> logger.warnv(
                        "purge modbus history failed, services: {0}, error: {1}",
                        serviceIds, e.getMessage()))
                .onFailure().recoverWithNull();
    }

    /**
     * 轮询任务表的对账，同样是**副作用**：失败只记日志。
     *
     * <p>与历史的处置不同在于「谁来兜底」：历史写失败只能靠下一次采集重试，
     * 而任务表是纯派生数据 —— 下一次对账（写入路径 / 周期 / poller 启动）会把它整个修回来，
     * 所以更没有必要让定义维护跟着失败。</p>
     *
     * <p>也正因为它是写入路径触发的，<b>买到的是「表始终是对的」，不是「改动立即生效」</b>：
     * 定义是在某个 api 副本上改的，poller 是另一个进程，它要等下一次周期对账（缺省 5 分钟）才知道
     * （见 Modbus-Service-Poller.md 第三节）。</p>
     */
    private Uni<Void> reconcilePolls(Uni<Void> reconcile) {
        return reconcile
                .onFailure().invoke(e -> logger.warnv(
                        "reconcile modbus polls failed, error: {0}", e.getMessage()))
                .onFailure().recoverWithNull();
    }

    /** 父设备应答 → 方法返回值。 */
    private static JsonObject decodeFields(ModbusServiceFunction function, ActionOperation result) {
        if (result == null) {
            throw ModbusInvokeException.of(ModbusFailureType.NO_RESPONSE, "no response from parent device");
        }
        if (result.isError()) {
            // 状态码一起带上：它是父设备侧给的原始信息，前端按分类聚合后要靠它下钻
            throw ModbusInvokeException.remote(ModbusFailureType.DEVICE_ERROR, result.status(),
                    result.description() == null
                            ? "parent device error, status=" + result.status()
                            : result.description());
        }

        List<ModbusServiceField> fields = function.response;
        if (fields == null || fields.isEmpty()) {
            // 写方法：应答是请求回显，没有读值
            return new JsonObject();
        }
        return ModbusResponseParser.parse(fields, frameOf(result));
    }

    /** 从父设备应答的出参里取回传的数据帧（父动作只有一个出参，即应答帧）。 */
    private static String frameOf(ActionOperation result) {
        for (ArgumentOperation argument : result.out().values()) {
            if (!argument.values().isEmpty() && argument.values().getFirst() != null) {
                return argument.values().getFirst().toString();
            }
        }

        // 父设备说自己成功、却没带回帧：归「无应答」—— 设备侧没给出可用的东西
        throw ModbusInvokeException.of(ModbusFailureType.NO_RESPONSE,
                "no frame in parent device response, status=" + result.status());
    }

    private static ModbusServiceFunction findFunction(ModbusService service, int index) {
        if (service.functions == null) {
            return null;
        }
        return service.functions.stream()
                .filter(function -> function != null && function.index != null && function.index == index)
                .findFirst()
                .orElse(null);
    }

    // ===== 校验 =====

    /** 请求参数（id）的检查；实体内容校验见 {@link ModbusServiceValidator}。 */
    private String checkId(String id) {
        if (id == null || id.isBlank()) {
            return "id is required";
        }
        return null;
    }

    /** patch 缺省的字段保留既有值（只改一部分的请求体不清空其余内容）。 */
    private static ModbusService merge(ModbusService existing, ModbusService patch) {
        ModbusService merged = new ModbusService();
        merged.id = existing.id;
        merged.creator = existing.creator;
        merged.updater = existing.updater;
        merged.name = patch.name != null ? patch.name : existing.name;
        merged.version = patch.version != null ? patch.version : existing.version;
        merged.configId = patch.configId != null ? patch.configId : existing.configId;
        merged.device = patch.device != null ? patch.device : existing.device;
        merged.functions = patch.functions != null ? patch.functions : existing.functions;
        return merged;
    }
}
