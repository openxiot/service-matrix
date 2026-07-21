package cc.openxiot.site.api.device;

import cc.openxiot.common.util.JwtUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;

@ApplicationScoped
public class ManipulationService {

    @Inject
    @RestClient
    ManipulationClient client;

    @ConfigProperty(name = "manipulation.app-id", defaultValue = "openxiot.site")
    String appId;

    @ConfigProperty(name = "manipulation.jwt-secret", defaultValue = "this is a test key")
    String jwtSecret;

    public Uni<JsonObject> addDevices(String orgId, String body) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.addDevices(appId, authorization, body)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> removeDevices(String orgId, List<String> did) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.removeDevices(appId, authorization, did)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> getDevices(String orgId, List<String> did) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.getDevices(appId, authorization, did)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> getShadows(String orgId, List<String> did) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.getShadows(appId, authorization, did)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> getProperties(String orgId, List<String> pid) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.getProperties(appId, authorization, pid)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> setProperties(String orgId, String body) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.setProperties(appId, authorization, body)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> invokeActions(String orgId, String body) {
        String authorization = JwtUtil.createOwnerIdToken(orgId, jwtSecret);
        return client.invokeActions(appId, authorization, body)
                .map(JsonObject::new);
    }
}
