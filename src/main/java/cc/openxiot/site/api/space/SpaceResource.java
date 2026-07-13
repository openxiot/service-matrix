package cc.openxiot.site.api.space;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.site.db.space.SpaceEntity;
import cc.openxiot.site.role.SiteRole;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

@Path("/v1/space")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Space", description = "Space API")
@RequestScoped
public class SpaceResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    SpaceService service;

    @GET
    public Response hello() {
        logger.info("hello");
        return OxResponse.ok("Hello from SpaceResource");
    }

    @POST
    @Path("/one")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Response addOne(SpaceEntity space) {
        String userId = getUserId();

        // TODO: 添加空间（子空间，根空间），
        return null;
    }

    @DELETE
    @Path("/one/{spaceId}")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Response removeOne(
            @PathParam("spaceId") String spaceId
    ) {
        String userId = getUserId();

        // TODO: 删除空间（空间如果有子空间，就不能删除）
        return null;
    }

    @PUT
    @Path("/one")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Response updateOne(SpaceEntity space) {
        String userId = getUserId();

        // TODO: 更新空间（parentId 如果修改，则为移动空间）
        return null;
    }

    @GET
    @Path("/one/{spaceId}")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Response getOne(
            @PathParam("spaceId") String spaceId
    ) {
        String userId = getUserId();

        // TODO: 读取一个空间的信息
        return null;
    }

    @GET
    @Path("/all")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Response getAll() {
        String userId = getUserId();

        // TODO: 读取一个根空间列表

        return null;
    }

    @GET
    @Path("/tree/{rootId}")
    @RolesAllowed({SiteRole.BROWSER, SiteRole.OPERATOR, SiteRole.MANAGER})
    public Response getTree(
            @PathParam("rootId") String rootId
    ) {
        String userId = getUserId();

        // TODO: 读取一个空间树

        return null;
    }

    @GET
    @Path("/graph/{rootId}")
    public Response getGraph(
            @PathParam("rootId") String rootId
    ) {
        // rootId => 根空间ID
        String userId = getUserId();

        // TODO: 读取一个物联网场地的所有信息：空间列表，设备列表

        return null;
    }
}
