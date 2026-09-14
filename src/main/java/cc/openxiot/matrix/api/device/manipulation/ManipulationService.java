package cc.openxiot.matrix.api.device.manipulation;

import cc.openxiot.common.util.JwtUtil;
import cc.openxiot.matrix.api.device.request.DeviceAddRequest;
import cn.geekcity.xiot.spec.codec.vertx.device.DeviceCodec;
import cn.geekcity.xiot.spec.device.Device;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * 调 manipulation 的设备接口。每个方法都要一个 {@code ownerId} —— 它被签进下游 JWT 的
 * {@code ownerId} claim，是 manipulation 侧**设备归属的主键之一**：那边的归属记录是
 * {@code Ownership(did, appId, ownerId)}（库 {@code device}、集合 {@code ownership}），
 * 查不到就直接把这次操作标成 {@code DEVICE_ID_NOT_EXIST}（"device not owned"）。
 *
 * <p>所以它**不是「谁在调」**，而是「**设备属于谁**」：传的是**设备所在空间的根空间 id**
 * （{@link cc.openxiot.matrix.db.space.SpaceEntity#rootSpaceId()}）。历史上这里传的是
 * {@code X-Org-Id}（调用者所在组织），那是错的 —— 同一个组织下的不同项目会互相看见设备，
 * 而没有请求头的调用方（轮询器）则完全无值可传。</p>
 *
 * <p>设备密钥（{@code key}）不在这里：它由 manipulation 从归属记录里取，matrix 不持有。</p>
 */
@ApplicationScoped
public class ManipulationService {

    @Inject
    Logger logger;

    @Inject
    @RestClient
    ManipulationClient client;

    @ConfigProperty(name = "manipulation.app-id", defaultValue = "openxiot.matrix")
    String appId;

    @ConfigProperty(name = "manipulation.jwt-secret", defaultValue = "this is a test key")
    String jwtSecret;

    public Uni<Device> addDevice(String ownerId, DeviceAddRequest req) {
        String authorization = JwtUtil.createOwnerIdToken(ownerId, jwtSecret);
        String body = new JsonObject()
                .put("did", req.did)
                .put("key", req.key)
                .encode();
        return client.addDevice(appId, authorization, body)
                .map(this::getJsonObject)
                .map(DeviceCodec::decode);
    }

    public Uni<JsonObject> removeDevice(String ownerId, String did) {
        String authorization = JwtUtil.createOwnerIdToken(ownerId, jwtSecret);
        return client.removeDevice(appId, authorization, did)
                .map(this::getJsonObject);
    }

    public Uni<JsonObject> getDevice(String ownerId, String did) {
        String authorization = JwtUtil.createOwnerIdToken(ownerId, jwtSecret);
        return client.getDevice(appId, authorization, did)
                .map(this::getJsonObject);
    }

    /** 批量添加：把 DeviceAddRequest 数组编码为 manipulation 需要的 [{did,key}] 再转发 */
    public Uni<List<Device>> addDevices(String ownerId, List<DeviceAddRequest> req) {
        String authorization = JwtUtil.createOwnerIdToken(ownerId, jwtSecret);
        JsonArray devices = new JsonArray();
        for (DeviceAddRequest device : req) {
            devices.add(new JsonObject()
                    .put("did", device.did)
                    .put("key", device.key));
        }
        return client.addDevices(appId, authorization, devices.encode())
                .map(this::getJsonArray)
                .map(DeviceCodec::decode);
    }

    public Uni<JsonObject> removeDevices(String ownerId, List<String> did) {
        String authorization = JwtUtil.createOwnerIdToken(ownerId, jwtSecret);
        return client.removeDevices(appId, authorization, did)
                .map(this::getJsonObject);
    }

    public Uni<JsonObject> getDevices(String ownerId, List<String> did) {
        String authorization = JwtUtil.createOwnerIdToken(ownerId, jwtSecret);
        return client.getDevices(appId, authorization, did)
                .map(this::getJsonObject);
    }

    public Uni<JsonArray> getShadows(String ownerId, List<String> did) {
        String authorization = JwtUtil.createOwnerIdToken(ownerId, jwtSecret);
        return client.getShadows(appId, authorization, did)
                .map(this::getJsonArray);
    }

    public Uni<JsonArray> getProperties(String ownerId, List<String> pid) {
        String authorization = JwtUtil.createOwnerIdToken(ownerId, jwtSecret);
        return client.getProperties(appId, authorization, pid)
                .map(this::getJsonArray);
    }

    public Uni<JsonArray> setProperties(String ownerId, String body) {
        String authorization = JwtUtil.createOwnerIdToken(ownerId, jwtSecret);
        return client.setProperties(appId, authorization, body)
                .map(this::getJsonArray);
    }

    public Uni<JsonArray> invokeActions(String ownerId, String body) {
        logger.infov("invokeActions: {0}", body);

        String authorization = JwtUtil.createOwnerIdToken(ownerId, jwtSecret);
        return client.invokeActions(appId, authorization, body)
                .map(x -> {
                    JsonArray array =  getJsonArray(x);
                    logger.infov("{0}", array);
                    return array;
                });
    }

    private JsonObject getJsonObject(String resp) {
        JsonObject json = new JsonObject(resp);
        if (!Boolean.TRUE.equals(json.getBoolean("success"))) {
            throw new IllegalArgumentException(json.getString("message", "Unknown error"));
        }
        return json.getJsonObject("data");
    }

    private JsonArray getJsonArray(String resp) {
        JsonObject json = new JsonObject(resp);
        if (!Boolean.TRUE.equals(json.getBoolean("success"))) {
            throw new IllegalArgumentException(json.getString("message", "Unknown error"));
        }
        return json.getJsonArray("data");
    }
}
