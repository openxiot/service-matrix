package cc.openxiot.site.api.device;

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

    public JsonObject addDevices(String authorization, String body) {
        return client.addDevices(appId, authorization, body);
    }

    public JsonObject removeDevices(String authorization, List<String> did) {
        return client.removeDevices(appId, authorization, did);
    }

    public JsonObject getDevices(String authorization, List<String> did) {
        return client.getDevices(appId, authorization, did);
    }

    public JsonObject getShadows(String authorization, List<String> did) {
        return client.getShadows(appId, authorization, did);
    }

    public JsonObject getProperties(String authorization, List<String> pid) {
        return client.getProperties(appId, authorization, pid);
    }

    public JsonObject setProperties(String authorization, String body) {
        return client.setProperties(appId, authorization, body);
    }

    public JsonObject invokeActions(String authorization, String body) {
        return client.invokeActions(appId, authorization, body);
    }
}
