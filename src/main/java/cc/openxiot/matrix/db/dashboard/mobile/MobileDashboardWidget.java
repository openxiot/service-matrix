package cc.openxiot.matrix.db.dashboard.mobile;

import cc.openxiot.matrix.db.dashboard.DashboardWidgetView;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 移动端看板上的一张卡片。五种类型（{@code type}）共用一个壳，差异全在 {@code config} 里。
 *
 * <p><b>与 Web 的 {@code WebDashboardWidget} 是两个独立实体，不共用</b>：手机是竖排堆叠，
 * 卡片<b>没有坐标</b> —— 位置的真值就是 {@code widgets} 的数组顺序；宽幅只有
 * {@code FULL}/{@code HALF} 两档（见 {@link MobileDashboardWidgetSize}），而 Web 那套
 * {@code x}/{@code y} + {@code W{列}H{像素}} 在这里一个字都不出现。共用的是**规则**：
 * 身份与 config 的校验在 {@code DashboardWidgetStructureValidator} 等处，它们只依赖
 * {@link DashboardWidgetView} 这个只读视图（这就是它存在的原因）。</p>
 *
 * <p>{@code widgets} 的<b>数组顺序是阅读顺序</b>，也就是上下次序；服务端<b>不排序、不补位</b>：
 * 重排是客户端的活，这里原样收、原样存（见 {@code MobileDashboardLayoutService.normalized}）。</p>
 *
 * <p><b>类名换过一次，文档键没换</b>：这个类原来叫 {@code DashboardWidget}、与 Web 共用。
 * BSON 里存的是<b>属性名</b>（{@code id} → {@code _id}、{@code type}、{@code size}…）而不是类名，
 * 看板文档上也<b>故意没有</b> {@code @BsonDiscriminator}（POJO codec 因此从不写 {@code _t}），
 * 所以 {@code mobile-dashboard-layouts} 里的老文档原样读进这个类，<b>不需要任何迁移脚本</b>。
 * ⚠️ 别顺手加上 {@code @BsonDiscriminator} —— 一加就开始写 {@code _t}，而库里已有的文档没有它。</p>
 *
 * <p><b>{@code config} 是异构的，且故意不建成统一的 POJO</b>：五种卡片的配置字段几乎不重叠
 * （统计卡片要 metric、曲线要服务与字段、分布要 dimension 与窗口…），硬塞进一个大 POJO 会得到
 * 一个大部分字段长期为 null 的类。用 {@code Map<String, Object>} 的代价是校验要在
 * {@code DashboardWidgetStructureValidator} 里手写（不能靠 Jackson 绑定），这个代价是值得的。</p>
 *
 * <p><b>不需要为它写 codec</b>：{@code db/mongo/ObjectPropertyCodecProvider} 已经把
 * 「声明类型是 Object 的字段」这一格的 codec 兜底装好了，而值类型为 Object 的 Map 本来也有
 * {@code MapPropertyCodecProvider} 兜底（{@code ObjectPropertyCodecProviderTest} 有一条用例钉着，
 * 移动端那条就在同一个测试里）。所以嵌套的 config 能原样落库、原样读回。</p>
 *
 * <p><b>{@code titleKey} 与 {@code title} 是两个不同的东西</b>：{@code title} 是用户敲进去的
 * 原文（「东区温度」），任何语言下都必须原样显示，翻译它就是把用户数据改坏；
 * {@code titleKey} 是<b>我们自己</b>的页面文案的 i18n key，只有预置布局会填 ——
 * 预置布局是服务端造的，若把「设备总量」直接写进 {@code title}，英文界面就会看到中文
 * （而按 {@code AGENTS.md} 的规则，服务端下发的 {@code title} 恰恰不许翻译）。
 * 客户端取值顺序是 {@code title} → {@code titleKey} → 按 {@code type} 的默认名。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "移动端看板上的一张卡片")
public class MobileDashboardWidget implements DashboardWidgetView {

    @Schema(description = "卡片 ID：客户端生成的短串，同一布局内唯一（渲染结果按它对应）",
            examples = {"w_7f3a"}, required = true)
    public String id;

    @Schema(description = "卡片类型：stat 单一统计 / line 曲线图 / distribution 数据分布 "
            + "/ device 设备卡片 / service 服务卡片",
            examples = {"stat"}, required = true)
    public String type;

    @Schema(description = "标题：**用户数据**，原样下发、原样显示、不翻译", examples = {"东区温度"})
    public String title;

    @Schema(description = "预置标题的 i18n key：**页面文案**，客户端翻译（只有预置布局会填），"
            + "`title` 存在时以 `title` 为准", examples = {"设备总量"})
    public String titleKey;

    @Schema(description = "尺寸档位：FULL 整宽 / HALF 半宽（可与另一张 HALF 并排，仅 stat 卡开放）。"
            + "**占几列几行不在卡片上** —— 位置真值就是数组顺序，宽幅只有这两档",
            examples = {"HALF"}, required = true)
    public String size;

    @Schema(description = "按 type 而异构的配置（见方案 §5 各卡片一节），落库不做结构化")
    public Map<String, Object> config = new LinkedHashMap<>();

    // ---- 共用规则要读的字段（见 DashboardWidgetView）：字段与方法同名，方法只服务于那个接口 ----

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String titleKey() {
        return titleKey;
    }

    @Override
    public Map<String, Object> config() {
        return config;
    }
}
