package cc.openxiot.matrix.db.modbus.service;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.service.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.service.poll.request.ModbusRequest;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;

/**
 * Modbus 服务：把一条 Modbus 点表映射成一组可直接调用的「方法」。
 *
 * <p>把「怎么调依赖的设备、发什么帧、怎么解应答」在**创建时**一次性展开存进本文档，
 * 调用时只读这一条记录即可组装出 {@code ActionOperation}，不需要再读点表、父设备、产品实例定义。</p>
 *
 * <p>独立集合 {@code services}（库 {@code modbus}，与点表 {@code configs} 分集合）。
 * 主键 {@code _id} 由 Panache 生成 ObjectId，对外（URL / JSON / invoke 请求体）一律以十六进制字符串呈现；
 * 持久化经 {@link ModbusServiceRepository}。</p>
 */
@RegisterForReflection
@Schema(description = "Modbus 服务（将点表映射成可调用的方法集合）")
public class ModbusService {

    @BsonId
    @Schema(description = "主键（MongoDB ObjectId）", required = false)
    public ObjectId id;

    /**
     * 定义格式版本号：当前只认 2。
     *
     * <p>1 → 2 是 poll 内部结构的演进：{@code request} 从「一整串 Modbus RTU 帧的 16 进制字符串」
     * 改成了结构化定义（{@link ModbusRequest}），
     * 写方法另外多出「写入字段 + 缺省值」。校验层按本常量**硬拒旧版本**：
     * 让旧口径的定义响亮地失败，而不是被读成 null 之后报一句含糊的「request is required」。</p>
     */
    public static final int VERSION = 2;

    @Schema(description = "服务名称（展示用，如 1 号冷水机组）", required = true)
    public String name;

    @Schema(description = "定义格式版本号：ModbusPoll 内部结构后续若演进，按它区分口径（当前为 2）",
            examples = {"2"}, required = true)
    public Integer version;

    @Schema(description = "源点表的创建时快照：点表之后被修改/删除都不影响本服务；"
            + "服务的寄存器缓存读侧投影直接读它，不再回查点表集合")
    public ModbusConfig configSnapshot;

    @Schema(description = "从站地址（1..247）：从源点表拷贝而来可改；各方法 request.slaveId 必须与它一致"
            + "（校验层强制）——它是“这个服务读哪台从站”的顶层事实",
            examples = {"1"}, required = true)
    public Integer slaveId;

    @Schema(description = "依赖的设备调用坐标（调哪个设备/服务/方法、请求帧填在哪个入参 piid）", required = true)
    public DeviceOn device = new DeviceOn();

    @Schema(description = "方法列表：每一项（ModbusPoll）= 一次父设备调用（读或写），"
            + "来源（type 上的 type 字段：command 显式命令 / registry 寄存器块读）见 ModbusPollFrom；"
            + "方法序号（身份）在 ModbusPoll 的 index",
            required = true)
    public List<ModbusPoll> polls;

    @Schema(description = "创建者/创建时间")
    public Person creator;

    @Schema(description = "最后更新者/更新时间")
    public Person updater;
}
