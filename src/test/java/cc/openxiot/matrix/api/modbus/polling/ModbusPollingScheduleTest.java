package cc.openxiot.matrix.api.modbus.polling;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusPollingSchedule}：轮询时刻的算法（纯函数，时间当参数传）。
 *
 * <p>钉住三件事：**不漂移**（周期不被采集耗时侵蚀）、**错过不补**（停机/missed 之后只排一次）、
 * **抖动不越界**（不把时刻拉到当下或过去，否则时间轮空转）。这三条错了都不会报错，
 * 只会让采样间隔悄悄变成另一个值。</p>
 */
class ModbusPollingScheduleTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final int INTERVAL = 10;

    /** 抖动为 0 时结果必须是确定的 —— 否则周期本身就不可推理了。 */
    @Test
    void insertRunAtWaitsAFullInterval() {
        assertEquals(NOW + 10_000, ModbusPollingSchedule.insertRunAt(NOW, INTERVAL, 0, fixed()));
    }

    /** 启动恢复：库里的 lastRunAt 还在前面就按它续，不从头再来（否则滚动发布会全挤在同一秒）。 */
    @Test
    void restoreKeepsTheOriginalAnchor() {
        long lastRunAt = NOW - 3_000;
        assertEquals(lastRunAt + 10_000, ModbusPollingSchedule.restoreRunAt(NOW, lastRunAt, INTERVAL, 0, fixed()));
    }

    @Test
    void restoreWithoutLastRunStartsLikeANewTask() {
        assertEquals(NOW + 10_000, ModbusPollingSchedule.restoreRunAt(NOW, null, INTERVAL, 0, fixed()));
    }

    /** 停机期间错过了很多个周期：立刻到期跑**一次**，不按错过的个数连排。 */
    @Test
    void restoreFiresOnceWhenTheAnchorIsLongPast() {
        long lastRunAt = NOW - 10 * 3_600_000L;   // 停机 10 小时
        long nextRunAt = ModbusPollingSchedule.restoreRunAt(NOW, lastRunAt, INTERVAL, 0, fixed());

        assertEquals(NOW, nextRunAt);
        assertTrue(ModbusPollingSchedule.isDue(nextRunAt, NOW));   // 只这一次：跑完由 nextRunAt 从当下重排
    }

    /** 不漂移：基准是「上一次开始跑的时刻 + interval」，不是「跑完的时刻 + interval」。 */
    @Test
    void nextRunAtFollowsTheRunStartNotTheFinishTime() {
        long runStartAt = NOW;
        long now = NOW + 2_500;                   // 这次采集花了 2.5 秒（慢应答）
        assertEquals(runStartAt + 10_000, ModbusPollingSchedule.nextRunAt(now, runStartAt, INTERVAL, 0, fixed()));
    }

    /** 连续重排 100 次的总时长 = 100 × interval（抖动 0 时精确成立）。 */
    @Test
    void repeatedReschedulingDoesNotDrift() {
        long runStartAt = NOW;
        for (int i = 0; i < 100; i++) {
            runStartAt = ModbusPollingSchedule.nextRunAt(runStartAt + 200, runStartAt, INTERVAL, 0, fixed());
        }
        assertEquals(NOW + 100 * 10_000, runStartAt);
    }

    /** 采集耗时超过一个周期时，下一次从**当下**起算一个完整周期 —— 不连跑（RS485 上不能连发两帧）。 */
    @Test
    void nextRunAtDoesNotChainRunsWhenTheInvokeOverruns() {
        long runStartAt = NOW;
        long now = NOW + 25_000;                  // 这次花了 25 秒，已经跨了 2 个周期
        assertEquals(now + 10_000, ModbusPollingSchedule.nextRunAt(now, runStartAt, INTERVAL, 0, fixed()));
    }

    @Test
    void jitterStaysWithinTheBand() {
        int percent = 10;
        RandomGenerator random = new Random(20260913L);
        long low = NOW + 10_000 - 1_000;          // interval 10s 的 ±10% = ±1s
        long high = NOW + 10_000 + 1_000;
        for (int i = 0; i < 200; i++) {
            long nextRunAt = ModbusPollingSchedule.nextRunAt(NOW, NOW, INTERVAL, percent, random);
            assertTrue(nextRunAt >= low && nextRunAt <= high, "out of band: " + nextRunAt);
        }
    }

    /** 抖动再大也不能落到当下或过去：那一秒的唤醒就会把它再取出来，变成原地空转。 */
    @Test
    void jitterNeverLandsInThePast() {
        RandomGenerator random = new Random(7L);
        // 基准只比 now 早 1ms，抖动幅度（100% of 10s）足以把它拉到过去
        long nextRunAt = ModbusPollingSchedule.restoreRunAt(NOW, NOW - 10_000 + 1, INTERVAL, 100, random);
        assertTrue(nextRunAt > NOW, "jitter pushed the run into the past: " + nextRunAt);

        long inserted = ModbusPollingSchedule.insertRunAt(NOW, INTERVAL, 100, random);
        assertTrue(inserted > NOW);
    }

    /** 到期是闭区间：正好等于当下就算到点（否则会白等一个唤醒周期）。 */
    @Test
    void isDueIncludesTheExactMoment() {
        assertTrue(ModbusPollingSchedule.isDue(NOW, NOW));
        assertTrue(ModbusPollingSchedule.isDue(NOW - 1, NOW));
        assertFalse(ModbusPollingSchedule.isDue(NOW + 1, NOW));
    }

    @Test
    void leaseExpiredCoversMissingExpiredAndHeld() {
        assertTrue(ModbusPollingSchedule.leaseExpired(null, NOW));          // 从没被跑过
        assertTrue(ModbusPollingSchedule.leaseExpired(NOW, NOW));           // 正好到期
        assertTrue(ModbusPollingSchedule.leaseExpired(NOW - 1, NOW));
        assertFalse(ModbusPollingSchedule.leaseExpired(NOW + 1, NOW));      // 别人还持有
    }

    /** 除了「过期」，自己还持有也算可抢 —— 上一次没放干净不该把自己挡住。 */
    @Test
    void leaseClaimableAlsoAllowsTheOwnerItself() {
        assertTrue(ModbusPollingSchedule.leaseClaimable(NOW + 60_000, "me", "me", NOW));
        assertFalse(ModbusPollingSchedule.leaseClaimable(NOW + 60_000, "other", "me", NOW));
        assertTrue(ModbusPollingSchedule.leaseClaimable(NOW - 1, "other", "me", NOW));
        assertTrue(ModbusPollingSchedule.leaseClaimable(null, null, "me", NOW));
    }

    private static RandomGenerator fixed() {
        return new Random(1L);
    }
}
