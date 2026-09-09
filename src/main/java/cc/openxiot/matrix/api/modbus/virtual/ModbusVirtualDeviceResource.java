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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * Modbus 虚拟化 API。org 一律取 {@code X-Org-Id} 请求头，并经
 * {@link OrgAccessService#checkMember} 校验（需为组织成员），口径与 device/space 一致。
 *
 * <p>createOne 范围 = 仅生成虚拟设备实例定义并落库（不注册真实设备）：取组织内可见的 Modbus 点表配置，
 * 构造 {@code DeviceInstance} 经 codec 编码后持久化到 {@code virtual-devices} 集合，回显设备摘要与映射信息。</p>
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
}
