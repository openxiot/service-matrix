package cc.openxiot.matrix.db.energy;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 点位数值的口径：实际值，还是增量值。
 *
 * <p>这两个词在能耗配置里最容易配反，含义按**报表要的电量**来定：</p>
 *
 * <ul>
 *   <li>{@link #ACTUAL}（实际值）：设备给的是**累计读数**（电表上的总 kWh）。平台按相邻两次
 *       读数作差得到这段时间的用量；读数变小视为清零/换表，从新读数重新起算（见
 *       {@code EnergyAccumulator}）。</li>
 *   <li>{@link #INCREMENT}（增量值）：设备给的就是**上一次上报以来的增量**。平台直接累加。
 *       同一个值连续出现只算一次（设备离线时影子会停在旧值，不能每轮都加一遍）。</li>
 * </ul>
 *
 * <p>功率点位（{@link EnergyMetric#POWER}）不分这两种口径：它是瞬时值，按时间积分。</p>
 */
@RegisterForReflection
public enum EnergyValueMode {
    ACTUAL,
    INCREMENT
}
