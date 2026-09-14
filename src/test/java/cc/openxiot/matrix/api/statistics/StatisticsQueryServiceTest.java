package cc.openxiot.matrix.api.statistics;

import cc.openxiot.matrix.api.statistics.StatisticsQueryService.Bucket;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.Count;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.DeviceStats;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.ServiceStats;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link StatisticsQueryService} 的归并与校验：分桶、按类型 / 点表 / 文本分组、窗口边界。
 *
 * <p>全是静态纯函数（{@code @QuarkusTest} 要连 Mongo，这个仓库跑不了），所以这里断言的正是
 * 「算得对不对」—— 取数那一段（逐服务 fan-out）由图上的形状与接口的集成测试覆盖。</p>
 */
class StatisticsQueryServiceTest {

    /**
     * 基准时刻：2023-11-14 前后，**向下取到整点**。
     *
     * <p>取整点是故意的：下面好几条都在断言「桶起点是哪个时刻」，基准自己要不是整点，
     * 断言就得跟着写一串偏移，桶的边界反而看不清楚。</p>
     */
    private static final long T0 = StatisticsQueryService.hourStart(1_700_000_000_000L);
    private static final long HOUR = StatisticsQueryService.HOUR;
    private static final long SECOND = 1_000L;

    // ===== 窗口校验 =====

    @Test
    void rejectsMissingFrom() {
        assertEquals("from is required",
                StatisticsQueryService.checkRange(null, new Date(T0)));
    }

    @Test
    void rejectsEmptyOrReversedRange() {
        // 零宽也算反了：只会查出空结果，不如直接说清楚（与告警清单同一条）
        assertEquals("from must be before to",
                StatisticsQueryService.checkRange(new Date(T0), new Date(T0)));
        assertEquals("from must be before to",
                StatisticsQueryService.checkRange(new Date(T0), new Date(T0 - HOUR)));
    }

    @Test
    void rejectsWindowWiderThanAMonth() {
        // 31 天正好可以，多一毫秒就不行 —— 这条是「不打 limit」的代价换来的闸门
        assertNull(StatisticsQueryService.checkRange(new Date(T0),
                new Date(T0 + StatisticsQueryService.MAX_WINDOW)));
        assertEquals("time range too wide", StatisticsQueryService.checkRange(new Date(T0),
                new Date(T0 + StatisticsQueryService.MAX_WINDOW + 1)));
    }

    // ===== 整点分桶 =====

    @Test
    void fillsEveryHourOfTheWindowIncludingEmptyOnes() {
        // 三个整点桶，中间那个一次都没有：**照样在**，值为 0（漏桶与零桶在图上长得一样）
        List<Bucket> buckets = StatisticsQueryService.hourly(
                List.of(new Date(T0), new Date(T0 + HOUR * 2)),
                new Date(T0), new Date(T0 + HOUR * 2 + SECOND * 30));

        assertEquals(3, buckets.size());
        assertEquals(List.of(T0, T0 + HOUR, T0 + HOUR * 2),
                buckets.stream().map(Bucket::at).toList());
        assertEquals(List.of(1, 0, 1), buckets.stream().map(Bucket::count).toList());
    }

    @Test
    void countsEveryMomentInsideTheSameHour() {
        // 同一小时内三次都算在这一桶上（桶按 at 整除到整点，不看分钟）
        List<Bucket> buckets = StatisticsQueryService.hourly(
                List.of(new Date(T0 + SECOND), new Date(T0 + SECOND * 30), new Date(T0 + SECOND * 59 * 60)),
                new Date(T0), new Date(T0 + HOUR - SECOND));

        assertEquals(1, buckets.size());
        assertEquals(3, buckets.getFirst().count());
    }

    @Test
    void putsTheExactBoundariesInTheFirstAndLastBucket() {
        // 边界：at 正好落在 from / to 上时各归哪一桶 —— 闭区间，两头都在
        List<Bucket> buckets = StatisticsQueryService.hourly(
                List.of(new Date(T0), new Date(T0 + HOUR)),
                new Date(T0), new Date(T0 + HOUR));

        assertEquals(2, buckets.size());
        assertEquals(List.of(1, 1), buckets.stream().map(Bucket::count).toList());
    }

    @Test
    void ignoresMomentsOutsideTheWindow() {
        // 查询已经过滤过，这里是纯函数自己的边界：越界的时刻只能被丢掉，不能撑大数组
        List<Bucket> buckets = StatisticsQueryService.hourly(
                Arrays.asList(new Date(T0 - HOUR), new Date(T0), new Date(T0 + HOUR * 5), null),
                new Date(T0), new Date(T0 + HOUR));

        assertEquals(2, buckets.size());
        assertEquals(List.of(1, 0), buckets.stream().map(Bucket::count).toList());
    }

    @Test
    void singleHourWindowHasExactlyOneBucket() {
        // to 就落在 from 那一小时里（首页「现在」正是这种情形）：一个部分桶
        List<Bucket> buckets = StatisticsQueryService.hourly(List.of(),
                new Date(T0 + SECOND * 10), new Date(T0 + SECOND * 20));

        assertEquals(1, buckets.size());
        assertEquals(0, buckets.getFirst().count());
    }

    // ===== 分组 =====

    @Test
    void sortsByCountDescendingThenByKeyAscending() {
        // 条数降序；同数按名字升序 —— 输出稳定，前端不必再排
        assertEquals(List.of(new Count("b", 3), new Count("a", 2), new Count("c", 2)),
                StatisticsQueryService.tally(List.of("a", "b", "b", "b", "a", "c", "c")));
    }

    @Test
    void alarmTextsBucketMissingOnesAsUnknown() {
        // 缺文本 / 空文本 / 只有空白都归 UNKNOWN 桶：归桶是为了让计数与总数对得上，
        // 不是替用户编一个告警文本（桶名与告警页 byText 的完全一致）
        List<String> keys = StatisticsQueryService.texts(List.of(
                alarm(null), alarm(""), alarm("  "), alarm("温度过高")));

        assertEquals(List.of(StatisticsQueryService.UNKNOWN, StatisticsQueryService.UNKNOWN,
                StatisticsQueryService.UNKNOWN, "温度过高"), keys);
        // 与 overview 里「先取键、再归并」是同一个组合
        assertEquals(List.of(new Count(StatisticsQueryService.UNKNOWN, 3), new Count("温度过高", 1)),
                StatisticsQueryService.tally(keys));
    }

    @Test
    void devicesCountTotalOnlineAndTypes() {
        DeviceStats stats = StatisticsQueryService.devicesOf(List.of(
                device("urn:xiot:device:dtu:acme:m1:v1", true),
                device("urn:xiot:device:dtu:acme:m2:v1", false),
                device("urn:xiot:device:meter:acme:m3:v1", true),
                // 不足 4 段：原样当类型（与前端 UrnUtils.extractTypeName 同口径）
                device("urn:broken", false)));

        assertEquals(4, stats.total());
        assertEquals(2, stats.online());
        // 同数的两个按名字升序：meter 在 urn:broken 之前
        assertEquals(List.of(new Count("dtu", 2), new Count("meter", 1), new Count("urn:broken", 1)),
                stats.byType());
    }

    @Test
    void devicesWithoutTypeFallIntoTheUnknownBucket() {
        DeviceStats stats = StatisticsQueryService.devicesOf(List.of(device(null, false)));

        assertEquals(1, stats.total());
        assertEquals(List.of(new Count(StatisticsQueryService.UNKNOWN, 1)), stats.byType());
    }

    @Test
    void servicesGroupByConfigIdAndKeepTheEmptyKey() {
        // 没配点表的服务落在空串键上（点表 id 是标识符不是显示名，前端据此显示「未定义」）
        ServiceStats stats = StatisticsQueryService.servicesOf(List.of(
                service("cfg-1"), service("cfg-1"), service("cfg-2"), service(null)));

        assertEquals(4, stats.total());
        assertEquals(List.of(new Count("cfg-1", 2), new Count("", 1), new Count("cfg-2", 1)),
                stats.byConfig());
        // 「分组的条数之和 == 总数」是构造出来的：两者出自同一次读取
        assertEquals(stats.total(),
                stats.byConfig().stream().mapToInt(StatisticsQueryService.Count::count).sum());
    }

    @Test
    void emptyInputsGiveEmptyResults() {
        DeviceStats devices = StatisticsQueryService.devicesOf(null);
        assertEquals(0, devices.total());
        assertEquals(0, devices.online());
        assertEquals(List.of(), devices.byType());

        assertEquals(0, StatisticsQueryService.servicesOf(null).total());
        assertEquals(List.of(), StatisticsQueryService.tally(null));
        // 整点分桶即便一条记录都没有也要把窗口铺满（曲线不能因为「没数据」就变成空图）
        assertEquals(2, StatisticsQueryService.hourly(null,
                new Date(T0), new Date(T0 + HOUR)).size());
    }

    // ===== 辅助 =====

    private static DeviceEntity device(String type, boolean online) {
        DeviceEntity device = new DeviceEntity();
        device.type = type;
        device.online = online;
        return device;
    }

    private static ModbusService service(String configId) {
        ModbusService service = new ModbusService();
        service.configId = configId;
        return service;
    }

    private static ModbusServiceAlarm alarm(String text) {
        ModbusServiceAlarm alarm = new ModbusServiceAlarm();
        alarm.text = text;
        alarm.at = new Date(T0);
        return alarm;
    }
}
