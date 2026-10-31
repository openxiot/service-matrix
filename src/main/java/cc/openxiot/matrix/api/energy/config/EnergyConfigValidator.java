package cc.openxiot.matrix.api.energy.config;

import cc.openxiot.matrix.db.energy.EnergyConfig;
import cc.openxiot.matrix.db.energy.EnergyMetric;
import cc.openxiot.matrix.db.energy.EnergyPoint;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergySourceKind;
import cc.openxiot.matrix.db.energy.EnergyValueMode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 能源配置的结构校验（纯函数）。
 *
 * <p>规则按「配错了会在报表上静默算出错数」来定：来源与点位字段必须成套、角色与指标必须匹配、
 * 时段必须是一天能整除的几档。资源层只负责把错误文案转成 {@code OxResponse.error}
 * —— 与 Modbus 点表的校验/资源分层同一条。</p>
 */
public final class EnergyConfigValidator {

    private static final Set<Integer> PERIODS = Set.of(1, 4, 12, 24);

    private EnergyConfigValidator() {
    }

    /** @return 错误文案；null = 通过 */
    public static String validate(EnergyConfig config) {
        if (config == null) {
            return "config is required";
        }
        if (blank(config.name)) {
            return "name is required";
        }
        if (config.role == null) {
            return "role is required";
        }
        if (config.type == null) {
            return "type is required";
        }
        if (config.periodHours == null || !PERIODS.contains(config.periodHours)) {
            return "periodHours must be 1, 4, 12 or 24";
        }
        if (negative(config.price) || negative(config.chargePrice) || negative(config.dischargePrice)) {
            return "price must not be negative";
        }
        List<EnergyPoint> points = config.points;
        if (points == null || points.isEmpty()) {
            return "points is required";
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < points.size(); i++) {
            EnergyPoint point = points.get(i);
            String error = validatePoint(config.role, point, i);
            if (error != null) {
                return error;
            }
            if (!keys.add(point.key())) {
                return "duplicate point: " + point.name;
            }
        }
        return null;
    }

    private static String validatePoint(EnergyRole role, EnergyPoint point, int index) {
        String prefix = "points[" + index + "]: ";
        if (point == null) {
            return prefix + "point is required";
        }
        if (point.source == null) {
            return prefix + "source is required";
        }
        if (point.source == EnergySourceKind.SERVICE) {
            if (blank(point.did) || point.siid == null || point.piid == null) {
                return prefix + "did, siid and piid are required for a service property";
            }
        } else if (blank(point.fdid) || point.registerIndex == null) {
            return prefix + "fdid and registerIndex are required for a register";
        }
        if (point.metric == null) {
            return prefix + "metric is required";
        }
        if (role == EnergyRole.STORAGE) {
            if (point.metric == EnergyMetric.ENERGY) {
                return prefix + "storage points must be charge/discharge energy or a state metric";
            }
        } else if (point.metric != EnergyMetric.ENERGY) {
            return prefix + "consumption/production points must use the ENERGY metric";
        }
        if (point.isEnergy() && point.mode == null) {
            return prefix + "mode is required for an energy point";
        }
        if (point.mode != null && point.mode != EnergyValueMode.ACTUAL
                && point.mode != EnergyValueMode.INCREMENT) {
            return prefix + "mode must be ACTUAL or INCREMENT";
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean negative(Double value) {
        return value != null && (!Double.isFinite(value) || value < 0);
    }
}
