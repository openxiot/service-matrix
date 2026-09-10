package cc.openxiot.matrix.api.modbus.service;

import cc.openxiot.matrix.api.device.manipulation.ManipulationService;
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

import java.util.List;

/**
 * Modbus 服务的业务逻辑：定义维护（增删改查）+ 方法调用。
 *
 * <p>调用只读一次 {@link ModbusServiceRepository}：拿到的文档里已经有「父设备调用坐标 + 请求帧 +
 * 应答解析规则」，据此组装成父设备的 {@code ActionOperation} 交给 manipulation 服务执行
 * （DTU 的“发送”方法把帧透传到 RS485），再把回传的应答帧按 response 规则解成「字段 → 值」。</p>
 *
 * <p>与 MODBUS.md 的方案一（虚拟设备 + {@code DeviceInterceptor} 翻译）并存：本类不碰虚拟设备、
 * 点表和产品实例定义，也不经过 {@code DeviceManipulator}。</p>
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

    @ConfigProperty(name = "modbus.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    /** 启动只做建索引（orgId / device.did / device.space.spaceId），沿用点表那个开关。 */
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
        return repo.insert(service).replaceWith(service);
    }

    public Uni<ModbusService> update(String orgId, String id, ModbusService patch) {
        String err = checkId(orgId, id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        if (patch == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("body is required"));
        }

        return repo.findByIdAndOrg(id, orgId)
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
                    return repo.replace(merged).replaceWith(merged);
                });
    }

    public Uni<ModbusService> get(String orgId, String id) {
        String err = checkId(orgId, id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return repo.findByIdAndOrg(id, orgId)
                .onItem().transformToUni(existing -> existing == null
                        ? Uni.createFrom().failure(new IllegalArgumentException("modbus service not found: " + id))
                        : Uni.createFrom().item(existing));
    }

    public Uni<List<ModbusService>> listByOrg(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("orgId is required"));
        }
        return repo.listByOrg(orgId);
    }

    public Uni<List<ModbusService>> listByParent(String orgId, String did) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("orgId is required"));
        }
        if (did == null || did.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("did is required"));
        }
        return repo.listByParent(did, orgId);
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

    public Uni<Long> delete(String orgId, String id) {
        String err = checkId(orgId, id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return repo.deleteByIdAndOrg(id, orgId)
                .onItem().transformToUni(count -> count == null || count == 0L
                        ? Uni.createFrom().failure(new IllegalArgumentException("modbus service not found: " + id))
                        : Uni.createFrom().item(count));
    }

    // ===== 方法调用 =====

    /**
     * 调用一个方法：读一次服务定义 → 组装父设备调用 → 按 response 规则解应答。
     *
     * <p>返回「字段 → 值」；写方法（response 为空）应答是请求回显、没有读值，返回空对象。
     * 父设备报错、帧非法、CRC 不符、异常码等一律以 {@link IllegalArgumentException} 抛出，
     * 由 Resource 转成失败响应。</p>
     */
    public Uni<JsonObject> invoke(String orgId, String serviceId, Integer functionIndex) {
        String err = checkId(orgId, serviceId);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        if (functionIndex == null || functionIndex < 1) {
            return Uni.createFrom().failure(new IllegalArgumentException("function is required"));
        }

        return repo.findByIdAndOrg(serviceId, orgId).onItem()
                .transformToUni(service -> service == null
                        ? Uni.createFrom().failure(new IllegalArgumentException("modbus service not found: " + serviceId))
                        : invokeFunction(orgId, service, functionIndex));
    }

    private Uni<JsonObject> invokeFunction(String orgId, ModbusService service, int functionIndex) {
        ModbusServiceFunction function = findFunction(service, functionIndex);
        if (function == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("modbus service function not found: index=" + functionIndex));
        }

        String coordinateErr = ModbusServiceValidator.checkDevice(service.device);
        if (coordinateErr != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(coordinateErr));
        }

        // 一次调用一帧：依赖设备的动作只有一个入参，请求帧原样填进去
        DeviceOn device = service.device;
        ActionOperation action = new ActionOperation(new ActionID(device.did, device.siid, device.aiid));
        action.in().put(device.argument, new ArgumentOperation(device.argument, function.request));

        String body = ActionOperationCodec.QUERY.encode(List.of(action)).toString();
        return manipulationService.invokeActions(orgId, body)
                .map(ActionOperationCodec.RESULT::decode)
                .map(results -> results.isEmpty() ? null : results.getFirst())
                .map(result -> decodeFields(function, result));
    }

    /** 父设备应答 → 方法返回值。 */
    private static JsonObject decodeFields(ModbusServiceFunction function, ActionOperation result) {
        if (result == null) {
            throw new IllegalArgumentException("no response from parent device");
        }
        if (result.isError()) {
            throw new IllegalArgumentException(result.description() == null
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

        throw new IllegalArgumentException("no frame in parent device response, status=" + result.status());
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

    /** 请求参数（org / id）的检查；实体内容校验见 {@link ModbusServiceValidator}。 */
    private String checkId(String orgId, String id) {
        if (orgId == null || orgId.isBlank()) {
            return "orgId is required";
        }
        if (id == null || id.isBlank()) {
            return "id is required";
        }
        return null;
    }

    /** patch 缺省的字段保留既有值（只改一部分的请求体不清空其余内容）。 */
    private static ModbusService merge(ModbusService existing, ModbusService patch) {
        ModbusService merged = new ModbusService();
        merged.id = existing.id;
        merged.orgId = existing.orgId;
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
