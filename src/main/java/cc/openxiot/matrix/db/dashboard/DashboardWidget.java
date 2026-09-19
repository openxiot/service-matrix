package cc.openxiot.matrix.db.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一张卡片。五种类型（{@code type}）共用一个壳，差异全在 {@code config} 里。
 *
 * <p><b>卡片有坐标，但只有两个</b>：{@link #x} / {@link #y} 是左上角起点（网格单位，
 * 屏幕宽 24 格、高无限）。<b>占几列几行（{@code w} / {@code h}）不上行</b> —— 那是从
 * {@link #size} 档位推出来的，占格表只在前端。这一条是有来历的：这里原来有一个
 * {@code layout} 字段（{@code x/y/w/h}），其中 {@code w}/{@code h} 是档位知识的
 * <b>第二份会过期的副本</b>（还要靠 {@code WebDashboardWidgetSize.applyTo} 按档位覆盖一次、
 * 再在别处校验 {@code x + w ≤ 24}），所以那一版整个删掉了。这一轮把坐标请回来时
 * <b>只请回了 {@code x}/{@code y}}</b>：那是用户摆的，服务端拿不出第二个人来算它；
 * {@code w}/{@code h} 仍然只有前端知道。</p>
 *
 * <p>{@code widgets} 的<b>数组顺序是阅读顺序</b>（窄屏折成一列时的上下次序），
 * <b>不是</b>位置 —— 位置是这两个坐标。服务端<b>不排序、不补位</b>：重排是前端的活，
 * 这里原样收、原样存（见 {@code WebDashboardLayoutService.normalized}）。</p>
 *
 * <p><b>老文档不需要迁移</b>：落库与读回都走 Mongo 的 POJO codec，它在文档里遇到类上没有的键
 * 是<b>跳过</b>（本类本来就挂着 {@code @JsonIgnoreProperties(ignoreUnknown = true)}），所以
 * 老文档里的 {@code widgets[].layout} 读的时候被忽略，第一次保存就把它从文档里洗掉了。
 * <b>注意别把它与现在的 {@code x}/{@code y} 搞混</b>：那个嵌套的 {@code layout} 是历史残留，
 * 不读也不写；平铺的 {@code x}/{@code y} 是<b>认识</b>的键。老文档还缺 {@code x}/{@code y}
 * （改造前存下来的卡片没有坐标），这种文档读出来照常是一份合法布局，由前端按数组顺序铺一遍
 * —— 铺法复刻的正是改造前浏览器流式排开的那个样子，所以长相不变。</p>
 *
 * <p><b>{@code config} 是异构的，且故意不建成统一的 POJO</b>：五种卡片的配置字段几乎不重叠
 * （统计卡片要 metric、曲线要服务与字段、分布要 dimension 与窗口…），硬塞进一个大 POJO 会得到
 * 一个大部分字段长期为 null 的类。用 {@code Map<String, Object>} 的代价是校验要在
 * {@code WebDashboardWidgetStructureValidator} 里手写（不能靠 Jackson 绑定），这个代价是值得的。</p>
 *
 * <p><b>不需要为它写 codec</b>：{@code db/mongo/ObjectPropertyCodecProvider} 已经把
 * 「声明类型是 Object 的字段」这一格的 codec 兜底装好了，而值类型为 Object 的 Map 本来也有
 * {@code MapPropertyCodecProvider} 兜底（{@code ObjectPropertyCodecProviderTest} 有一条用例钉着）。
 * 所以嵌套的 config 能原样落库、原样读回。</p>
 *
 * <p><b>{@code titleKey} 与 {@code title} 是两个不同的东西</b>：{@code title} 是用户敲进去的
 * 原文（「东区温度」），任何语言下都必须原样显示，翻译它就是把用户数据改坏；
 * {@code titleKey} 是<b>我们自己</b>的页面文案的 i18n key，只有预置布局会填 ——
 * 预置布局是服务端造的，若把「设备总量」直接写进 {@code title}，英文界面就会看到中文
 * （而按 {@code AGENTS.md} 的规则，服务端下发的 {@code title} 恰恰不许翻译）。
 * 前端取值顺序是 {@code title} → {@code titleKey} → 按 {@code type} 的默认名。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "看板上的一张卡片")
public class DashboardWidget {

    @Schema(description = "卡片 ID：前端生成的短串，同一布局内唯一（渲染结果按它对应）",
            examples = {"w_7f3a"}, required = true)
    public String id;

    @Schema(description = "卡片类型：stat 单一统计 / line 曲线图 / distribution 数据分布 "
            + "/ device 设备卡片 / service 服务卡片",
            examples = {"stat"}, required = true)
    public String type;

    @Schema(description = "标题：**用户数据**，原样下发、原样显示、不翻译", examples = {"东区温度"})
    public String title;

    @Schema(description = "预置标题的 i18n key：**页面文案**，前端翻译（只有预置布局会填），"
            + "`title` 存在时以 `title` 为准", examples = {"设备总量"})
    public String titleKey;

    @Schema(description = "尺寸档位 W{列}H{像素}，如 W6H200 = 6 列宽、200 像素高：决定这张卡"
            + "占几列几行（**占格表在前端**，服务端只认档位名认不认识；改造前的 S1/M1/S/M/L/XL "
            + "仍然收，见 WebDashboardWidgetSize.of）", examples = {"W12H200"}, required = true)
    public String size;

    @Schema(description = "起始列（0 起，0..23）。**没有这个键 = 老文档**（改造前的卡片没有坐标），"
            + "由前端按数组顺序铺一遍；与 `y` 同生同灭，只给一个的按「都没有」处理",
            examples = {"6"})
    public Integer x;

    @Schema(description = "起始行（0 起，上不封顶）。与 `x` 同生同灭", examples = {"4"})
    public Integer y;

    @Schema(description = "按 type 而异构的配置（见方案 §5 各卡片一节），落库不做结构化")
    public Map<String, Object> config = new LinkedHashMap<>();
}
