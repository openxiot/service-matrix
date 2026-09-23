package cc.openxiot.matrix.db.modbus.service.function;

import cc.openxiot.matrix.db.modbus.service.function.response.field.ModbusFunctionResponseField;
import cc.openxiot.matrix.db.modbus.service.function.request.ModbusFunctionRequest;
import cc.openxiot.matrix.db.modbus.service.function.response.ModbusFunctionResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Modbus 服务里的一个方法：一次父设备调用 = 一帧请求 + 一条应答解析规则。
 *
 * <p>读方法（点表 fc 01/02/03/04）有 {@code response}；写方法（fc 05/06/0F/10）的应答是请求回显、
 * 没有读值，{@code response} 整段不出现。</p>
 *
 * <p>{@code interval} 是服务端自动调用本方法的周期（秒），{@code polling} 是这套自动调用的总开关，
 * 两者都只挂在读方法上：写方法的应答是请求回显，周期调用等于周期性往寄存器里写值，
 * 校验层直接拒掉（见 {@code ModbusServiceValidator}）。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Modbus 服务方法")
public class ModbusFunction {

    @Schema(description = "方法序号（1 起自然数，服务内唯一；对应点表功能码动作的 index）", examples = {"1"}, required = true)
    public Integer index;

    @Schema(description = "方法名称（展示用，如 读蒸发器进水温度）", required = true)
    public String name;

    @Schema(description = "服务端自动调用本方法的周期（秒）：到点自动 invoke 一次；缺省表示没配周期。"
            + "只对读方法（fc 01/02/03/04）有意义，写方法填了会被校验拒绝（那等于周期性写寄存器）",
            examples = {"5"})
    public Integer interval;

    @Schema(description = "是否启用自动轮询：true = 服务端按 interval 周期自动调用；false = 保留 interval 但暂停"
            + "（随时可再开）；缺省 = 按 interval 判定，设了周期即启用。只有读方法能启用：填 true 而没给 interval、"
            + "或该方法不是读方法，都会被校验拒绝",
            examples = {"true"})
    public Boolean polling;

    @Schema(description = "请求帧定义：invoke 时按它现组帧（含 CRC16）交给父设备发送。读方法给数量、"
            + "写方法给写入字段（含字段名与缺省值），两者互斥", required = true)
    public ModbusFunctionRequest request;

    @Schema(description = "应答定义：fields[] 按 index 顺序、以 bytes 依次累加偏移切分应答数据区；"
            + "写方法没有应答定义（应答是请求回显），不填")
    public ModbusFunctionResponse response;

    /**
     * 应答解析字段，<b>永不为 null</b>：拿不到定义就给一张空表。
     *
     * <p>「没有应答字段」在库里有三种写法 —— {@code function} 为 null、{@code response} 整段缺失、
     * {@code fields} 为空 —— 而它们在每个调用点都指向同一件事：<b>这是写方法，应答是请求回显，没有读值</b>
     * （校验器那边也多一层判断：读方法拿到空表本身就是要报的错，所以它同样只需要读这张表）。
     * 收在这一个方法里，是为了让那十来处调用点不必各写一遍三段式空判断。</p>
     */
    public static List<ModbusFunctionResponseField> responseFields(ModbusFunction function) {
        if (function == null || function.response == null || function.response.fields == null) {
            return List.of();
        }
        return function.response.fields;
    }
}
