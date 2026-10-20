package cc.openxiot.matrix.db.modbus.service.polling;

import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseField;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequest;
import cc.openxiot.matrix.db.modbus.service.polling.response.ModbusPollingResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Modbus 服务里的一个方法：一次父设备调用 = 一帧请求 + 一条应答解析规则。
 *
 * <p>这是**唯一**的方法类（曾经是一张抽象基类 {@code ModbusPolling} 带 command / registry 两个子类、
 * 靠 {@code kind} 判别字在多态拆开后，本文件把这种多形态**压扁**回单类）：来源不再用子类来区分，
 * 而是落在 {@link ModbusPollingFrom}（{@link #from}）上 —— {@code type="command"} 是显式命令
 * （读写都有）、{@code type="registry"} 是寄存器点表展开的读块（天然是读方法）。
 * 共享字段（{@code name} / {@code interval} / {@code polling} / {@code request} / {@code response}）
 * 都在这一类上，调用链只依赖这些共享字段就能工作，不再有「先看哪种 kind 再分支」的环节。</p>
 *
 * <p>读方法（点表 fc 01/02/03/04）有 {@code response}；写方法（fc 05/06/0F/10）的应答是请求回显、
 * 没有读值，{@code response} 整段不出现。registry 方法天然是读方法，它的 {@code request} /
 * {@code response} 由前端从点表展开（register-derived read block），后端把它当读方法处理。</p>
 *
 * <p>{@code interval} 是服务端自动调用本方法的周期（秒），{@code polling} 是这套自动调用的总开关，
 * 两者都只挂在读方法上：写方法的应答是请求回显，周期调用等于周期性往寄存器里写值，
 * 校验层直接拒掉（见 {@code ModbusServiceValidator}）。</p>
 *
 * <p>{@link #index} 是方法在服务内的序号（1 起自然数、同服务唯一）—— 轮询身份
 * （{@code modbus.polls.pollingIndex}）、告警/影子/历史的方法键都读它。
 * {@link ModbusPollingFrom#index} 只作**溯源**（指向 config 的 {@code ModbusRegistry#index} /
 * {@code ModbusCommand#index}），**不作轮询身份**，别拿它当 identity。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Modbus 服务方法")
public class ModbusPolling {

    @Schema(description = "方法在服务内的序号（1 起自然数、同服务唯一）：轮询身份（modbus.polls.pollingIndex）、"
            + "告警/影子/历史的方法键都读它", examples = {"1"}, required = true)
    public Integer index;

    @Schema(description = "方法来源：registry 或 command，见 {@link ModbusPollingFrom}；其中 from.index 仅作溯源"
            + "（指向 config 的 ModbusRegistry#index / ModbusCommand#index），不作轮询身份（身份用本类的 index）",
            required = true)
    public ModbusPollingFrom from;

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
    public ModbusPollingRequest request;

    @Schema(description = "应答定义：fields[] 按 index 顺序、以 bytes 依次累加偏移切分应答数据区；"
            + "写方法没有应答定义（应答是请求回显），不填")
    public ModbusPollingResponse response;

    public ModbusPolling() {
    }

    /**
     * 应答解析字段，<b>永不为 null</b>：拿不到定义就给一张空表。
     *
     * <p>「没有应答字段」在库里有三种写法 —— {@code function} 为 null、{@code response} 整段缺失、
     * {@code fields} 为空 —— 而它们在每个调用点都指向同一件事：<b>这是写方法，应答是请求回显，没有读值</b>
     * （校验器那边也多一层判断：读方法拿到空表本身就是要报的错，所以它同样只需要读这张表）。
     * 收在这一个方法里，是为了让那十来处调用点不必各写一遍三段式空判断。</p>
     */
    public static List<ModbusPollingResponseField> responseFields(ModbusPolling function) {
        if (function == null || function.response == null || function.response.fields == null) {
            return List.of();
        }
        return function.response.fields;
    }
}