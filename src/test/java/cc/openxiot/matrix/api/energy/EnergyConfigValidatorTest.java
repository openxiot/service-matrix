package cc.openxiot.matrix.api.energy;

import cc.openxiot.matrix.db.energy.EnergyConfig;
import cc.openxiot.matrix.db.energy.EnergyMetric;
import cc.openxiot.matrix.db.energy.EnergyPoint;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergySourceKind;
import cc.openxiot.matrix.db.energy.EnergyType;
import cc.openxiot.matrix.db.energy.EnergyValueMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link EnergyConfigValidator}：能拦住会在报表上静默算错数的配置。
 */
class EnergyConfigValidatorTest {

    @Test
    void acceptsAWellFormedConsumptionConfig() {
        assertNull(EnergyConfigValidator.validate(config(EnergyRole.CONSUMPTION,
                point(EnergySourceKind.SERVICE, EnergyMetric.ENERGY))));
    }

    @Test
    void acceptsStorageStateAndChargePoints() {
        EnergyConfig config = config(EnergyRole.STORAGE,
                point(EnergySourceKind.REGISTER, EnergyMetric.CHARGE_ENERGY),
                point(EnergySourceKind.SERVICE, EnergyMetric.SOC),
                point(EnergySourceKind.SERVICE, EnergyMetric.POWER));
        assertNull(EnergyConfigValidator.validate(config));
    }

    @Test
    void rejectsConsumptionPointWithStorageMetric() {
        EnergyConfig config = config(EnergyRole.CONSUMPTION,
                point(EnergySourceKind.SERVICE, EnergyMetric.SOC));
        assertEquals("points[0]: consumption/production points must use the ENERGY metric",
                EnergyConfigValidator.validate(config));
    }

    @Test
    void rejectsStoragePointWithGenericEnergyMetric() {
        EnergyConfig config = config(EnergyRole.STORAGE,
                point(EnergySourceKind.SERVICE, EnergyMetric.ENERGY));
        assertEquals("points[0]: storage points must be charge/discharge energy or a state metric",
                EnergyConfigValidator.validate(config));
    }

    @Test
    void rejectsServicePointWithoutPropertyIds() {
        EnergyPoint point = point(EnergySourceKind.SERVICE, EnergyMetric.ENERGY);
        point.piid = null;
        assertEquals("points[0]: did, siid and piid are required for a service property",
                EnergyConfigValidator.validate(config(EnergyRole.CONSUMPTION, point)));
    }

    @Test
    void rejectsRegisterPointWithoutIndex() {
        EnergyPoint point = point(EnergySourceKind.REGISTER, EnergyMetric.ENERGY);
        point.registerIndex = null;
        assertEquals("points[0]: fdid and registerIndex are required for a register",
                EnergyConfigValidator.validate(config(EnergyRole.CONSUMPTION, point)));
    }

    @Test
    void rejectsEnergyPointWithoutMode() {
        EnergyPoint point = point(EnergySourceKind.REGISTER, EnergyMetric.ENERGY);
        point.mode = null;
        assertEquals("points[0]: mode is required for an energy point",
                EnergyConfigValidator.validate(config(EnergyRole.CONSUMPTION, point)));
    }

    @Test
    void rejectsDuplicatePoints() {
        EnergyConfig config = config(EnergyRole.CONSUMPTION,
                point(EnergySourceKind.REGISTER, EnergyMetric.ENERGY),
                point(EnergySourceKind.REGISTER, EnergyMetric.ENERGY));
        assertEquals("duplicate point: meter", EnergyConfigValidator.validate(config));
    }

    @Test
    void rejectsUnsupportedPeriod() {
        EnergyConfig config = config(EnergyRole.CONSUMPTION,
                point(EnergySourceKind.REGISTER, EnergyMetric.ENERGY));
        config.periodHours = 3;
        assertEquals("periodHours must be 1, 4, 12 or 24", EnergyConfigValidator.validate(config));
    }

    @Test
    void rejectsNegativePrice() {
        EnergyConfig config = config(EnergyRole.CONSUMPTION,
                point(EnergySourceKind.REGISTER, EnergyMetric.ENERGY));
        config.price = -0.1;
        assertEquals("price must not be negative", EnergyConfigValidator.validate(config));
    }

    @Test
    void rejectsEmptyPoints() {
        EnergyConfig config = config(EnergyRole.CONSUMPTION);
        assertEquals("points is required", EnergyConfigValidator.validate(config));
    }

    // ===== helpers =====

    private static EnergyConfig config(EnergyRole role, EnergyPoint... points) {
        EnergyConfig config = new EnergyConfig();
        config.name = "总表";
        config.role = role;
        config.type = EnergyType.ELECTRICITY;
        config.periodHours = 24;
        config.points = List.of(points);
        return config;
    }

    private static EnergyPoint point(EnergySourceKind source, EnergyMetric metric) {
        EnergyPoint point = new EnergyPoint();
        point.source = source;
        point.did = source == EnergySourceKind.SERVICE ? "did-1" : null;
        point.siid = source == EnergySourceKind.SERVICE ? 1 : null;
        point.piid = source == EnergySourceKind.SERVICE ? 2 : null;
        point.fdid = source == EnergySourceKind.REGISTER ? "fd-1" : null;
        point.registerIndex = source == EnergySourceKind.REGISTER ? 0 : null;
        point.metric = metric;
        point.mode = EnergyValueMode.ACTUAL;
        point.name = "meter";
        return point;
    }
}
