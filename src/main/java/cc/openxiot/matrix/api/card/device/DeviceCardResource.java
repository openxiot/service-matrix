package cc.openxiot.matrix.api.card.device;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.card.device.params.DeviceCardDto;
import cc.openxiot.matrix.api.dashboard.DashboardAccess;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * 设备卡片的展示配置 API：项目内共享一份，读是空间成员、写是空间管理员。
 *
 * <p>为什么写比读严一档：卡片配置和看板布局一样「改一次影响所有人」—— 一个管理员改完，
 * 项目里每个成员打开页面看到的都是新卡片。与 {@code WebDashboardResource} 共用
 * {@link DashboardAccess}，两处权限口径不可能分叉。</p>
 *
 * <p><b>「未配置」与「配置成空」是两种状态</b>：GET 单台时 {@code customized=false} 表示
 * 从未配置过（前端按缺省 2 个服务 × 3 个属性摆）；PUT 一份空清单表示用户明确清空；
 * DELETE 是「恢复默认」（删文档，回到未配置）。所以「恢复默认」不能用 PUT 空清单代替。</p>
 *
 * <p>路径上的 {@code spaceId} 可以是子空间：服务层会规范成根空间再落库/查询，
 * 在子空间和设备所属的项目页看到的是同一份配置。</p>
 */
@Path("/matrix/v1/card/device")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "DeviceCard", description = "设备卡片展示配置 API")
@RequestScoped
public class DeviceCardResource extends AbstractResource {

    @Inject
    DashboardAccess access;

    @Inject
    DeviceCardService cardService;

    /** 项目下所有已配置的设备卡片（没配过的设备不在里面，前端走缺省规则）。 */
    @GET
    @Path("/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> list(@PathParam("spaceId") String spaceId) {
        return access.spaceMember(spaceId)
                .chain(cardService::list)
                .map(cards -> OxResponse.ok(new JsonObject()
                        .put("spaceId", spaceId)
                        .put("items", DeviceCardCodec.encodeList(cards))))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 单台设备的配置；没配过时 {@code customized=false}，不报 404。 */
    @GET
    @Path("/{spaceId}/{did}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> get(@PathParam("spaceId") String spaceId, @PathParam("did") String did) {
        return access.spaceMember(spaceId)
                .chain(space -> cardService.load(space, did))
                .map(card -> OxResponse.ok(card == null
                        ? DeviceCardCodec.encodeAbsent(did)
                        : DeviceCardCodec.encode(card)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/{spaceId}/{did}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> save(@PathParam("spaceId") String spaceId, @PathParam("did") String did,
                              DeviceCardDto body) {
        // 与 FieldDeviceResource.createOne 一样在链外取：链上的回调可能已经不在请求线程上，
        // 那时安全上下文（当前用户）不一定还在
        Person who = currentUser();
        return access.spaceAdmin(spaceId)
                .chain(space -> cardService.save(space, did, body, who))
                .map(card -> OxResponse.ok(DeviceCardCodec.encode(card)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 恢复默认：删掉配置，回到缺省摆法。没有配置时也成功（幂等）。 */
    @DELETE
    @Path("/{spaceId}/{did}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> reset(@PathParam("spaceId") String spaceId, @PathParam("did") String did) {
        return access.spaceAdmin(spaceId)
                .chain(space -> cardService.reset(space, did))
                .map(ignored -> OxResponse.ok(DeviceCardCodec.encodeAbsent(did)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }
}
