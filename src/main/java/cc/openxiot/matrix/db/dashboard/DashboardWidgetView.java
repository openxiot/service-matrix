package cc.openxiot.matrix.db.dashboard;

import java.util.Map;

/**
 * 卡片上**两张看板共用规则**要读的那几个字段。
 *
 * <p>{@link WebDashboardWidget} 与 {@link cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget}
 * 是**两个互不牵连的实体**：Web 的卡片有 {@code x}/{@code y}（用户摆的坐标），Mobile 的卡片有
 * {@code side}（半宽卡占这一行的哪半格），各自的东西不带进对方。但**身份与 config 的校验规则只有
 * 一份**（{@code DashboardWidgetStructureValidator}、{@code DashboardWidgetOwnershipValidator}、
 * {@code DashboardWidgetDataService} 都是两张看板共用的），它们只要下面这几个值，于是依赖这个
 * 只读视图，而不是某一个具体实体 —— 规则一套口径一套文案，实体各是各的。</p>
 *
 * <p><b>为什么没有 {@code size()}</b>：档位是两张看板语义完全不同的一档（Web 是
 * {@code W{列}H{像素}}、Mobile 是 {@code FULL/HALF}），共用规则一个字都用不到它。把它放进来，
 * 「哪一端的档位」又会重新变成一个共享概念 —— 那正是拆开实体要避免的事。所以档位校验留在各自的壳里
 * （{@code WebDashboardWidgetStructureValidator} / {@code MobileDashboardWidgetStructureValidator}）。</p>
 *
 * <p><b>为什么访问器与字段同名</b>：两个实体都用公开字段（同 {@code SpaceEntity} 的做法），
 * 而本接口只能用方法表达 —— {@code widget.id} 与 {@code widget.id()} 在 Java 里同时存在是合法的
 * （字段与方法各占一个命名空间），这几个方法只服务于本接口。</p>
 */
public interface DashboardWidgetView {

    /** 卡片 id：前端生成的短串，同一布局内唯一（渲染结果按它对应）。 */
    String id();

    /** 卡片类型：{@code stat} / {@code line} / {@code distribution} / {@code device} / {@code service}。 */
    String type();

    /** 标题：**用户数据**，原样下发、原样显示、不翻译。 */
    String title();

    /** 预置标题的 i18n key：**页面文案**，只有服务端造的预置布局会填。 */
    String titleKey();

    /** 按 type 而异构的配置（落库不做结构化，见各实体的说明）。 */
    Map<String, Object> config();
}
