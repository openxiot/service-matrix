package cc.openxiot.matrix.api.modbus.poll;

import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusPollBaselines}：内存基准能不能吃。
 *
 * <p>判定只有一条，但它是「故障态必须读库」这条口径的唯一落点：吃错一次基准，
 * 会把一次新的故障并进上一次的账上（{@code errorAt} 不刷新 → 故障时长算成两个故障的和）。</p>
 */
class ModbusPollBaselinesTest {

    private static final ModbusPollKey KEY = new ModbusPollKey("svc-1", 1);

    @Test
    void usableRejectsTheAbsentAndTheFaulted() {
        assertFalse(ModbusPollBaselines.usable(null));
        assertTrue(ModbusPollBaselines.usable(shadow(null)));
        assertFalse(ModbusPollBaselines.usable(shadow("DTU offline")));   // 故障态：回退读库
    }

    @Test
    void getReturnsNullWhenThereIsNothingUsable() {
        ModbusPollBaselines baselines = new ModbusPollBaselines();

        assertNull(baselines.get(KEY));                  // 冷启动：缓存是空的
        assertNull(baselines.get(null));

        baselines.put(KEY, shadow(null));
        assertSame(baselines.get(KEY), baselines.get(KEY));

        baselines.put(KEY, shadow("CRC error"));         // 故障态盖掉之后就不再可用
        assertNull(baselines.get(KEY));
    }

    /** 这次什么都没写（没有新基准）：保留旧缓存 —— 清掉只会让下一次采集白读一次库。 */
    @Test
    void putWithNullKeepsTheOldBaseline() {
        ModbusPollBaselines baselines = new ModbusPollBaselines();
        ModbusServiceShadow cached = shadow(null);
        baselines.put(KEY, cached);

        baselines.put(KEY, null);
        baselines.put(null, shadow(null));

        assertEquals(1, baselines.size());
        assertSame(cached, baselines.get(KEY));
    }

    @Test
    void forgetDropsTheBaseline() {
        ModbusPollBaselines baselines = new ModbusPollBaselines();
        baselines.put(KEY, shadow(null));
        baselines.put(new ModbusPollKey("svc-2", 1), shadow(null));

        baselines.forget(KEY);

        assertEquals(1, baselines.size());
        assertNull(baselines.get(KEY));
        baselines.forget(null);                          // 不抛
        baselines.forget(new ModbusPollKey("svc-9", 9));
    }

    private static ModbusServiceShadow shadow(String lastError) {
        ModbusServiceShadow shadow = new ModbusServiceShadow();
        shadow.serviceId = "svc-1";
        shadow.pollingIndex = 1;
        shadow.fields = Map.of("temp", 21);
        shadow.lastError = lastError;
        return shadow;
    }
}
