package cc.openxiot.matrix.api.energy;

import cc.openxiot.matrix.db.energy.EnergyMetric;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergyValueMode;
import cc.openxiot.matrix.db.energy.config.EnergyConfig;
import cc.openxiot.matrix.db.energy.config.EnergyPoint;
import cc.openxiot.matrix.db.energy.stat.EnergySample;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「点位读数」变成「这一轮该往小时桶里加多少」的纯函数集合。
 *
 * <p>没有注入、没有库、没有时钟：采样线程把读数、上一次基线、当前时刻传进来，
 * 这里只做算术。这样「累计口径算得对不对」可以单测，不必起 Mongo 也不必等一分钟。</p>
 *
 * <h3>三条口径</h3>
 *
 * <ul>
 *   <li><b>实际值</b>（{@link EnergyValueMode#ACTUAL}）：读数是累计量，本轮增量 = 本次读数 − 上次读数。
 *       读数变小视为清零/换表：从新读数起算（增量取本次读数本身）。</li>
 *   <li><b>增量值</b>（{@link EnergyValueMode#INCREMENT}）：读数本身就是增量，直接累加；
 *       但**同一个读数只算一次** —— 设备离线时影子会停在旧值，每轮都加就会凭空翻倍。
 *       寄存器有 {@code recordedAt}，由采样线程判断「这次读是不是新的」；设备属性没有时间戳，
 *       用「值是否变化」判断（见 {@code EnergySampler}）。</li>
 *   <li><b>功率</b>（{@link EnergyMetric#POWER}）：瞬时值，按距上次采样的时长积分成电量。
 *       正数算充电、负数算放电；时长夹在 0 与 3 个采样周期之间（进程停了一夜不该补出一整夜的假电量）。</li>
 * </ul>
 *
 * <h3>多台设备怎么合</h3>
 *
 * <p>一条配置可以配多个电池/电表。合并规则按指标性质定：电量、功率、容量相加；
 * SOC、SOH、温度取平均；循环次数相加。平均值在报表层按「和 ÷ 次数」算，这里只负责把
 * 同一轮的各点位数合成一个 {@link EnergySample}。</p>
 */
public final class EnergyAccumulator {

    private EnergyAccumulator() {
    }

    /**
     * 一个点位这一轮的结论：该加多少、新的基线是什么。
     */
    public record PointOutcome(
            EnergySample sample,
            Double lastRaw,
            Long lastAt,
            double delta
    ) {
        /**
         * 没读到值（或值不可用）：基线不动，这一轮什么都不加。
         */
        public static PointOutcome missing(Double lastRaw, Long lastAt) {
            return new PointOutcome(EnergySample.EMPTY, lastRaw, lastAt, 0);
        }
    }

    /**
     * 消费一个点位的一次读数。
     *
     * @param point          点位定义
     * @param raw            本次读数；null = 没读到
     * @param fresh          对 INCREMENT：这次读是不是一次**新**上报（寄存器按 recordedAt、设备属性按值变化）
     * @param now            采样时刻（epoch 毫秒）
     * @param lastRaw        上一次的原始值
     * @param lastAt         上一次读数时刻
     * @param integratePower 是否把功率积分成充/放电量（存储且没配显式充放电量点位时）
     * @param intervalMillis 采样周期（功率积分时长的上限按它的 3 倍）
     */
    public static PointOutcome consume(
            EnergyPoint point,
            Double raw,
            boolean fresh,
            long now,
            Double lastRaw,
            Long lastAt,
            boolean integratePower,
            long intervalMillis
    ) {
        if (point == null || raw == null || !Double.isFinite(raw)) {
            return PointOutcome.missing(lastRaw, lastAt);
        }
        long safeInterval = Math.max(intervalMillis, 1_000L);
        return switch (point.metric) {
            case ENERGY, CHARGE_ENERGY, DISCHARGE_ENERGY -> consumeEnergy(point, raw, fresh, now, lastRaw, lastAt);
            case POWER -> consumePower(raw, now, lastRaw, lastAt, integratePower, safeInterval);
            case SOC, CAPACITY, SOH, TEMPERATURE, CYCLES ->
                    new PointOutcome(stateSample(point.metric, raw), raw, now, 0);
        };
    }

    private static PointOutcome consumeEnergy(
            EnergyPoint point,
            double raw,
            boolean fresh,
            long now,
            Double lastRaw,
            Long lastAt
    ) {
        double delta;
        if (lastRaw == null) {
            // 第一条读数只建立基线：不知道它之前累积了多少，算进来会把历史一次计入当前桶
            delta = 0;
        } else if (point.mode == EnergyValueMode.ACTUAL) {
            delta = raw >= lastRaw
                    ? raw - lastRaw
                    // 变小 = 清零 / 换表：新读数就是清零之后累计到的量
                    : raw;
        } else {
            delta = fresh ? raw : 0;
        }
        delta = Math.max(delta, 0);
        EnergySample sample = switch (point.metric) {
            case ENERGY -> new EnergySample(delta, 0, 0, 0, 0, null, null, null, null, null, null);
            case CHARGE_ENERGY -> new EnergySample(0, delta, 0, 0, 0, null, null, null, null, null, null);
            default -> new EnergySample(0, 0, delta, 0, 0, null, null, null, null, null, null);
        };
        return new PointOutcome(sample, raw, now, delta);
    }

    private static PointOutcome consumePower(
            double raw,
            long now,
            Double lastRaw,
            Long lastAt,
            boolean integratePower,
            long intervalMillis
    ) {
        double charge = 0;
        double discharge = 0;
        if (integratePower && lastAt != null) {
            long dt = Math.clamp(now - lastAt, 0L, intervalMillis * 3);
            double energy = raw * dt / 3_600_000.0;
            if (energy >= 0) {
                charge = energy;
            } else {
                discharge = -energy;
            }
        }
        EnergySample sample = new EnergySample(0, charge, discharge, 0, 0,
                raw, null, null, null, null, null);
        return new PointOutcome(sample, raw, now, charge + discharge);
    }

    private static EnergySample stateSample(EnergyMetric metric, double value) {
        return switch (metric) {
            case SOC -> new EnergySample(0, 0, 0, 0, 0, null, value, null, null, null, null);
            case CAPACITY -> new EnergySample(0, 0, 0, 0, 0, null, null, value, null, null, null);
            case SOH -> new EnergySample(0, 0, 0, 0, 0, null, null, null, value, null, null);
            case TEMPERATURE -> new EnergySample(0, 0, 0, 0, 0, null, null, null, null, value, null);
            case CYCLES -> new EnergySample(0, 0, 0, 0, 0, null, null, null, null, null, value);
            default -> EnergySample.EMPTY;
        };
    }

    /**
     * 把一条配置下各点位的本轮结果合成一个采样。
     *
     * <p>合并规则见类注释。空清单返回 {@link EnergySample#EMPTY}，调用方据此跳过写库。</p>
     */
    public static EnergySample combine(List<EnergySample> samples) {
        if (samples == null || samples.isEmpty()) {
            return EnergySample.EMPTY;
        }
        double energy = 0;
        double charge = 0;
        double discharge = 0;
        double power = 0;
        boolean hasPower = false;
        double soc = 0;
        int socCount = 0;
        double capacity = 0;
        boolean hasCapacity = false;
        double soh = 0;
        int sohCount = 0;
        double temperature = 0;
        int temperatureCount = 0;
        double cycles = 0;
        boolean hasCycles = false;
        for (EnergySample sample : samples) {
            if (sample == null) {
                continue;
            }
            energy += sample.energy();
            charge += sample.charge();
            discharge += sample.discharge();
            if (sample.power() != null) {
                power += sample.power();
                hasPower = true;
            }
            if (sample.soc() != null) {
                soc += sample.soc();
                socCount++;
            }
            if (sample.capacity() != null) {
                capacity += sample.capacity();
                hasCapacity = true;
            }
            if (sample.soh() != null) {
                soh += sample.soh();
                sohCount++;
            }
            if (sample.temperature() != null) {
                temperature += sample.temperature();
                temperatureCount++;
            }
            if (sample.cycles() != null) {
                cycles += sample.cycles();
                hasCycles = true;
            }
        }
        return new EnergySample(
                energy,
                charge,
                discharge,
                0,
                0,
                hasPower ? power : null,
                socCount > 0 ? soc / socCount : null,
                hasCapacity ? capacity : null,
                sohCount > 0 ? soh / sohCount : null,
                temperatureCount > 0 ? temperature / temperatureCount : null,
                hasCycles ? cycles : null);
    }

    /**
     * 按配置的价格把电量折成成本/收益（采样当时的价格，之后改价不重算历史）。
     *
     * <ul>
     *   <li>消耗：成本 = 用量 × 单价；</li>
     *   <li>生产：收益 = 产量 × 单价；</li>
     *   <li>存储：成本 = 充电量 × 充电价，收益 = 放电量 × 放电价。</li>
     * </ul>
     *
     * <p>价格没配按 0 计，报表里就是「只有电量、没有钱」—— 这正是配置页不填价格时的预期。</p>
     */
    public static EnergySample price(EnergyConfig config, EnergySample sample) {
        if (config == null || sample == null || sample.isEmpty()) {
            return EnergySample.EMPTY;
        }
        double cost = 0;
        double revenue = 0;
        if (config.role == EnergyRole.STORAGE) {
            cost = sample.charge() * nonNegative(config.chargePrice);
            revenue = sample.discharge() * nonNegative(config.dischargePrice);
        } else if (config.role == EnergyRole.CONSUMPTION) {
            cost = sample.energy() * nonNegative(config.price);
        } else {
            revenue = sample.energy() * nonNegative(config.price);
        }
        return new EnergySample(sample.energy(), sample.charge(), sample.discharge(), cost, revenue,
                sample.power(), sample.soc(), sample.capacity(), sample.soh(),
                sample.temperature(), sample.cycles());
    }

    /**
     * 有些设备会把「没读到」编码成 NaN 或字符串；到这一层只认有限数。
     */
    public static Double number(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (value instanceof String text) {
            try {
                double d = Double.parseDouble(text.trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double nonNegative(Double value) {
        return value == null || !Double.isFinite(value) || value < 0 ? 0 : value;
    }

    /**
     * 空台账：便于调用方收集各点位结果。
     */
    public static List<EnergySample> newSamples() {
        return new ArrayList<>();
    }
}
