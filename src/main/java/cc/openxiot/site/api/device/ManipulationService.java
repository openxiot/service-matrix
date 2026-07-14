package cc.openxiot.site.api.device;

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

    public Uni<JsonObject> addDevices(String authorization, String body) {
        return client.addDevices(appId, authorization, body)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> removeDevices(String authorization, List<String> did) {
        return client.removeDevices(appId, authorization, did)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> getDevices(String authorization, List<String> did) {
        return client.getDevices(appId, authorization, did)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> getShadows(String authorization, List<String> did) {
        return client.getShadows(appId, authorization, did)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> getProperties(String authorization, List<String> pid) {
        return client.getProperties(appId, authorization, pid)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> setProperties(String authorization, String body) {
        return client.setProperties(appId, authorization, body)
                .map(JsonObject::new);
    }

    public Uni<JsonObject> invokeActions(String authorization, String body) {
        return client.invokeActions(appId, authorization, body)
                .map(JsonObject::new);
    }
}
