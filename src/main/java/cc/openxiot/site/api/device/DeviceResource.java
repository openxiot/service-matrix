package cc.openxiot.site.api.device;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.site.api.space.SpaceService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;

@Path("/v1/device")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Device", description = "Device API")
@RequestScoped
public class DeviceResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    DeviceService deviceService;

    @Inject
    SpaceService spaceService;

    @Inject
    ManipulationService manipulationService;

    @GET
    public Response hello() {
        logger.info("hello");
        return OxResponse.ok("Hello from DeviceResource");
    }

    @POST
    @Path("/many/{spaceId}")
    public Response addDevices(
            @PathParam("spaceId") String spaceId,
            String body
    ) {
        String userId = getUserId();

        // TODO: 添加设备，先检查用户是否拥有这个空间
        //  再调用 ManipulationService 里的添加接口，成功了，就通过 DeviceService 保存。

        return null;
    }

    @DELETE
    @Path("/many/{spaceId}")
    public Response removeDevices(
            @PathParam("spaceId") String spaceId,
            @QueryParam("did") List<String> did
    ) {
        String userId = getUserId();

        // TODO: 删除设备，先检查用户是否拥有这个空间
        //  再调用 ManipulationService 里的添加接口，成功了，就通过 DeviceService 删除。

        return null;
    }

    @PUT
    @Path("/many/space")
    public Response updateSpace(String body) {
        String userId = getUserId();

        // TODO: 仅修改设备所在的空间，先检查用户是否拥有这个空间
        //  再调用 DeviceService 即可

        return null;
    }

    @GET
    @Path("/many/{spaceId}")
    public Response getDevices(
            @PathParam("spaceId") String spaceId
    ) {
        String userId = getUserId();

        // TODO: 读取空间里的设备列表，先检查用户是否拥有这个空间，再调用 DeviceService 即可

        return null;
    }

    @GET
    @Path("/shadows/{spaceId}")
    public Response getShadows(
            @PathParam("spaceId") String spaceId,
            @QueryParam("did") List<String> did
    ) {
        // spaceId: 根空间

        String userId = getUserId();

        // TODO: 先调用 DeviceService 校验设备所在的空间，以及用户是否拥有这个空间
        //  再 ManipulationService 里的 getShadows

        return null;
    }

    @GET
    @Path("/properties/{spaceId}")
    public Uni<Response> getProperties(
            @QueryParam("pid") List<String> pid
    ) {
        String userId = getUserId();

        // TODO: 先调用 DeviceService 校验设备所在的空间，以及用户是否拥有这个空间
        //  再 ManipulationService 里的 getProperties

        return null;
    }

    @POST
    @Path("/properties/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> setProperties(String body) {
        String userId = getUserId();

        // TODO: 先调用 DeviceService 校验设备所在的空间，以及用户是否拥有这个空间
        //  再 ManipulationService 里的 setProperties

        return null;
    }

    @POST
    @Path("/actions/{spaceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> invokeActions(String body) {
        String userId = getUserId();

        // TODO: 先调用 DeviceService 校验设备所在的空间，以及用户是否拥有这个空间
        //  再 ManipulationService 里的 invokeActions

        return null;
    }
}
