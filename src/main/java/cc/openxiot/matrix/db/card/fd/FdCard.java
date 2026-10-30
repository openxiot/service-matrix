package cc.openxiot.matrix.db.card.fd;

import cc.openxiot.common.person.Person;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 现场设备（Modbus）卡片的展示配置：**一台现场设备一份**，按项目（根空间）共享。
 *
 * <p>只存寄存器在点表里的**序号**（{@code ModbusRegistry.index}），不存名字：名字是用户可改的
 * 自由文本、还允许重名，拿它当身份，改一次名配置就全丢了；{@code index} 在同一份点表内唯一且稳定。
 * 点表内容仍以现场设备的 {@code configSnapshot} 为准 —— 配置只是「从里面挑几个」，不复制点表。</p>
 *
 * <p><b>文档不存在 ≠ 什么都不显示</b>：没有文档时前端按缺省规则摆（最多 4 个寄存器）；
 * 文档存在但 {@code registers} 为空，才是「用户明确清空了」。所以「恢复默认」是删文档。
 * 与 {@link cc.openxiot.matrix.db.card.device.DeviceCard} 同一条口径。</p>
 *
 * <p><b>按根空间存</b>（{@code spaceId} 存 {@code SpaceEntity.rootSpaceId()}）：配置是「项目一份」，
 * 在子空间页面和项目页面看到的是同一份。</p>
 *
 * <p><b>{@code fdid} 就是文档主键</b>（{@code _id}）：它是现场设备自己的 ObjectId（十六进制字符串），
 * 全局唯一且永不复用，所以一份配置天然一台现场设备一份。{@code spaceId} 不是身份，只是**每次保存
 * 时按现场设备落点现算的派生字段**，专为「按项目列配置」这一个查询服务 —— 现场设备跟着设备走，
 * 设备跨不了根空间，所以它不会过期；万一脏了，下一次保存会把它盖回正确值。</p>
 */
@RegisterForReflection
@Schema(description = "现场设备卡片展示配置（一台现场设备一份，项目内共享）")
public class FdCard {

    @BsonId
    @Schema(description = "现场设备 ID（fdid，十六进制字符串，即文档主键）", required = true)
    public String fdid;

    @Schema(description = "所属空间 ID（根空间，即项目）。派生字段：每次保存按现场设备落点现算覆盖，仅用于按项目列表",
            required = true)
    public String spaceId;

    @Schema(description = "要显示的寄存器序号（{@code ModbusRegistry.index}），**数组顺序即卡片上的显示顺序**；"
            + "空数组表示明确不显示任何寄存器（与「没有配置」不同）", required = true)
    public List<Integer> registers = new ArrayList<>();

    @Schema(description = "创建者/创建时间")
    public Person creator;

    @Schema(description = "最后更新者/更新时间")
    public Person updater;
}
