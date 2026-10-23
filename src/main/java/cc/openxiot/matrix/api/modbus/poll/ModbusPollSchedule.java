package cc.openxiot.matrix.api.modbus.poll;

import java.util.random.RandomGenerator;

/**
 * 轮询时刻的算法：**纯函数**，时间一律当参数传（不引 Clock），抖动由调用方给随机源。
 *
 * <p>三条口径在这里定死，调度循环与单测共用同一份实现：</p>
 *
 * <ul>
 *   <li><b>不漂移</b>：正常情况下一次采集的实际间隔就是 {@code interval}。
 *       基准取「上一次**开始**跑的时刻 + interval」，而不是「跑完的时刻 + interval」 ——
 *       否则一次慢应答（比如 2 秒）会让周期变成 {@code interval + 采集耗时}，长期慢慢跑偏。</li>
 *   <li><b>错过不补</b>：基准已经过期（进程停机、任务刚被对账加进来）时，**只排一次**、从当下起算一个完整周期，
 *       不按错过多少个周期连排。补跑对采集没有意义：那些时刻的值已经采不到了，
 *       连跑只会把 RS485 总线堵住。</li>
 *   <li><b>抖动</b>：同一份定义里的方法周期往往是同一个整数（10 秒、30 秒），不抖动就会整点齐发。
 *       抖动只加在重排结果上、幅度是周期的一个百分比（缺省 ±10%），且**不会把时刻拉到当下或过去**
 *       （否则时间轮会空转）。</li>
 * </ul>
 */
public final class ModbusPollSchedule {

    private ModbusPollSchedule() {
    }

    /**
     * 首次入表（或启动恢复时库里没有 {@code lastRunAt}）：从现在起算一个完整周期。
     *
     * <p>不立刻跑第一次：一是新配置的任务没必要马上采，二是对账会一次加进来一批任务，
     * 立刻跑等于一批帧同时上总线。抖动的第一个用武之地就在这。</p>
     */
    public static long insertRunAt(long now, int intervalSeconds, int jitterPercent, RandomGenerator random) {
        long interval = intervalMillis(intervalSeconds);
        return forward(now, jitter(now + interval, interval, jitterPercent, random));
    }

    /**
     * 启动恢复：按库里的 {@code lastRunAt} 接着排，而不是从头再来 ——
     * 否则一次滚动发布会把所有任务挤到同一秒爆发（这也是 {@code nextRunAt} 必须落库的原因）。
     *
     * <p>停机期间错过的返回 {@code now}（立刻到期，跑一次），见类注释「错过不补」。</p>
     *
     * @param lastRunAt 上一次**开始执行**的时刻（epoch 毫秒）；null = 从没跑过
     */
    public static long restoreRunAt(long now, Long lastRunAt, int intervalSeconds,
                                    int jitterPercent, RandomGenerator random) {
        if (lastRunAt == null) {
            return insertRunAt(now, intervalSeconds, jitterPercent, random);
        }
        long interval = intervalMillis(intervalSeconds);
        long anchor = lastRunAt + interval;
        if (anchor <= now) {
            return now;
        }
        return forward(now, jitter(anchor, interval, jitterPercent, random));
    }

    /**
     * 一次采集派发出去之后的重排。
     *
     * @param runStartAt 本次**开始执行**的时刻（epoch 毫秒），基准就是它 —— 见类注释「不漂移」
     */
    public static long nextRunAt(long now, long runStartAt, int intervalSeconds,
                                 int jitterPercent, RandomGenerator random) {
        long interval = intervalMillis(intervalSeconds);
        long anchor = runStartAt + interval;
        // 基准还在前面就按基准（周期不被采集耗时吃掉）；已经过了就从当下起算（不连跑）
        long base = anchor > now ? anchor : now + interval;
        return forward(now, jitter(base, interval, jitterPercent, random));
    }

    /** 到期判定：{@code nextRunAt == now} 算到期（闭区间），时间轮与查询接口共用这一份口径。 */
    public static boolean isDue(long nextRunAt, long now) {
        return nextRunAt <= now;
    }

    /**
     * 租约是否已过期（= 别人可以抢）：没被持有（{@code leaseUntil} 为空）或已过时刻。
     *
     * <p>这是 {@code ModbusPollingRepository.tryLease} 那个查询条件的可单测版本 ——
     * 库里那份还多一条「持有者是自己」，见 {@link #leaseClaimable}。</p>
     */
    public static boolean leaseExpired(Long leaseUntil, long now) {
        return leaseUntil == null || leaseUntil <= now;
    }

    /** 能不能抢：租约已过期，或**持有者就是自己**（上一次没放干净，重入同一任务时不该被自己挡住）。 */
    public static boolean leaseClaimable(Long leaseUntil, String leaseOwner, String me, long now) {
        return leaseExpired(leaseUntil, now) || (leaseOwner != null && leaseOwner.equals(me));
    }

    private static long intervalMillis(int intervalSeconds) {
        return intervalSeconds * 1000L;
    }

    /** 抖动幅度是周期的一个百分比；{@code r ∈ [-1, 1)} 由调用方给（测试传定值即可复现）。 */
    private static long jitter(long base, long interval, int jitterPercent, RandomGenerator random) {
        if (jitterPercent <= 0) {
            return base;
        }
        long span = Math.round(interval * jitterPercent / 100.0);
        if (span <= 0) {
            return base;
        }
        return base + Math.round(span * random.nextDouble(-1.0, 1.0));
    }

    /** 抖动不能把时刻拉到当下或过去：那会让时间轮在下一秒又取到它，变成空转。 */
    private static long forward(long now, long value) {
        return Math.max(now + 1, value);
    }
}
