package cc.openxiot.matrix.api.modbus.shadow;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.modbus.fd.FieldDeviceService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * 现场设备**影子**（寄存器缓存网格）API：一次读取一个/多个服务的全部可读寄存器缓存实况。
 *
 * <p>这是对「设备影子」（{@code DeviceResource.getShadows}）的 Modbus 侧对应：设备影子给设备属性，
 * 这里给的是**服务点表里所有可读寄存器的最后一次轮询快照**（读侧投影，见
 * {@code FieldDeviceService.registerCaches}），按 {@code fdid} 归组。</p>
 *
 * <p><b>按空间鉴权</b>（与 {@code FieldDeviceResource} 同口径）：路径上的 {@code spaceId} 是当前项目空间，
 * 查询要该空间的**成员**（{@link OrgAccessService#checkSpaceMember}）。{@code spaceId} 只作鉴权锚点：
 * 判定通过后按 {@code fdids} 操作，不校验「现场设备是否真属于该空间」。</p>
 *
 * <p>{@code fdids}：缺省时返回该空间下现场设备的网格（与设备影子「不传 did 则按空间取」同口径）。</p>
 */
@Path("/matrix/v1/modbus/shadows")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Modbus Shadow", description = "Modbus Device shadow (register cache) API")
@RequestScoped
public class ModbusShadowResource extends AbstractResource {

    @Inject
    Logger logger;

    /** 网格归并与批量读取，见 {@link ModbusShadowService#registerCaches}。 */
    @Inject
    ModbusShadowService shadowService;

    /** 仅用于「不带 fdids 时按空间取全部现场设备」的定义查询（见 {@link #resolveFdids}）。 */
    @Inject
    FieldDeviceService fdService;

    @Inject
    SpaceService spaceService;

    @Inject
    OrgAccessService orgAccessService;

    @GET
    @Path("/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> getShadows(
            @PathParam("spaceId") String spaceId,
            @QueryParam("fdids") List<String> fdids
    ) {
        logger.infov("get modbus shadows, space: {0}, fdids: {1}",
                spaceId, fdids == null ? "-" : fdids);
        // RESTEasy 把 @QueryParam List<String> 的每个「出现」当一个元素、不拆逗号；`?fdids=a,b,c`
        // 会绑成单个 "a,b,c"。这里按逗号摊平一遍，让「逗号分隔」和「重复参数」两种写法都能用。
        List<String> ids = splitParam(fdids);
        return requireSpaceMember(spaceId)
                .chain(space -> resolveFdids(space, ids))
                .chain(ids2 -> shadowService.registerCaches(ids2))
                .map(grid -> OxResponse.ok(new JsonObject()
                        .put("shadows", encodeShadows(grid))))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** {@code ?fdids=a,b,c} 与 {@code ?fdids=a&fdids=b} 都接受：按逗号摊平、去空段。 */
    private static List<String> splitParam(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .flatMap(s -> java.util.Arrays.stream(s.split(",")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 给了 {@code fdids} 直接用；没给则取该空间下的全部服务（与设备影子同口径）。
     */
    private Uni<List<String>> resolveFdids(SpaceEntity space, List<String> fdids) {
        if (fdids != null && !fdids.isEmpty()) {
            return Uni.createFrom().item(fdids);
        }
        return fdService.listBySpaceId(space.id.toString())
                .map(list -> list.stream().map(s -> s.id.toHexString()).toList());
    }

    /** 每张服务网格组装成 {@code {fdid, registers}}，整体放进 {@code shadows} 对象（按入参顺序）。 */
    private static JsonObject encodeShadows(Map<String, List<JsonObject>> grid) {
        JsonObject shadows = new JsonObject();
        grid.forEach((fdid, list) -> {
            JsonObject one = new JsonObject();
            one.put("fdid", fdid);
            one.put("registers", new JsonArray(list));
            shadows.put(fdid, one);
        });
        return shadows;
    }

    /** 空间成员（查询）。 */
    private Uni<SpaceEntity> requireSpaceMember(String spaceId) {
        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }
                    return orgAccessService.checkSpaceMember(space, getUserId())
                            .map(ignored -> space);
                });
    }
}