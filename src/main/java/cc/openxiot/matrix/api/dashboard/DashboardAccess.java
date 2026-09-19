package cc.openxiot.matrix.api.dashboard;

import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 看板资源（Web 与 Mobile）共用的**空间鉴权入口**：成员读、管理员写，两者口径逐字一致。
 *
 * <p>这是从 {@code WebDashboardResource} 的两个私有 helper 提出来的。两套看板各有一个资源，
 * 把一个「查空间 → 判 member/admin」各写一份就要改三份（原来那 9 份 requireSpaceMember 的
 * 前车之鉴），这处又恰恰是最不该分叉的 —— 读成员、写管理员是看板**唯一**的权限口径，
 * 分叉了就会出现「Web 能看、Mobile 不能」这种最难查的不一致。</p>
 *
 * <p>当前账号从 {@link SecurityIdentity} 解析（与 {@code AbstractResource} 同法）。空间不存在按
 * 「无权限」处理，不区分 404 —— 与 {@code DeviceResource} 同口径。</p>
 */
@ApplicationScoped
public class DashboardAccess {

    @Inject
    SpaceService spaceService;

    @Inject
    OrgAccessService orgAccessService;

    @Inject
    SecurityIdentity securityIdentity;

    /** 空间成员（读布局、取数）。返回空间实体，供后续 chain。 */
    public Uni<SpaceEntity> spaceMember(String spaceId) {
        return requireSpace(spaceId,
                space -> orgAccessService.checkSpaceMember(space, userId()));
    }

    /** 空间管理员（保存布局）：改布局影响所有成员，所以比读严一档。 */
    public Uni<SpaceEntity> spaceAdmin(String spaceId) {
        return requireSpace(spaceId,
                space -> orgAccessService.checkSpaceAdmin(space, userId()));
    }

    private Uni<SpaceEntity> requireSpace(String spaceId, SpaceMemberCheck check) {
        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }
                    return check.check(space).map(ignored -> space);
                });
    }

    private String userId() {
        return securityIdentity.getPrincipal().getName();
    }

    private interface SpaceMemberCheck {
        Uni<Void> check(SpaceEntity space);
    }
}