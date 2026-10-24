package cc.openxiot.matrix.api.modbus.history;

import java.util.Date;

/**
 * 一次区间曲线查询（{@code /history/range}）的<b>响应模型</b>：把「是哪条曲线」和「窗口算成什么样」合成一份，
 * 交给 {@link ModbusRegistryHistoryRangeCodec} 编码成响应体。
 *
 * <p><b>在调用方（HTTP 的历史页、看板的字段曲线）处构建，不是由服务产出</b>。原因与
 * {@link ModbusRegistryHistoryService.RangeResult} 的注释一致：{@code serviceId} / {@code identity} /
 * {@code field} 是指名「这一条曲线」的<b>请求信息</b>（历史页来自查询参数、看板来自卡片配置），
 * 而窗口（{@code from}/{@code to}/{@code maxPoints}/{@code window}）是服务<b>算出来</b>的 ——
 * 服务只回答「这两刻之间、要多少点」，不知道也不关心这条曲线是谁。所以这个类型是两者在边界处的合并，
 * 不塞回 {@code RangeResult}。</p>
 *
 * <p>{@link #identity} 在历史页那条路是方法序号（{@code pollIndex}），在看板那条路是寄存器序号
 * （{@code registerIndex}）—— 二者都只是请求里那份标识的回显，编码时落在响应体的 {@code pollIndex} 键上；
 * 前端画图只用 {@code points} / {@code carryIn}，不按它取数据。</p>
 *
 * <p>编码要的其它字段（{@code downsampled} / {@code total} / {@code points} / {@code carryIn}）都在
 * {@link ModbusRegistryHistoryWindow.Window} 里或由编码侧从它派生，不需要进这个 record。</p>
 *
 * @param identity 曲线标识的回显：历史页 = pollIndex，看板 = registerIndex（都落到响应的 pollIndex 键）
 * @param window   窗口整形结果：降不降采样、点/桶、carry-in、要不要降采样都在这里
 */
public record ModbusRegistryHistoryRange(
        String serviceId,
        Integer identity,
        String field,
        Date from,
        Date to,
        int maxPoints,
        ModbusRegistryHistoryWindow.Window window
) {
}