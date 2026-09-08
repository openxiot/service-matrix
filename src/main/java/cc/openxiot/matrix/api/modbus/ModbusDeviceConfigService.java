package cc.openxiot.matrix.api.modbus;

import cc.openxiot.matrix.db.modbus.ModbusDeviceConfig;
import cc.openxiot.matrix.db.modbus.ModbusDeviceConfigRepository;
import cc.openxiot.matrix.db.modbus.ModbusPoint;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ModbusDeviceConfigService {

    private static final Set<String> AREAS = Set.of("input", "holding", "coil", "discrete_input");
    private static final Set<String> RWS = Set.of("r", "w", "rw");
    private static final Set<String> VISIBILITIES = Set.of("private", "public");

    @Inject
    Logger logger;

    @Inject
    ModbusDeviceConfigRepository repo;

    @ConfigProperty(name = "modbus.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    void init(@Observes StartupEvent event) {
        if (!indexEnabled) {
            return;
        }
        repo.ensureIndexes().await().indefinitely();
    }

    public Uni<ModbusDeviceConfig> create(ModbusDeviceConfig cfg) {
        String err = validate(cfg);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        cfg.id = UUID.randomUUID().toString();
        return repo.insert(cfg).replaceWith(cfg);
    }

    public Uni<ModbusDeviceConfig> update(String orgId, String id, ModbusDeviceConfig patch) {
        String err = checkId(orgId, id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return repo.findByIdAndOrg(id, orgId)
                .onItem().transformToUni(existing -> {
                    if (existing == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: " + id));
                    }
                    String contentErr = validateContent(patch);
                    if (contentErr != null) {
                        return Uni.createFrom().failure(new IllegalArgumentException(contentErr));
                    }
                    existing.manufacturer = patch.manufacturer;
                    existing.model = patch.model;
                    existing.slaveId = patch.slaveId;
                    existing.visibility = patch.visibility;
                    existing.description = patch.description;
                    existing.points = patch.points;
                    if (patch.updater != null) {
                        existing.updater = patch.updater;
                    }
                    return repo.replace(existing).replaceWith(existing);
                });
    }

    public Uni<ModbusDeviceConfig> get(String orgId, String id) {
        String err = checkId(orgId, id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return repo.findByIdAndOrg(id, orgId)
                .onItem().transformToUni(existing -> {
                    if (existing == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: " + id));
                    }
                    return Uni.createFrom().item(existing);
                });
    }

    public Uni<List<ModbusDeviceConfig>> listByOrg(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("orgId is required"));
        }
        return repo.listByOrg(orgId);
    }

    public Uni<List<ModbusDeviceConfig>> listPublic() {
        return repo.listPublic();
    }

    public Uni<List<ModbusDeviceConfig>> listVisible(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("orgId is required"));
        }
        return repo.listVisible(orgId);
    }

    public Uni<Long> delete(String orgId, String id) {
        String err = checkId(orgId, id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return repo.deleteByIdAndOrg(id, orgId)
                .onItem().transformToUni(count -> {
                    if (count == null || count == 0L) {
                        return Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: " + id));
                    }
                    return Uni.createFrom().item(count);
                });
    }

    private String checkId(String orgId, String id) {
        if (orgId == null || orgId.isBlank()) {
            return "orgId is required";
        }
        if (id == null || id.isBlank()) {
            return "id is required";
        }
        return null;
    }

    private String validate(ModbusDeviceConfig cfg) {
        if (cfg == null) {
            return "body is required";
        }
        if (cfg.orgId == null || cfg.orgId.isBlank()) {
            return "orgId is required";
        }
        return validateContent(cfg);
    }

    private String validateContent(ModbusDeviceConfig cfg) {
        if (cfg.manufacturer == null || cfg.manufacturer.isBlank()) {
            return "manufacturer is required";
        }
        if (cfg.model == null || cfg.model.isBlank()) {
            return "model is required";
        }
        if (cfg.slaveId == null) {
            return "slaveId is required";
        }
        if (cfg.slaveId < 0 || cfg.slaveId > 247) {
            return "invalid slaveId: " + cfg.slaveId;
        }
        if (cfg.visibility == null || cfg.visibility.isBlank()) {
            return "visibility is required";
        }
        if (!VISIBILITIES.contains(cfg.visibility)) {
            return "invalid visibility: " + cfg.visibility;
        }
        return validatePoints(cfg.points);
    }

    private String validatePoints(List<ModbusPoint> points) {
        if (points == null) {
            return null;
        }
        for (int i = 0; i < points.size(); i++) {
            ModbusPoint p = points.get(i);
            if (p == null) {
                return "points[" + i + "] is null";
            }
            if (p.name == null || p.name.isBlank()) {
                return "points[" + i + "].name is required";
            }
            if (p.dataType == null || p.dataType.isBlank()) {
                return "points[" + i + "].dataType is required";
            }
            if (p.area != null && !AREAS.contains(p.area)) {
                return "invalid points[" + i + "].area: " + p.area;
            }
            if (p.rw != null && !RWS.contains(p.rw)) {
                return "invalid points[" + i + "].rw: " + p.rw;
            }
            if (p.address != null && p.address < 0) {
                return "invalid points[" + i + "].address: " + p.address;
            }
            if (p.logicalAddress != null && p.logicalAddress < 0) {
                return "invalid points[" + i + "].logicalAddress: " + p.logicalAddress;
            }
        }
        return null;
    }
}
