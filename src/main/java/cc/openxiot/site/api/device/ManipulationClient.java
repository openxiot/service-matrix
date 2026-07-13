package cc.openxiot.site.api.device;

import jakarta.json.JsonObject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "manipulation-api")
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
public interface ManipulationClient {

    @GET
    @Path("/devices")
    JsonObject getDevices(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            @QueryParam("did") List<String> did
    );
}
