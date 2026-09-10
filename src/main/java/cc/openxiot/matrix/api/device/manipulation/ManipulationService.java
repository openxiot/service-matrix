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

    public Uni<Device> addDevice(String orgId, DeviceAddRequest req) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        String body = new JsonObject()
                .put("did", req.did)
                .put("key", req.key)
                .encode();
        return client.addDevice(appId, authorization, body)
                .map(this::getJsonObject)
                .map(DeviceCodec::decode);
    }

    public Uni<JsonObject> removeDevice(String orgId, String did) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.removeDevice(appId, authorization, did)
                .map(this::getJsonObject);
    }

    public Uni<JsonObject> getDevice(String orgId, String did) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.getDevice(appId, authorization, did)
                .map(this::getJsonObject);
    }

    /** 批量添加：把 DeviceAddRequest 数组编码为 manipulation 需要的 [{did,key}] 再转发 */
    public Uni<List<Device>> addDevices(String orgId, List<DeviceAddRequest> req) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
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

    public Uni<JsonObject> removeDevices(String orgId, List<String> did) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.removeDevices(appId, authorization, did)
                .map(this::getJsonObject);
    }

    public Uni<JsonObject> getDevices(String orgId, List<String> did) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.getDevices(appId, authorization, did)
                .map(this::getJsonObject);
    }

    public Uni<JsonArray> getShadows(String orgId, List<String> did) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.getShadows(appId, authorization, did)
                .map(this::getJsonArray);
    }

    public Uni<JsonArray> getProperties(String orgId, List<String> pid) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.getProperties(appId, authorization, pid)
                .map(this::getJsonArray);
    }

    public Uni<JsonArray> setProperties(String orgId, String body) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.setProperties(appId, authorization, body)
                .map(this::getJsonArray);
    }

    public Uni<JsonArray> invokeActions(String orgId, String body) {
        logger.infov("invokeActions: {0}", body);

        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.invokeActions(appId, authorization, body)
                .map(this::getJsonArray);
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
