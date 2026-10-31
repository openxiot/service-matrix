package cc.openxiot.matrix.api.energy.config;

import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.energy.AbstractEnergyResource;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergyType;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * 能源配置 API：项目下的配置增删改查。
 *
 * <p>读需空间成员、写需空间管理员；错误走 {@code OxResponse.error}
 * （HTTP 200 + {@code success:false}），与全仓一致，不用 4xx。</p>
 */
@Path("/matrix/v1/energy/configs")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Energy", description = "能源配置 API")
@RequestScoped
public class EnergyConfigResource extends AbstractEnergyResource {

    @Inject
    EnergyConfigService configService;

    /** 项目下的配置；{@code role} / {@code type} 可选（页面按标签页时两个都给）。 */
    @GET
    @Path("/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> listConfigs(
            @PathParam("spaceId") String spaceId,
            @QueryParam("role") String role,
            @QueryParam("type") String type
    ) {
        logger.infov("list energy configs, space: {0}, role: {1}, type: {2}", spaceId, role, type);
        return requireSpaceMember(spaceId)
                .chain(space -> configService.list(space.rootSpaceId(),
                        optional(EnergyRole.class, role), optional(EnergyType.class, type)))
                .map(list -> OxResponse.ok(EnergyConfigCodec.encode(list)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @POST
    @Path("/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> createConfig(
            @PathParam("spaceId") String spaceId,
            EnergyConfigCodec.Request body
    ) {
        logger.infov("create energy config, space: {0}", spaceId);
        if (body == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        return requireSpaceAdmin(spaceId)
                .chain(space -> configService.create(space.rootSpaceId(), currentUser(),
                        EnergyConfigCodec.decode(body)))
                .map(config -> OxResponse.ok(EnergyConfigCodec.encode(config)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/{spaceId}/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> updateConfig(
            @PathParam("spaceId") String spaceId,
            @PathParam("id") String id,
            EnergyConfigCodec.Request body
    ) {
        logger.infov("update energy config, space: {0}, id: {1}", spaceId, id);
        if (body == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        return requireSpaceAdmin(spaceId)
                .chain(space -> configService.update(space.rootSpaceId(), id, currentUser(),
                        EnergyConfigCodec.decode(body)))
                .map(config -> OxResponse.ok(EnergyConfigCodec.encode(config)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @DELETE
    @Path("/{spaceId}/{id}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> deleteConfig(
            @PathParam("spaceId") String spaceId,
            @PathParam("id") String id
    ) {
        logger.infov("delete energy config, space: {0}, id: {1}", spaceId, id);
        return requireSpaceAdmin(spaceId)
                .chain(space -> configService.delete(space.rootSpaceId(), id))
                .map(deleted -> OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }
}
