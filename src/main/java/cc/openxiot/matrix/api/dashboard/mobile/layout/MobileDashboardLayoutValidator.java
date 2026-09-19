package cc.openxiot.matrix.api.dashboard.mobile.layout;

import cc.openxiot.matrix.api.dashboard.mobile.params.MobileDashboardLayoutDto;
import cc.openxiot.matrix.api.dashboard.mobile.widget.MobileDashboardWidgetStructureValidator;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetOwnershipValidator;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 保存一份移动端布局时的**校验编排入口**：先结构、后归属，第一个错误即整份失败。
 *
 * <p>镜像 {@code WebDashboardLayoutValidator}，只把结构校验换成移动端外壳
 * ({@link MobileDashboardWidgetStructureValidator}，{@code FULL/HALF} 档位)；归属校验与 Web 共用同一个
 * {@link DashboardWidgetOwnershipValidator} —— 移动端卡片与 Web 卡片是同一种东西、同一套归属口径，
 * 不需要也不该另抄一份查库逻辑。</p>
 */
@ApplicationScoped
public class MobileDashboardLayoutValidator {

    @Inject
    DashboardWidgetOwnershipValidator ownershipValidator;

    /** 保存路径：结构校验 + 归属校验，第一个错误即整份失败。结构校验先跑且同步。 */
    public Uni<Void> validate(SpaceEntity space, MobileDashboardLayoutDto body) {
        String structural = MobileDashboardWidgetStructureValidator.validate(body);
        if (structural != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(structural));
        }
        return ownershipValidator.errorsByWidget(space, body.widgets).chain(errors -> errors.isEmpty()
                ? Uni.createFrom().voidItem()
                // errors 是 LinkedHashMap，按卡片提交顺序插入，所以这里报的确实是「第一张出错的卡」
                : Uni.createFrom().failure(
                        new IllegalArgumentException(errors.values().iterator().next())));
    }
}