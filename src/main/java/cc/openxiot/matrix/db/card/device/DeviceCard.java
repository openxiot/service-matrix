package cc.openxiot.matrix.db.card.device;

import cc.openxiot.common.person.Person;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备卡片的展示配置：**一台设备一份**，按项目（根空间）共享。
 *
 * <p>配置只回答「卡片上摆哪些东西」，不回答「怎么读值」—— 值仍然来自设备影子，
 * 这里只存服务（{@code siid}）与只读属性（{@code piid}）的**选择**。服务/属性的名字、当前值
 * 一个字都不存：它们会变，卡片应该跟着定义变，而不是被一份过期副本钉住。</p>
 *
 * <p><b>文档不存在 ≠ 什么都不显示</b>：没有文档时由前端按缺省规则摆（最多 2 个服务 × 3 个属性）；
 * 文档存在但 {@code services} 为空，才是「用户明确清空了，卡片上什么都不显示」。
 * 这两种状态必须分开，所以「恢复默认」是删文档（DELETE），不是存一份空配置。
 * 与看板布局同一条口径（{@code WebDashboardLayoutEntity}）。</p>
 *
 * <p><b>按根空间存</b>（{@code spaceId} 存 {@code SpaceEntity.rootSpaceId()}）：设备卡片的配置
 * 是「项目一份」，在子空间页面和项目页面看到的是同一份。</p>
 *
 * <p><b>{@code did} 就是文档主键</b>（{@code _id}）：设备 id 全局唯一且永不复用，一份配置天然
 * 一台设备一份，不必再养一个无关的 ObjectId。{@code spaceId} 不是身份，只是**每次保存时按设备
 * 现算的派生字段**，专为「按项目列配置」这一个查询服务 —— 设备跨根空间搬不动
 * （见 {@code DeviceService.updateSpace}），所以它不会过期；万一脏了，下一次保存会把它盖回正确值。</p>
 *
 * <p>与 {@link cc.openxiot.matrix.db.card.fd.FdCard} 分集合、分接口：设备卡片与现场设备卡片
 * 的配置形状和校验规则都不同（前者是服务树，后者是寄存器序号），合在一起只会让两边互相牵制。</p>
 */
@RegisterForReflection
@Schema(description = "设备卡片展示配置（一台设备一份，项目内共享）")
public class DeviceCard {

    @BsonId
    @Schema(description = "设备 ID（即文档主键）", required = true)
    public String did;

    @Schema(description = "所属空间 ID（根空间，即项目）。派生字段：每次保存按设备现算覆盖，仅用于按项目列表",
            required = true)
    public String spaceId;

    @Schema(description = "要显示的服务及其只读属性，**数组顺序即卡片上的显示顺序**；"
            + "空数组表示明确不显示任何服务（与「没有配置」不同）", required = true)
    public List<ServiceSelection> services = new ArrayList<>();

    @Schema(description = "创建者/创建时间")
    public Person creator;

    @Schema(description = "最后更新者/更新时间")
    public Person updater;

    /**
     * 一个服务上的选择：{@code siid} + 要显示的只读属性 {@code piid}。
     *
     * <p>用内嵌对象而不是「服务 id 的数组 + 属性 id 的平行数组」：后者一旦长度不齐就会
     * 悄悄错位，而且没法表达「这个服务一个属性都不显示」。{@code piids} 为空是合法的
     * （服务本身在卡片上占一行标题，下面不列值），与「服务没选」区分得开。</p>
     */
    @RegisterForReflection
    @Schema(description = "设备卡片上的一个服务选择")
    public static class ServiceSelection {

        @Schema(description = "服务 ID（siid）", examples = {"1"}, required = true)
        public Integer siid;

        @Schema(description = "要显示的只读属性 ID（piid）列表，顺序即显示顺序", required = true)
        public List<Integer> piids = new ArrayList<>();
    }
}
