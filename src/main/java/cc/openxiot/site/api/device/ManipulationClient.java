package cc.openxiot.site.api.device;

import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "manipulation-api")
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
public interface ManipulationClient {

    @POST
    @Path("/devices")
    @Consumes(MediaType.APPLICATION_JSON)
    JsonObject addDevices(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            String body
    );

    @DELETE
    @Path("/devices")
    JsonObject removeDevices(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            @QueryParam("did") List<String> did
    );

    @GET
    @Path("/devices")
    JsonObject getDevices(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            @QueryParam("did") List<String> did
    );

    @GET
    @Path("/shadows")
    JsonObject getShadows(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            @QueryParam("did") List<String> did
    );

    @GET
    @Path("/properties")
    JsonObject getProperties(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            @QueryParam("pid") List<String> pid
    );

    @POST
    @Path("/properties")
    @Consumes(MediaType.APPLICATION_JSON)
    JsonObject setProperties(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            String body
    );

    @POST
    @Path("/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    JsonObject invokeActions(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            String body
    );
}
