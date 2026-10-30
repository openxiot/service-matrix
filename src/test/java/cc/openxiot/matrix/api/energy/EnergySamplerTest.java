package cc.openxiot.matrix.api.energy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnergySampler#mergeObservations} 的回归测试。
 *
 * <p>两路观测在「没有该来源点位」时都是不可变的 {@code Map.of()}，早期实现直接在
 * 其中一侧 {@code putAll}，导致只配一种来源的配置每轮采样都抛
 * {@link UnsupportedOperationException}（message 为 null，日志只剩「failed: null」）。</p>
 */
class EnergySamplerTest {

    @Test
    void mergeWorksWhenBothSidesAreImmutableAndEmpty() {
        assertTrue(EnergySampler.mergeObservations(Map.of(), Map.of()).isEmpty());
    }

    @Test
    void mergeKeepsEntriesFromBothSides() {
        var service = new EnergySampler.Observation(1.5, null);
        var register = new EnergySampler.Observation(2.5, 1_000L);

        Map<String, EnergySampler.Observation> merged =
                EnergySampler.mergeObservations(Map.of("SERVICE|a", service), Map.of("REGISTER|b", register));

        assertEquals(service, merged.get("SERVICE|a"));
        assertEquals(register, merged.get("REGISTER|b"));
    }

    @Test
    void mergedMapIsMutable() {
        Map<String, EnergySampler.Observation> merged = EnergySampler.mergeObservations(Map.of(), Map.of());
        merged.put("REGISTER|x", new EnergySampler.Observation(1, null));
        assertEquals(1, merged.size());
    }
}
