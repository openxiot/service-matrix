package cc.openxiot.matrix.api.device;

import cc.openxiot.matrix.api.device.interceptor.DeviceInterceptor;
import cc.openxiot.matrix.api.device.manipulation.ManipulationService;
import cc.openxiot.matrix.api.device.request.DeviceAddRequest;
import cc.openxiot.matrix.db.device.DeviceRepository;
import cn.geekcity.xiot.spec.codec.vertx.operation.ActionOperationCodec;
import cn.geekcity.xiot.spec.device.Device;
import cn.geekcity.xiot.spec.operation.ActionOperation;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 设备操作入口：Resource 层统一经它访问 manipulation 服务，不直接注入 {@link ManipulationService}。
 * <p>
 * 其中面向设备的四类操作——读影子（getShadows）、读属性（getProperties）、写属性（setProperties）、
 * 执行方法（invokeActions）——会先经过 {@link DeviceInterceptor}：目前只有执行方法有拦截逻辑
 * （modbus 虚拟设备翻译成父设备调用），其余三类仍是直接透传。
 */
@ApplicationScoped
public class DeviceManipulator {

    @Inject
    ManipulationService service;

    @Inject
    DeviceRepository repository;

    @Inject
    DeviceInterceptor interceptor;

    public Uni<Device> addDevice(String orgId, DeviceAddRequest req) {
        return service.addDevice(orgId, req);
    }

    public Uni<List<Device>> addDevices(String orgId, List<DeviceAddRequest> req) {
        return service.addDevices(orgId, req);
    }

    public Uni<JsonObject> removeDevice(String orgId, String did) {
        return service.removeDevice(orgId, did);
    }

    public Uni<JsonObject> removeDevices(String orgId, List<String> did) {
        return service.removeDevices(orgId, did);
    }

    public Uni<JsonArray> getShadows(String orgId, List<String> did) {
//        intercept(interceptor -> interceptor.getShadows(orgId, did));
        return service.getShadows(orgId, did);
    }

    public Uni<JsonArray> getProperties(String orgId, List<String> pid) {
//        intercept(interceptor -> interceptor.getProperties(orgId, pid));
        return service.getProperties(orgId, pid);
    }

    public Uni<JsonArray> setProperties(String orgId, String body) {
//        intercept(interceptor -> interceptor.setProperties(orgId, body));
        return service.setProperties(orgId, body);
    }

    /**
     * 执行一批方法调用：按设备分组，逐台调用后再按请求顺序汇合结果。
     *
     * <p>请求体是 xiot ActionOperation 的 QUERY 数组（aid + in），返回体是 RESULT 数组
     * （aid + status + out），与 manipulation 服务的出入口径一致，前端按 {@code ActionOperationCodec.Result} 解析。</p>
     */
    public Uni<JsonArray> invokeActions(String orgId, String body) {
        List<ActionOperation> operations = ActionOperationCodec.QUERY.decode(new JsonArray(body));
        if (operations.isEmpty()) {
            return Uni.createFrom().item(new JsonArray());
        }

        // 同一台设备的多个方法一起下发（可能跨设备，故先按 did 分组，保持请求里首次出现的顺序）
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < operations.size(); i++) {
            groups.computeIfAbsent(operations.get(i).did(), did -> new ArrayList<>()).add(i);
        }

        List<Map.Entry<String, List<Integer>>> entries = new ArrayList<>(groups.entrySet());
        List<Uni<List<ActionOperation>>> calls = new ArrayList<>(entries.size());
        for (Map.Entry<String, List<Integer>> entry : entries) {
            List<ActionOperation> actions = entry.getValue().stream().map(operations::get).toList();
            calls.add(invokeActions(orgId, entry.getKey(), actions));
        }

        return Uni.join().all(calls).andFailFast()
                .map(results -> merge(entries, results, operations.size()))
                .map(ActionOperationCodec.RESULT::encode);
    }

    /** 按分组时记录的下标把各设备的结果放回请求顺序（结果数按请求条数一一对应）。 */
    private static List<ActionOperation> merge(List<Map.Entry<String, List<Integer>>> entries,
                                               List<List<ActionOperation>> results, int size) {
        List<ActionOperation> merged = new ArrayList<>(Collections.nCopies(size, null));
        for (int g = 0; g < entries.size(); g++) {
            List<Integer> indexes = entries.get(g).getValue();
            List<ActionOperation> list = results.get(g);
            for (int i = 0; i < indexes.size() && i < list.size(); i++) {
                merged.set(indexes.get(i), list.get(i));
            }
        }

        merged.removeIf(Objects::isNull);
        return merged;
    }

    private Uni<List<ActionOperation>> invokeActions(String orgId, String did, List<ActionOperation> actions) {
        return repository.findByDeviceId(did)
                .chain(device -> {
                    // modbus 虚拟设备自身没有通信能力：交给拦截器翻译成父设备（DTU）的“发送”调用
                    if (device != null && "modbus".equalsIgnoreCase(device.protocol)) {
                        return interceptor.invokeActions(orgId, did, actions);
                    }

                    String body = ActionOperationCodec.QUERY.encode(actions).toString();
                    return service.invokeActions(orgId, body)
                            .map(ActionOperationCodec.RESULT::decode);
                });
    }
}
