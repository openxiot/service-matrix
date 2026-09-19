package cc.openxiot.matrix.db.dashboard.web;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.dashboard.web.widget.DashboardWidget;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Web数据看板: 用于浏览器和Pad
 * Mobile数据看板：用于移动端（iPhone & Android）
 * 自定义数据看板的布局：**一个空间一份**，空间内共享（读写权限不对称，见
 * {@code DashboardResource}：读是空间成员、写是空间管理员）。
 *
 * <p><b>为什么整个布局是一个文档、widgets 内嵌</b>：一屏卡片是一个整体，改一张会牵动一片
 * （拖到哪儿、被压的谁让位、整屏怎么上吸，都是前端算好的一整份新布局）。拆成 widget 表就要在
 * 一个事务里重写 N 行，而且仍然解决不了并发下的中间态。一屏卡片量级是几十，
 * 离 Mongo 单文档 16MB 的边很远。</p>
 *
 * <p><b>文档不存在 ≠ 返回空</b>：空间从未配置过时，服务层直接返回一份<b>内存生成的预置布局</b>
 * （见 {@code PresetLayouts}），不落库。若走「首次 GET 时补一条文档」，两个成员同时打开页面
 * 就会并发建两条 —— 要么加唯一索引加重试，要么面对重复文档，代价都大于收益。
 * 落库只发生在保存：{@code version} 在「从未保存过」时恒为 {@code 0}。</p>
 *
 * <p>与 {@code SpaceEntity} 同库（{@code matrix}）：这是空间级数据，生命周期跟着空间走。</p>
 */
@MongoEntity(database = "matrix", collection = "dashboard-layouts")
@RegisterForReflection
@Schema(description = "自定义数据看板布局（一个空间一份）")
public class DashboardLayoutEntity extends ReactivePanacheMongoEntity {

    // 主键 id（ObjectId）继承自 ReactivePanacheMongoEntity：落库时由 Panache 生成，
    // 不在此声明字段，避免 @BsonId 重复映射。

    @Schema(description = "所属空间 ID（根空间，即项目）", required = true)
    public String spaceId;

    @Schema(description = "卡片列表：**数组顺序是阅读顺序**（窄屏折成一列时的上下次序），"
            + "位置的真值是每张卡自己的 `x` / `y`；服务端不排序、不补位，原样收存",
            required = true)
    public List<DashboardWidget> widgets = new ArrayList<>();

    @Schema(description = "乐观锁版本号，每次保存 +1；无文档（预置布局）时为 0",
            examples = {"0"}, required = true)
    public long version;

    @Schema(description = "创建者/创建时间")
    public Person creator;

    @Schema(description = "最后更新者/更新时间")
    public Person updater;
}
