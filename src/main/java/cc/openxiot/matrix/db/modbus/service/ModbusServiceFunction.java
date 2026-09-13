package cc.openxiot.matrix.db.modbus.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Modbus 服务里的一个方法：一次父设备调用 = 一帧请求 + 一条应答解析规则。
 *
 * <p>读方法（点表 fc 01/02/03/04）有 {@code response}；写方法（fc 05/06/0F/10）的应答是请求回显、
 * 没有读值，{@code response} 为空数组。</p>
 *
 * <p>{@code interval} 是服务端自动调用本方法的周期（秒），{@code polling} 是这套自动调用的总开关，
 * 两者都只挂在读方法上：写方法的应答是请求回显，周期调用等于周期性往寄存器里写值，
 * 校验层直接拒掉（见 {@code ModbusServiceValidator}）。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Modbus 服务方法")
public class ModbusServiceFunction {

    @Schema(description = "方法序号（1 起自然数，服务内唯一；对应点表功能码动作的 index）", examples = {"1"}, required = true)
    public Integer index;

    @Schema(description = "方法名称（展示用，如 读蒸发器进水温度）", required = true)
    public String name;

    @Schema(description = "请求帧：完整的 Modbus RTU 帧 16 进制字符串（含 CRC16），由前端生成，原样交给父设备发送",
            examples = {"010300000001840A"}, required = true)
    public String request;

    @Schema(description = "服务端自动调用本方法的周期（秒）：到点自动 invoke 一次；缺省表示没配周期。"
            + "只对读方法（fc 01/02/03/04）有意义，写方法填了会被校验拒绝（那等于周期性写寄存器）",
            examples = {"5"})
    public Integer interval;

    @Schema(description = "是否启用自动轮询：true = 服务端按 interval 周期自动调用；false = 保留 interval 但暂停"
            + "（随时可再开）；缺省 = 按 interval 判定，设了周期即启用。只有读方法能启用：填 true 而没给 interval、"
            + "或该方法不是读方法，都会被校验拒绝",
            examples = {"true"})
    public Boolean polling;

    @Schema(description = "应答解析规则：按 index 顺序、以 bytes 依次累加偏移切分应答数据区；空数组表示写方法")
    public List<ModbusServiceField> response;
}
