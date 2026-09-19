package cc.openxiot.matrix.api.dashboard.web.layout;

import cc.openxiot.matrix.api.dashboard.web.params.DashboardLayoutDto;
import cc.openxiot.matrix.api.dashboard.web.widget.WidgetOwnershipValidator;
import cc.openxiot.matrix.api.dashboard.web.widget.WidgetStructureValidator;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 保存一份布局时的**校验编排入口**：先结构、后归属，第一个错误即整份失败。
 *
 * <p>本类自己不校验任何东西，它只决定两件事的顺序和失败语义：</p>
 * <ul>
 *   <li>结构校验在 {@link WidgetStructureValidator} —— 纯函数、不查库。它先跑且是同步的：
 *       body 不成形时连「有哪些卡片」都无从谈起，此时<b>一次查询都不该发出去</b>；</li>
 *   <li>归属校验在 {@link WidgetOwnershipValidator} —— 要查库，返回
 *       {@code widgetId → 错误文案}。它对渲染路径是**逐卡隔离**的，但保存路径没有
 *       「部分成功」这一说，所以这里把它收敛成「报第一张出错的卡」。</li>
 * </ul>
 *
 * <p>两个校验器同属 {@code widget} 包，本类留在 {@code layout}：它们的名字里都没有 Layout，
 * 因为「卡片配置合不合法」跟布局无关 —— 布局只是第一个调用它们的场景。这样切分之后
 * {@code widget} 包不再依赖 {@code layout} 包，依赖是单向的。</p>
 *
 * <p>为什么保存要一次报一个错而不是全报：错误提示最终是一条 toast / 一个字段，
 * 编辑器一行只放得下一个。逐张标红那条路属于渲染（见 {@code WidgetDataService}）。</p>
 */
@ApplicationScoped
public class DashboardLayoutValidator {

    @Inject
    WidgetOwnershipValidator ownershipValidator;

    /**
     * 保存路径：结构校验 + 归属校验，第一个错误即整份失败。
     * 结构校验先跑且同步 —— body 不成形时连「有哪些卡片」都无从谈起。
     */
    public Uni<Void> validate(SpaceEntity space, DashboardLayoutDto body) {
        String structural = WidgetStructureValidator.validate(body);
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
