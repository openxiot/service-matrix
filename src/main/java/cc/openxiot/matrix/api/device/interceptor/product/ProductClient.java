package cc.openxiot.matrix.api.device.interceptor.product;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@Path("/v1/product")
@RegisterRestClient(configKey = "product-center-api")
public interface ProductClient {

    @GET
    @Path("/instance/one/{type}")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<String> getInstance(@PathParam("type") String type);
}
