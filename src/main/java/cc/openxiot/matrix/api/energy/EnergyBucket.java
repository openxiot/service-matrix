package cc.openxiot.matrix.api.energy;

import cc.openxiot.matrix.db.energy.stat.EnergyStat;

/**
 * 一个展示桶（或整个窗口的合计）的聚合值。
 *
 * <p>「和 + 次数」原样保留，平均值在编码时才算（{@link #powerAvg()} / {@link #socAvg()} 等）：
 * 桶合并（多配置相加）必须先把和与次数相加再相除，不能把两个平均值再平均 —— 两台电池采样次数
 * 不同时，后者会偏。</p>
 *
 * <p>容量、循环次数是「每台设备的最新值」，跨配置合并时**相加**（总容量、总循环数）；
 * 同一配置跨小时合并时**取最后**（容量不会因为多加了几个小时而翻倍）。</p>
 */
public record EnergyBucket(
        long at,
        double energy,
        double charge,
        double discharge,
        double cost,
        double revenue,
        double powerSum,
        long powerCount,
        Double powerMax,
        Double powerMin,
        double socSum,
        long socCount,
        Double socMin,
        Double socMax,
        Double capacity,
        double sohSum,
        long sohCount,
        double temperatureSum,
        long temperatureCount,
        Double cycles,
        long samples
) {

    public static EnergyBucket empty(long at) {
        return new EnergyBucket(at, 0, 0, 0, 0, 0, 0, 0, null, null, 0, 0, null, null,
                null, 0, 0, 0, 0, null, 0);
    }

    /** 把一条小时统计并进来。调用方保证按 {@code bucketAt} 升序，后进来的「最后值」才是最后值。 */
    public EnergyBucket plus(EnergyStat stat) {
        if (stat == null) {
            return this;
        }
        return new EnergyBucket(
                at,
                energy + stat.energy,
                charge + stat.charge,
                discharge + stat.discharge,
                cost + stat.cost,
                revenue + stat.revenue,
                powerSum + stat.powerSum,
                powerCount + stat.powerCount,
                max(powerMax, stat.powerCount > 0 ? stat.powerMax : null),
                min(powerMin, stat.powerCount > 0 ? stat.powerMin : null),
                socSum + stat.socSum,
                socCount + stat.socCount,
                min(socMin, stat.socCount > 0 ? stat.socMin : null),
                max(socMax, stat.socCount > 0 ? stat.socMax : null),
                last(capacity, stat.capacity),
                sohSum + (stat.soh != null ? stat.soh : 0),
                sohCount + (stat.soh != null ? 1 : 0),
                temperatureSum + (stat.temperature != null ? stat.temperature : 0),
                temperatureCount + (stat.temperature != null ? 1 : 0),
                last(cycles, stat.cycles),
                samples + stat.samples);
    }

    /**
     * 两个桶合并（同一展示桶跨配置相加）。
     *
     * <p>容量与循环次数相加；SOH / 温度把和与次数相加；极值取极值；其余全加。</p>
     */
    public EnergyBucket merge(EnergyBucket other) {
        if (other == null) {
            return this;
        }
        return new EnergyBucket(
                at,
                energy + other.energy,
                charge + other.charge,
                discharge + other.discharge,
                cost + other.cost,
                revenue + other.revenue,
                powerSum + other.powerSum,
                powerCount + other.powerCount,
                max(powerMax, other.powerMax),
                min(powerMin, other.powerMin),
                socSum + other.socSum,
                socCount + other.socCount,
                min(socMin, other.socMin),
                max(socMax, other.socMax),
                sum(capacity, other.capacity),
                sohSum + other.sohSum,
                sohCount + other.sohCount,
                temperatureSum + other.temperatureSum,
                temperatureCount + other.temperatureCount,
                sum(cycles, other.cycles),
                samples + other.samples);
    }

    public Double powerAvg() {
        return powerCount == 0 ? null : powerSum / powerCount;
    }

    public Double socAvg() {
        return socCount == 0 ? null : socSum / socCount;
    }

    public Double sohAvg() {
        return sohCount == 0 ? null : sohSum / sohCount;
    }

    public Double temperatureAvg() {
        return temperatureCount == 0 ? null : temperatureSum / temperatureCount;
    }

    /**
     * 最大充电功率（正值）：桶内最大功率不是正数 = 这段时间没充过电，给 null 显示「—」。
     *
     * <p>表格要的是「最大充电功率」与「最大放电功率」两列；{@link #powerMax()} 只是带符号的最大值，
     * 不能直接当充电功率用（整段都在放电时它是负的）。</p>
     */
    public Double chargePowerMax() {
        return powerMax == null || powerMax <= 0 ? null : powerMax;
    }

    /** 最大放电功率（正值，取桶内最小功率的绝对值）；没放过电给 null。 */
    public Double dischargePowerMax() {
        return powerMin == null || powerMin >= 0 ? null : -powerMin;
    }

    /** 净存储变化：充电量 − 放电量（只有存储报表用得上）。 */
    public double netStorage() {
        return charge - discharge;
    }

    private static Double max(Double a, Double b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.max(a, b);
    }

    private static Double min(Double a, Double b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }

    private static Double sum(Double a, Double b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + b;
    }

    private static Double last(Double previous, Double current) {
        return current != null ? current : previous;
    }
}
