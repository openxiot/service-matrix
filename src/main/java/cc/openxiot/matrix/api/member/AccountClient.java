package cc.openxiot.matrix.api.member;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * service-account 虚拟组织（项目成员）系统接口的客户端。
 * 鉴权走 X-System-Token（owner token，HMAC-SHA256 + openxiot.internal.secret）。
 */
@RegisterRestClient(configKey = "account-service")
@Path("/user/organization")
public interface AccountClient {

    @POST
    @Path("/virtual")
    @Consumes(MediaType.APPLICATION_JSON)
    Uni<String> upsertVirtual(
            @HeaderParam("X-System-Token") String systemToken,
            VirtualOrgRequest req
    );

    @DELETE
    @Path("/virtual/{rootSpaceId}")
    Uni<String> deleteVirtual(
            @HeaderParam("X-System-Token") String systemToken,
            @PathParam("rootSpaceId") String rootSpaceId,
            @QueryParam("memberId") String memberId
    );
}
