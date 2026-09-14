package cc.openxiot.matrix.db.modbus.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 一个出值的阈值告警配置：值越限时产生一条告警（规则见 Modbus-Alarm.md）。
 *
 * <p>挂在两个地方：{@link ModbusServiceField#alarm}（普通字段）与 {@link ModbusServiceFieldBit#alarm}
 * （01/02 位区里具名位的那个 0/1 取值）。位必须能单独配 ——
 * {@code ModbusResponseParser} 把每个位按它自己的 {@code field} 单独放进结果对象，
 * 位的值不在父字段那份整段掩码上，只挂父字段的话「位 = 1 告警」根本够不着。</p>
 *
 * <p><b>用符号而不是 GT/GTE 这类枚举名</b>：符号语言中立、与需求原话一致、日志与响应里回显无歧义。
 * 「超过 / 达到 / 低于」那些<b>词</b>是页面自己的文案，由前端翻译；符号本身不翻译。</p>
 *
 * <p>{@link #enabled} 缺省 / false 都不产生告警，但配置原样留着 ——
 * 与 {@link ModbusServiceFunction#polling} 同一条规矩（关掉不等于丢掉配置，随时可再开）。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "出值的阈值告警配置")
public class ModbusServiceFieldAlarm {

    @Schema(description = "是否启用告警：缺省/false = 不产生告警，配置原样留着（关掉不等于丢掉配置）",
            examples = {"true"})
    public Boolean enabled;

    @Schema(description = "比较方式：> 超过 / >= 达到 / < 低于 / <= 低于等于 / = 等于。"
            + "「等于」用于取值表命名的状态（比 state）与位（比 threshold 的 0/1）",
            examples = {">"})
    public String compare;

    @Schema(description = "数值阈值：数值比较必填，必为有限数。"
            + "compare 为 = 且字段带 value-list 时改用 state（那种字段的值是字符串，比不了数）",
            examples = {"80"})
    public Double threshold;

    @Schema(description = "compare 为 = 时的比较目标：字段带 value-list 时必填，"
            + "取该字段取值表里某个 description（逐字相等）；数值字段与位字段用 threshold",
            examples = {"ON"})
    public String state;

    @Schema(description = "告警级别：INFO 提示 / WARN 警告 / CRITICAL 严重", examples = {"WARN"})
    public String level;

    @Schema(description = "告警文本（用户自定义，如 温度过高 / 温度过低）：**用户数据，前端原样显示、不翻译**",
            examples = {"温度过高"})
    public String text;
}
