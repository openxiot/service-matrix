package cc.openxiot.matrix.db.modbus.alarm;

/**
 * 判定侧的**状态值**：某个出值当前开着的那条行，是**哪条规则**开的、以及**有没有被人处理过**。
 *
 * <p>判定（{@code ModbusAlarmPolicy}）输出的是状态的差量，所以它每一步都要先知道「这个出值现在开着吗」。
 * 这一张「出值名 → 本记录」的表就是那份状态的物化 —— 由
 * {@link ModbusServiceAlarmRepository#mapOpenRuleByField} 一次查出来（只投影 {@code field} /
 * {@code ruleId} / {@code handled} 三列），判定过程里不再回查库，见 {@code Modbus-Alarm.md} 第五节。</p>
 *
 * <h3>为什么是两个字段而不是一个 ruleId</h3>
 *
 * <p>{@code ruleId} 回答「这条开着的行是不是还该开着」：与当前胜者的 {@code id} 不同就说明换了规则
 * （关闭原因 {@link ModbusServiceAlarm#CLOSE_SUPERSEDED}）。</p>
 *
 * <p>{@code handled} 回答「这条已经被人认领过了吗」：同一条规则仍在命中、而这条已处理过时，
 * 要把它关掉（{@link ModbusServiceAlarm#CLOSE_HANDLED}）并另开一条新的 —— 「处理」的意思是
 * 「我知道了，以后还有越线再提醒我」。两者必须一起读：只看 {@code ruleId} 会把已处理的行
 * 一直当成稳态，后续的越线全被挡在门外。</p>
 *
 * <p><b>{@code handled} 是原始布尔而不是可空的 {@code Boolean}。</b>库里的 {@code handled} 允许缺失
 * （更早的数据，以及插入那一刻的写法），而「缺失」在判定上的含义与 {@code false} **完全一样**：
 * 未处理。转换只在装填这一处做（{@code Boolean.TRUE.equals(...)}），判定侧就不必再防一次 null。</p>
 *
 * <p>放在 db 包而不是判定所在的 api 包，是为了不新造一条 {@code db → api} 的反向依赖：
 * 生产方（仓储）与消费方（判定）分属两侧，现有箭头只有 api → db。</p>
 */
public record OpenAlarmRule(String ruleId, boolean handled) {
}
