package cc.openxiot.matrix.api.energy;

import cc.openxiot.matrix.db.energy.EnergyMetric;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergySourceKind;
import cc.openxiot.matrix.db.energy.EnergyValueMode;
import cc.openxiot.matrix.db.energy.config.EnergyConfig;
import cc.openxiot.matrix.db.energy.config.EnergyPoint;
import cc.openxiot.matrix.db.energy.stat.EnergySample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnergyAccumulator} 的口径：累计差值、回绕/清零、增量去重、功率积分、多设备合并、计价。
 *
 * <p>全是纯函数，不需要 Mongo，也不需要等采样线程 —— 「算得对不对」在这里断言。</p>
 */
class EnergyAccumulatorTest {

    private static final long MINUTE = 60_000L;
    private static final long NOW = 1_700_000_000_000L;

    // ===== 累计量：实际值 =====

    @Test
    void actualFirstReadingIsOnlyABaseline() {
        EnergyAccumulator.PointOutcome outcome = consume(point(EnergyMetric.ENERGY, EnergyValueMode.ACTUAL),
                120.0, true, null, null, 60_000L);
        assertEquals(0.0, outcome.delta());
        assertEquals(120.0, outcome.lastRaw());
        assertTrue(outcome.sample().isEmpty());
    }

    @Test
    void actualDeltaIsCurrentMinusPrevious() {
        EnergyAccumulator.PointOutcome outcome = consume(point(EnergyMetric.ENERGY, EnergyValueMode.ACTUAL),
                125.5, true, 120.0, NOW - MINUTE, 60_000L);
        assertEquals(5.5, outcome.delta(), 1e-9);
        assertEquals(5.5, outcome.sample().energy(), 1e-9);
    }

    @Test
    void actualCounterRolloverCountsFromZero() {
        // 换表 / 清零：新读数就是清零之后累计到的量，不能算成「负的用量」
        EnergyAccumulator.PointOutcome outcome = consume(point(EnergyMetric.ENERGY, EnergyValueMode.ACTUAL),
                3.0, true, 9_000.0, NOW - MINUTE, 60_000L);
        assertEquals(3.0, outcome.delta(), 1e-9);
    }

    @Test
    void actualMissingReadingKeepsBaseline() {
        EnergyAccumulator.PointOutcome outcome = consume(point(EnergyMetric.ENERGY, EnergyValueMode.ACTUAL),
                null, false, 120.0, NOW - MINUTE, 60_000L);
        assertEquals(0.0, outcome.delta());
        assertEquals(120.0, outcome.lastRaw());
        assertTrue(outcome.sample().isEmpty());
    }

    // ===== 累计量：增量值 =====

    @Test
    void incrementCountsOnlyFreshReadings() {
        EnergyPoint point = point(EnergyMetric.CHARGE_ENERGY, EnergyValueMode.INCREMENT);
        // 寄存器 recordedAt 比上次新：这次读是新的，值就是这一段充进去的电
        assertEquals(2.0, consume(point, 2.0, true, 0.0, NOW - MINUTE, 60_000L).delta(), 1e-9);
        // 设备离线/影子没刷新：同一个读数不能再加一次
        assertEquals(0.0, consume(point, 2.0, false, 0.0, NOW - MINUTE, 60_000L).delta(), 1e-9);
    }

    // ===== 功率积分 =====

    @Test
    void powerIntegratesOverElapsedTime() {
        // 1.8 kW 持续 30 秒 = 0.015 kWh
        EnergyAccumulator.PointOutcome outcome = consume(point(EnergyMetric.POWER, EnergyValueMode.ACTUAL),
                1.8, true, null, NOW - 30_000L, 60_000L);
        assertEquals(0.015, outcome.sample().charge(), 1e-9);
        assertEquals(0.0, outcome.sample().discharge(), 1e-9);
        assertEquals(1.8, outcome.sample().power(), 1e-9);
    }

    @Test
    void negativePowerIsDischarge() {
        EnergyAccumulator.PointOutcome outcome = consume(point(EnergyMetric.POWER, EnergyValueMode.ACTUAL),
                -1.2, true, null, NOW - 30_000L, 60_000L);
        assertEquals(0.0, outcome.sample().charge(), 1e-9);
        assertEquals(0.01, outcome.sample().discharge(), 1e-9);
    }

    @Test
    void powerIntegrationClampsDowntime() {
        // 进程停了一夜：最多补 3 个采样周期，不能凭空积出一整夜的电
        EnergyAccumulator.PointOutcome outcome = consume(point(EnergyMetric.POWER, EnergyValueMode.ACTUAL),
                1.0, true, null, NOW - 10 * 3_600_000L, 60_000L);
        assertEquals(3.0 * 60_000L / 3_600_000.0, outcome.sample().charge(), 1e-9);
    }

    @Test
    void powerWithoutIntegrationOnlyReportsTheReading() {
        EnergyAccumulator.PointOutcome outcome = EnergyAccumulator.consume(
                point(EnergyMetric.POWER, EnergyValueMode.ACTUAL),
                1.0, true, NOW, null, NOW - 30_000L, false, 60_000L);
        assertEquals(0.0, outcome.sample().charge(), 1e-9);
        assertEquals(1.0, outcome.sample().power(), 1e-9);
    }

    // ===== 多设备合并 =====

    @Test
    void combineSumsEnergyAndAverageSoc() {
        EnergySample a = new EnergySample(1, 0, 0, 0, 0, 500.0, 60.0, 100.0, 98.0, 25.0, 10.0);
        EnergySample b = new EnergySample(2, 0, 0, 0, 0, 300.0, 40.0, 200.0, 96.0, 27.0, 20.0);
        EnergySample combined = EnergyAccumulator.combine(List.of(a, b));
        assertEquals(3.0, combined.energy(), 1e-9);
        assertEquals(800.0, combined.power(), 1e-9);
        assertEquals(50.0, combined.soc(), 1e-9);
        assertEquals(300.0, combined.capacity(), 1e-9); // 容量相加 = 总容量
        assertEquals(97.0, combined.soh(), 1e-9);
        assertEquals(26.0, combined.temperature(), 1e-9);
        assertEquals(30.0, combined.cycles(), 1e-9);
    }

    @Test
    void combineWithoutSocLeavesItNull() {
        assertNull(EnergyAccumulator.combine(List.of(EnergySample.EMPTY)).soc());
    }

    // ===== 计价 =====

    @Test
    void consumptionIsCost() {
        EnergyConfig config = config(EnergyRole.CONSUMPTION, 0.8, null, null);
        EnergySample priced = EnergyAccumulator.price(config, new EnergySample(10, 0, 0, 0, 0, null, null, null, null, null, null));
        assertEquals(8.0, priced.cost(), 1e-9);
        assertEquals(0.0, priced.revenue(), 1e-9);
    }

    @Test
    void productionIsRevenue() {
        EnergyConfig config = config(EnergyRole.PRODUCTION, 0.4, null, null);
        EnergySample priced = EnergyAccumulator.price(config, new EnergySample(10, 0, 0, 0, 0, null, null, null, null, null, null));
        assertEquals(0.0, priced.cost(), 1e-9);
        assertEquals(4.0, priced.revenue(), 1e-9);
    }

    @Test
    void storagePricesChargeAndDischargeSeparately() {
        EnergyConfig config = config(EnergyRole.STORAGE, null, 0.5, 1.2);
        EnergySample priced = EnergyAccumulator.price(config, new EnergySample(0, 10, 4, 0, 0, null, null, null, null, null, null));
        assertEquals(5.0, priced.cost(), 1e-9);
        assertEquals(4.8, priced.revenue(), 1e-9);
    }

    @Test
    void missingPriceMeansNoMoney() {
        EnergyConfig config = config(EnergyRole.CONSUMPTION, null, null, null);
        EnergySample priced = EnergyAccumulator.price(config, new EnergySample(10, 0, 0, 0, 0, null, null, null, null, null, null));
        assertEquals(0.0, priced.cost(), 1e-9);
    }

    // ===== helpers =====

    private static EnergyAccumulator.PointOutcome consume(EnergyPoint point, Double raw, boolean fresh,
                                                          Double lastRaw, Long lastAt, long interval) {
        return EnergyAccumulator.consume(point, raw, fresh, NOW, lastRaw, lastAt, true, interval);
    }

    private static EnergyPoint point(EnergyMetric metric, EnergyValueMode mode) {
        EnergyPoint point = new EnergyPoint();
        point.source = EnergySourceKind.REGISTER;
        point.fdid = "fd-1";
        point.registerIndex = 0;
        point.metric = metric;
        point.mode = mode;
        point.name = metric.name();
        return point;
    }

    private static EnergyConfig config(EnergyRole role, Double price, Double chargePrice, Double dischargePrice) {
        EnergyConfig config = new EnergyConfig();
        config.role = role;
        config.price = price;
        config.chargePrice = chargePrice;
        config.dischargePrice = dischargePrice;
        return config;
    }
}
