package cc.openxiot.site.api.device;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "manipulation-api")
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
public interface ManipulationClient {

    @POST
    @Path("/device")
    @Consumes(MediaType.APPLICATION_JSON)
    Uni<String> addDevice(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            String body
    );

    @DELETE
    @Path("/device/{did}")
    Uni<String> removeDevice(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            @PathParam("did") String did
    );

    @GET
    @Path("/device/{did}")
    Uni<String> getDevice(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            @PathParam("did") String did
    );

    @POST
    @Path("/devices")
    @Consumes(MediaType.APPLICATION_JSON)
    Uni<String> addDevices(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            String body
    );

    @DELETE
    @Path("/devices")
    Uni<String> removeDevices(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            @QueryParam("did") List<String> did
    );

    @GET
    @Path("/devices")
    Uni<String> getDevices(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            @QueryParam("did") List<String> did
    );

    @GET
    @Path("/shadows")
    Uni<String> getShadows(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            @QueryParam("did") List<String> did
    );

    @GET
    @Path("/properties")
    Uni<String> getProperties(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            @QueryParam("pid") List<String> pid
    );

    @POST
    @Path("/properties")
    @Consumes(MediaType.APPLICATION_JSON)
    Uni<String> setProperties(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            String body
    );

    @POST
    @Path("/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    Uni<String> invokeActions(
            @HeaderParam("appId") String appId,
            @HeaderParam("authorization") String authorization,
            String body
    );
}
