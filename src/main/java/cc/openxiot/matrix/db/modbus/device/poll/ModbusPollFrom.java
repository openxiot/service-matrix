package cc.openxiot.matrix.db.modbus.device.poll;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 一个 {@link ModbusPoll}（服务方法）的**来源**，取代旧的多形态 {@code kind} / 裸 {@code index} 字段。
 *
 * <p>方法只可能来自两条路：由人手工配的显式命令（{@code type="command"}，读写都有），或者由前端
 * 从寄存器点表展开的读块（{@code type="registry"}，天然是读方法）。这一份对象落在单类
 * {@code ModbusPoll} 上 —— 不再需要两个子类来区分来源。</p>
 *
 * <p>{@code index} 仅作**溯源**：指向 config 的 {@code ModbusRegistry#index}（{@code type="registry"}）
 * 或 {@code ModbusCommand#index}（{@code type="command"}），说明这个方法是从哪条点表/命令配出来的。
 * 它**不是**方法在服务内的身份 —— 身份是 {@code ModbusPoll.index}（1 起自然数、同服务唯一），
 * 轮询身份（{@code modbus.polls.pollIndex}）、告警/影子的方法键都读 {@code ModbusPoll.index}。</p>
 *
 * <p>{@code access} 只对 registry 来源有意义（"read"/"write"）：command 方法的读写方向完全由
 * {@code request.fc} 决定，不需要再标一遍；registry 方法由点表展开，把点表上的读/写向带过来供溯源。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Modbus设备方法的来源（registry 或 command）与溯源序号")
public class ModbusPollFrom {

    @Schema(description = "来源：registry = 由寄存器点表展开的读块；command = 人手工配的显式命令",
            examples = {"registry", "command"}, required = true)
    public String type;

    @Schema(description = "溯源：指向 config 的 ModbusRegistry#index / ModbusCommand#index，说明方法来源；"
            + "不是方法身份（身份是 ModbusPoll.index）", examples = {"1"})
    public Integer index;

    @Schema(description = "read/write（仅 registry 来源有意义；command 的方向由 request.fc 决定）",
            examples = {"read", "write"})
    public String access;

    public ModbusPollFrom() {
    }
}