package cc.openxiot.matrix.api.card.fd;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.common.response.OxResponse;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.card.fd.params.FdCardDto;
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
 * 现场设备卡片的展示配置 API：项目内共享一份，读是空间成员、写是空间管理员。
 *
 * <p>与 {@code DeviceCardResource} <b>分接口、分集合</b>：两者形状不同（这里只选寄存器序号），
 * 校验规则也不同（寄存器必须在本现场设备的点表里且可读），合并成一个「卡片配置」接口只会让
 * 两边互相牵制，前端也要在同一个资源里分辨两种 body。</p>
 *
 * <p><b>「未配置」与「配置成空」是两种状态</b>：GET 单台时 {@code customized=false} 表示
 * 从未配置过（前端按缺省 4 个寄存器摆）；PUT 空清单表示明确清空；DELETE 是「恢复默认」。</p>
 */
@Path("/matrix/v1/card/fd")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "FdCard", description = "现场设备卡片展示配置 API")
@RequestScoped
public class FdCardResource extends AbstractResource {

    @Inject
    DashboardAccess access;

    @Inject
    FdCardService cardService;

    /** 项目下所有已配置的现场设备卡片（没配过的不在里面，前端走缺省规则）。 */
    @GET
    @Path("/{spaceId}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> list(@PathParam("spaceId") String spaceId) {
        return access.spaceMember(spaceId)
                .chain(cardService::list)
                .map(cards -> OxResponse.ok(new JsonObject()
                        .put("spaceId", spaceId)
                        .put("items", FdCardCodec.encodeList(cards))))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 单台现场设备的配置；没配过时 {@code customized=false}，不报 404。 */
    @GET
    @Path("/{spaceId}/{fdid}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> get(@PathParam("spaceId") String spaceId, @PathParam("fdid") String fdid) {
        return access.spaceMember(spaceId)
                .chain(space -> cardService.load(space, fdid))
                .map(card -> OxResponse.ok(card == null
                        ? FdCardCodec.encodeAbsent(fdid)
                        : FdCardCodec.encode(card)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    @PUT
    @Path("/{spaceId}/{fdid}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> save(@PathParam("spaceId") String spaceId, @PathParam("fdid") String fdid,
                              FdCardDto body) {
        // 与 FieldDeviceResource.createOne 一样在链外取：链上的回调可能已经不在请求线程上，
        // 那时安全上下文（当前用户）不一定还在
        Person who = currentUser();
        return access.spaceAdmin(spaceId)
                .chain(space -> cardService.save(space, fdid, body, who))
                .map(card -> OxResponse.ok(FdCardCodec.encode(card)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }

    /** 恢复默认：删掉配置，回到缺省摆法。没有配置时也成功（幂等）。 */
    @DELETE
    @Path("/{spaceId}/{fdid}")
    @RolesAllowed({OxRole.USER})
    public Uni<Response> reset(@PathParam("spaceId") String spaceId, @PathParam("fdid") String fdid) {
        return access.spaceAdmin(spaceId)
                .chain(space -> cardService.reset(space, fdid))
                .map(ignored -> OxResponse.ok(FdCardCodec.encodeAbsent(fdid)))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> OxResponse.error(e.getMessage()));
    }
}
