package cc.openxiot.matrix.api.modbus.service;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.db.modbus.virtual.ModbusMapping;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;


@Path("/matrix/v1/modbus/service")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modbus Service", description = "Modbus Service API")
@RequestScoped
public class ModbusServiceResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    ModbusServiceService service;

    @Inject
    OrgAccessService orgAccessService;

    @GET
    public Uni<Response> hello() {
        logger.info("hello");
        return Uni.createFrom().item(OxResponse.ok("Hello from ModbusServiceResource"));
    }

    @POST
    @Path("/one")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> createOne(
            @HeaderParam("X-Org-Id") String orgId,
            ModbusMapping mapping
    ) {
        // TODO: 按 MODBUS2.md 实现（待 ModbusService 模型 + ModbusServiceRepository 落地后补齐）
        return Uni.createFrom().item(OxResponse.error("not implemented"));
    }

    private Uni<String> requireAdmin(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }
        return orgAccessService.checkAdmin(orgId, getUserId()).map(ignored -> orgId);
    }

    @DELETE
    @Path("/one")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> deleteOne(
            @HeaderParam("X-Org-Id") String orgId,
            @QueryParam("did") String did
    ) {
        // TODO: 按 MODBUS2.md 实现（待 ModbusService 模型 + ModbusServiceRepository 落地后补齐）
        return Uni.createFrom().item(OxResponse.error("not implemented"));
    }
}
