package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.api.modbus.history.ModbusHistoryRetention.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ModbusHistoryRetention} 的保留口径（Modbus-History.md 第六节）。
 *
 * <p>纯函数，不需要数据库 —— TTL 索引到底建不建得上要真库（Azure DocumentDB）才说了算，
 * 这里钉的只是「配置天数 → 秒数」与「该做什么」这两个判定。</p>
 */
class ModbusHistoryRetentionTest {

    /** 90 天的秒数。 */
    private static final long NINETY_DAYS = 7_776_000L;

    // ===== 天数 → 秒数 =====

    @Test
    void defaultRetentionIsNinetyDays() {
        assertEquals(NINETY_DAYS, ModbusHistoryRetention.ttlSeconds(90));
    }

    @Test
    void oneDayIsOneDayOfSeconds() {
        assertEquals(ModbusHistoryRetention.SECONDS_PER_DAY, ModbusHistoryRetention.ttlSeconds(1));
    }

    @Test
    void zeroAndNegativeMeanOff() {
        // 「关」= 返回 0 = 调用方不建 TTL 索引；缺省是 90，要关只能显式设 0
        assertEquals(0L, ModbusHistoryRetention.ttlSeconds(0));
        assertEquals(0L, ModbusHistoryRetention.ttlSeconds(-1));
    }

    // ===== 该做什么 =====

    @Test
    void freshCollectionGetsTheIndex() {
        // 新集合：没有 TTL 索引（null）→ 建。这是最常走的一条
        assertEquals(Action.CREATE, ModbusHistoryRetention.decide(null, NINETY_DAYS));
    }

    @Test
    void sameSecondsIsNoOp() {
        // 每次启动都会走一遍：秒数没变就不该动索引（drop + create 在大集合上有代价）
        assertEquals(Action.NONE, ModbusHistoryRetention.decide(7_776_000, NINETY_DAYS));
    }

    @Test
    void changingDaysRebuildsTheIndex() {
        // 90 天改 30 天：不重建就静默不生效，90 天前的数据继续躺着
        assertEquals(Action.RECREATE, ModbusHistoryRetention.decide(7_776_000, 2_592_000L));
        // 反过来（30 → 90）同理：不重建会让数据比约定的更早被删
        assertEquals(Action.RECREATE, ModbusHistoryRetention.decide(2_592_000, NINETY_DAYS));
    }

    @Test
    void turningOffDropsTheExistingIndex() {
        // 「关」必须把已有的删掉：留着的话数据照删，配置就撒了谎
        assertEquals(Action.DROP, ModbusHistoryRetention.decide(7_776_000, 0L));
    }

    @Test
    void turningOffWithNoIndexIsNoOp() {
        // 本来就关着、也没有索引：别每次启动都去 drop 一个不存在的东西
        assertEquals(Action.NONE, ModbusHistoryRetention.decide(null, 0L));
    }
}
