package cc.openxiot.matrix.api.modbus.virtual;

import cc.openxiot.common.person.Person;
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

/**
 * Modbus 虚拟化 API。org 一律取 {@code X-Org-Id} 请求头，并经
 * {@link OrgAccessService#checkMember} 校验（需为组织成员），口径与 device/space 一致。
 *
 * <p>createOne = 生成虚拟设备实例定义并落库（{@code virtual-devices} 定义注册表），再把它作为父设备
 * （请求 {@code did}）下的子设备创建矩阵 {@code DeviceEntity}（复制父设备所在 space，离线起步）并返回该设备。
 * 取组织内可见的 Modbus 点表配置；父设备不存在则报错。</p>
 *
 * <p>deleteOne = 反向清理：删除归属当前组织的虚拟设备定义文档，并连同删掉同 did 的矩阵子设备
 * （createOne 的镜像，不做子设备守卫）。</p>
 */
@Path("/matrix/v1/modbus/virtual")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modbus Virtual", description = "Modbus 虚拟化 API")
@RequestScoped
public class ModbusVirtualDeviceResource extends AbstractResource {

    @Inject
    Logger logger;

    @Inject
    ModbusVirtualDeviceService service;

    @Inject
    OrgAccessService orgAccessService;

    @GET
    public Uni<Response> hello() {
        logger.info("hello");
        return Uni.createFrom().item(OxResponse.ok("Hello from ModbusVirtualDeviceResource"));
    }

    @POST
    @Path("/one")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({OxRole.USER})
    public Uni<Response> createOne(
            @HeaderParam("X-Org-Id") String orgId,
            ModbusMapping mapping
    ) {
        logger.infov("create virtual device, org: {0}", orgId);
        if (mapping == null) {
            return Uni.createFrom().item(OxResponse.error("body is required"));
        }
        Person who = currentUser();
        return requireMember(orgId)
                .chain(o -> service.create(o, mapping, who))
                .map(OxResponse::ok)
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    private Uni<String> requireMember(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required"));
        }
        return orgAccessService.checkMember(orgId, getUserId()).map(ignored -> orgId);
    }

    @DELETE
    @Path("/one")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> deleteOne(
            @HeaderParam("X-Org-Id") String orgId,
            @QueryParam("did") String did
    ) {
        logger.infov("delete virtual device, org: {0}, did: {1}", orgId, did);
        return requireMember(orgId)
                .chain(o -> service.delete(o, did))
                .replaceWith(OxResponse.ok())
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }
}
