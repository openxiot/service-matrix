package cc.openxiot.matrix.api.energy.summary;

import cc.openxiot.matrix.api.energy.EnergyGranularity;
import cc.openxiot.matrix.api.energy.EnergyRollup;
import cc.openxiot.matrix.api.energy.EnergySeriesReader;
import cc.openxiot.matrix.api.energy.status.EnergyStatus;
import cc.openxiot.matrix.api.energy.status.EnergyStatusService;
import cc.openxiot.matrix.db.energy.EnergyConfig;
import cc.openxiot.matrix.db.energy.EnergyConfigRepository;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergyStat;
import cc.openxiot.matrix.db.energy.EnergyStatRepository;
import cc.openxiot.matrix.db.energy.EnergyType;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 摘要的五张卡口径、电网夹到 0、上一期窗口、空数据与停用配置。
 *
 * <p>用真实的 {@link EnergySeriesReader} + 假的 {@code statRepo}：摘要最容易错的是
 * 「角色 × 类型怎么映射成卡」，rollup 的合并规则已有 <code>EnergyRollupTest</code> 覆盖。</p>
 */
class EnergySummaryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2024-01-01 00:00 +08:00 */
    private static final long FROM = 1_704_038_400_000L;
    private static final long HOUR = EnergyRollup.HOUR;
    private static final long TO = FROM + 2 * HOUR;
    private static final long PREVIOUS_FROM = FROM - 2 * HOUR;

    private final EnergyConfigRepository configRepo = mock(EnergyConfigRepository.class);
    private final EnergyStatRepository statRepo = mock(EnergyStatRepository.class);
    private final EnergyStatusService statusService = mock(EnergyStatusService.class);
    private final EnergySummaryService service = new EnergySummaryService();

    @BeforeEach
    void setUp() {
        EnergySeriesReader reader = new EnergySeriesReader(statRepo);
        service.configRepo = configRepo;
        service.seriesReader = reader;
        service.statusService = statusService;
        when(statusService.status(anyString(), any(), any()))
                .thenReturn(Uni.createFrom().item(emptyStatus()));
        // 默认「没配的窗口没数据」；具体用例再用精确的窗口覆盖
        when(statRepo.findRange(anyString(), anyLong(), anyLong()))
                .thenReturn(Uni.createFrom().item(List.of()));
    }

    @Test
    void mapsEachCardFromItsRoleAndType() {
        EnergyConfig consumption = config(EnergyRole.CONSUMPTION, EnergyType.ELECTRICITY);
        EnergyConfig production = config(EnergyRole.PRODUCTION, EnergyType.ELECTRICITY);
        EnergyConfig gas = config(EnergyRole.CONSUMPTION, EnergyType.GAS);
        EnergyConfig water = config(EnergyRole.CONSUMPTION, EnergyType.WATER);
        EnergyConfig storage = config(EnergyRole.STORAGE, EnergyType.ELECTRICITY);
        configs(consumption, production, gas, water, storage);

        current(consumption, stat(FROM, 5.0));
        previous(consumption, stat(PREVIOUS_FROM, 4.0));
        current(production, stat(FROM, 2.0));
        previous(production, stat(PREVIOUS_FROM, 1.0));
        current(gas, stat(FROM, 3.0));
        current(water, stat(FROM, 1.5));
        previous(water, stat(PREVIOUS_FROM, 1.0));
        current(storage, storageStat(FROM, 2.5, 1.0, -2.0, 42.0));
        previous(storage, storageStat(PREVIOUS_FROM, 2.0, 0.5, 1.0, 40.0));

        EnergySummary summary = summary();

        assertEquals(List.of(EnergySummary.Key.SOLAR, EnergySummary.Key.BATTERY,
                        EnergySummary.Key.GRID, EnergySummary.Key.GAS, EnergySummary.Key.WATER),
                summary.totals().stream().map(EnergySummary.Total::key).toList());
        assertEquals(2.0, total(summary, EnergySummary.Key.SOLAR).value(), 1e-9);
        assertEquals(2.5, total(summary, EnergySummary.Key.BATTERY).value(), 1e-9);
        assertEquals(3.0, total(summary, EnergySummary.Key.GRID).value(), 1e-9);
        assertEquals(3.0, total(summary, EnergySummary.Key.GAS).value(), 1e-9);
        assertEquals(1.5, total(summary, EnergySummary.Key.WATER).value(), 1e-9);

        assertEquals(1.0, total(summary, EnergySummary.Key.SOLAR).previous(), 1e-9);
        assertEquals(2.0, total(summary, EnergySummary.Key.BATTERY).previous(), 1e-9);
        assertEquals(3.0, total(summary, EnergySummary.Key.GRID).previous(), 1e-9);
        assertNull(total(summary, EnergySummary.Key.GAS).previous());
        assertEquals(1.0, total(summary, EnergySummary.Key.WATER).previous(), 1e-9);

        assertEquals(2.5, summary.charge(), 1e-9);
        assertEquals(1.0, summary.discharge(), 1e-9);
        assertEquals("kWh", total(summary, EnergySummary.Key.SOLAR).unit());
        assertEquals("m³", total(summary, EnergySummary.Key.GAS).unit());
    }

    @Test
    void gridClampsAtZero() {
        EnergyConfig consumption = config(EnergyRole.CONSUMPTION, EnergyType.ELECTRICITY);
        EnergyConfig production = config(EnergyRole.PRODUCTION, EnergyType.ELECTRICITY);
        configs(consumption, production);
        current(consumption, stat(FROM, 1.0));
        current(production, stat(FROM, 3.0));
        previous(production, stat(PREVIOUS_FROM, 2.0));
        previous(consumption, stat(PREVIOUS_FROM, 1.0));

        EnergySummary summary = summary();

        assertEquals(0.0, total(summary, EnergySummary.Key.GRID).value(), 1e-9);
        // 上一期发电覆盖全部用电 → 净购电 0；环比的分母为 0 时给 null，不显示百分比
        assertNull(total(summary, EnergySummary.Key.GRID).previous());
    }

    @Test
    void productionWithoutConfigCountsAsZero() {
        EnergyConfig consumption = config(EnergyRole.CONSUMPTION, EnergyType.ELECTRICITY);
        configs(consumption);
        current(consumption, stat(FROM, 5.0));
        previous(consumption, stat(PREVIOUS_FROM, 4.0));

        EnergySummary summary = summary();

        assertNull(total(summary, EnergySummary.Key.SOLAR).value());
        assertEquals(5.0, total(summary, EnergySummary.Key.GRID).value(), 1e-9);
        assertEquals(4.0, total(summary, EnergySummary.Key.GRID).previous(), 1e-9);
    }

    @Test
    void productionConfiguredWithoutSamplesMakesGridNull() {
        EnergyConfig consumption = config(EnergyRole.CONSUMPTION, EnergyType.ELECTRICITY);
        EnergyConfig production = config(EnergyRole.PRODUCTION, EnergyType.ELECTRICITY);
        configs(consumption, production);
        current(consumption, stat(FROM, 5.0));
        // 发电有配置但窗口内一个采样点都没有
        when(statRepo.findRange(production.id.toHexString(), FROM, TO))
                .thenReturn(Uni.createFrom().item(List.of()));

        EnergySummary summary = summary();

        assertNull(total(summary, EnergySummary.Key.SOLAR).value());
        assertNull(total(summary, EnergySummary.Key.GRID).value());
    }

    @Test
    void noConfigsGivesNullTotalsAndDenseEmptySeries() {
        when(configRepo.listBySpace("space")).thenReturn(Uni.createFrom().item(List.of()));

        EnergySummary summary = summary();

        for (EnergySummary.Total total : summary.totals()) {
            assertNull(total.value(), total.key() + " should be null");
            assertNull(total.previous(), total.key() + " previous should be null");
        }
        assertNull(summary.charge());
        assertNull(summary.discharge());
        assertNull(summary.storage());

        assertEquals(2, summary.series().consumption().size());
        assertEquals(0, summary.series().consumption().get(0).samples());
        assertEquals(FROM, summary.series().consumption().get(0).at());
        assertNull(summary.series().storage().get(0).socAvg());
        assertNull(summary.series().storage().get(0).powerAvg());
    }

    @Test
    void disabledConfigStillCountsInWindowTotals() {
        EnergyConfig disabled = config(EnergyRole.CONSUMPTION, EnergyType.ELECTRICITY);
        disabled.enabled = false;
        configs(disabled);
        current(disabled, stat(FROM, 2.0));

        EnergySummary summary = summary();

        assertEquals(2.0, total(summary, EnergySummary.Key.GRID).value(), 1e-9);
    }

    @Test
    void previousZeroBecomesNull() {
        EnergyConfig gas = config(EnergyRole.CONSUMPTION, EnergyType.GAS);
        configs(gas);
        current(gas, stat(FROM, 3.0));
        // 有采样但确实是 0：环比不显示 0%
        previous(gas, stat(PREVIOUS_FROM, 0.0));

        EnergySummary summary = summary();

        assertEquals(3.0, total(summary, EnergySummary.Key.GAS).value(), 1e-9);
        assertNull(total(summary, EnergySummary.Key.GAS).previous());
    }

    @Test
    void storageStateComesFromStatus() {
        when(configRepo.listBySpace("space")).thenReturn(Uni.createFrom().item(List.of()));
        EnergyStatus.Group group = new EnergyStatus.Group(
                "battery-1", "电池", 78.0, 120.0, 93.6, -8.4, 99.0, 28.0, 300.0, 1.0, 0.5, null);
        when(statusService.status(anyString(), any(), any()))
                .thenReturn(Uni.createFrom().item(
                        new EnergyStatus("space", "ELECTRICITY", List.of(group), group)));

        EnergySummary summary = summary();

        assertEquals(78.0, summary.storage().soc(), 1e-9);
        assertEquals(93.6, summary.storage().available(), 1e-9);
        assertEquals(120.0, summary.storage().capacity(), 1e-9);
        assertEquals(-8.4, summary.storage().power(), 1e-9);
    }

    @Test
    void granularityFollowsSpan() {
        when(configRepo.listBySpace("space")).thenReturn(Uni.createFrom().item(List.of()));

        EnergySummary summary = service.summary("space", FROM, FROM + 3 * EnergyRollup.DAY, ZONE)
                .await().indefinitely();

        assertEquals(EnergyGranularity.DAY, summary.granularity());
        assertEquals(3, summary.series().consumption().size());
        assertEquals(FROM, summary.series().consumption().get(0).at());
    }

    @Test
    void rejectsMissingFrom() {
        assertThrows(IllegalArgumentException.class,
                () -> service.summary("space", null, TO, ZONE).await().indefinitely());
    }

    @Test
    void rejectsNonPositiveWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> service.summary("space", TO, TO, ZONE).await().indefinitely());
    }

    @Test
    void rejectsWindowLongerThanAYear() {
        assertThrows(IllegalArgumentException.class,
                () -> service.summary("space", FROM, FROM + 367 * EnergyRollup.DAY, ZONE)
                        .await().indefinitely());
    }

    private EnergySummary summary() {
        return service.summary("space", FROM, TO, ZONE).await().indefinitely();
    }

    private void configs(EnergyConfig... configs) {
        when(configRepo.listBySpace("space")).thenReturn(Uni.createFrom().item(List.of(configs)));
    }

    private void current(EnergyConfig config, EnergyStat stat) {
        when(statRepo.findRange(config.id.toHexString(), FROM, TO))
                .thenReturn(Uni.createFrom().item(List.of(stat)));
    }

    private void previous(EnergyConfig config, EnergyStat stat) {
        when(statRepo.findRange(config.id.toHexString(), PREVIOUS_FROM, FROM))
                .thenReturn(Uni.createFrom().item(List.of(stat)));
    }

    private static EnergySummary.Total total(EnergySummary summary, EnergySummary.Key key) {
        Map<EnergySummary.Key, EnergySummary.Total> byKey = summary.totals().stream()
                .collect(Collectors.toMap(EnergySummary.Total::key, Function.identity()));
        return byKey.get(key);
    }

    private static EnergyConfig config(EnergyRole role, EnergyType type) {
        EnergyConfig config = new EnergyConfig();
        config.id = new ObjectId();
        config.name = role + "/" + type;
        config.role = role;
        config.type = type;
        config.points = new ArrayList<>();
        return config;
    }

    private static EnergyStat stat(long bucketAt, double energy) {
        EnergyStat stat = new EnergyStat();
        stat.bucketAt = bucketAt;
        stat.energy = energy;
        stat.samples = 1;
        return stat;
    }

    private static EnergyStat storageStat(long bucketAt, double charge, double discharge,
                                          double power, double soc) {
        EnergyStat stat = stat(bucketAt, 0);
        stat.charge = charge;
        stat.discharge = discharge;
        stat.powerSum = power;
        stat.powerCount = 1;
        stat.socSum = soc;
        stat.socCount = 1;
        return stat;
    }

    private static EnergyStatus emptyStatus() {
        return new EnergyStatus("space", "ELECTRICITY", List.of(),
                new EnergyStatus.Group(null, null, null, null, null, null, null, null, null, 0, 0, null));
    }
}
