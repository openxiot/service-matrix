package cc.openxiot.matrix.db.dashboard.mobile;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.dashboard.DashboardWidget;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 移动端数据看板（iPhone & Android，竖屏）的布局：**一个项目一份**，项目内共享 —— 与 Web 看板
 * 同一套数据模型：{@code spaceId + widgets + version}（乐观锁）+ 人事信息，只是<b>另起一个集合</b>
 * ({@code mobile-dashboard-layouts})，两者互不干扰，也不共用位置。
 *
 * <p><b>为什么移动端独立一份布局，而不是渲染 Web 那份</b>：手机是<b>有序纵向堆叠</b>，
 * Web 是 24 列可拖拽网格。若让手机去渲染 Web 那份 24 列布局，要么缩放变形、要么得在手机端
 * 重新铺一遍位置 —— 铺法是「竖屏下每一行怎么落」的另一种排列语义，仍然得移动端自己维护。
 * 既然排列语义不同，布局数据就该分开存；只有<b>卡片本身</b>（id/type/config）是同一份语义，
 * 所以它复用 Web 那个已共享的 {@link DashboardWidget} 实体与取数引擎。</p>
 *
 * <p><b>移动端卡片没有坐标</b>：手机不需要自由网格，数组顺序就是阅读顺序（竖着从上到下），
 * 每张卡一个宽幅档位 {@code FULL / HALF}（见 {@code MobileDashboardWidgetSize}）。所以移动端
 * 布局里的 {@code DashboardWidget} 一律不带 {@code x}/{@code y} —— 实体字段还在（共享类型），
 * 只是移动端从不填充、codec 也不下发。</p>
 *
 * <p>读写权限不对称，与 Web 同一口径：读是空间成员、写是空间管理员（见
 * {@code MobileDashboardResource}）。</p>
 */
@MongoEntity(database = "matrix", collection = "mobile-dashboard-layouts")
@RegisterForReflection
@Schema(description = "移动端看板布局（一个项目一份，竖屏有序堆叠）")
public class MobileDashboardLayoutEntity extends ReactivePanacheMongoEntity {

    @Schema(description = "所属空间 ID（根空间，即项目）", required = true)
    public String spaceId;

    @Schema(description = "卡片列表：**数组顺序就是阅读顺序**（竖屏从上到下），"
            + "每张卡一个宽幅档位 `size`（FULL/HALF），无坐标", required = true)
    public List<DashboardWidget> widgets = new ArrayList<>();

    @Schema(description = "乐观锁版本号，每次保存 +1；无文档（预置布局）时为 0",
            examples = {"0"}, required = true)
    public long version;

    @Schema(description = "创建者/创建时间")
    public Person creator;

    @Schema(description = "最后更新者/更新时间")
    public Person updater;
}