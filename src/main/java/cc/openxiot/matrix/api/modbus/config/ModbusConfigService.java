package cc.openxiot.matrix.api.modbus.config;

import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigRepository;
import com.mongodb.client.model.Filters;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ModbusConfigService {

    /** 功能码词表（十六进制大写字符串）。 */
    private static final Set<String> FCS = Set.of("01", "02", "03", "04", "05", "06", "0F", "10");
    private static final Set<String> REGISTER_DATA_TYPES = Set.of("int16", "uint16", "int32", "uint32", "float32", "string");
    private static final Set<String> WRITE_DATA_TYPES = Set.of("int16", "uint16", "int32", "uint32", "float32");
    private static final Set<String> BYTE_ORDERS = Set.of("ABCD", "DCBA", "BADC", "CDAB");
    private static final Set<String> COIL_STATES = Set.of("on", "off");
    private static final Set<String> VISIBILITIES = Set.of("private", "public");
    /** 读功能码单次上限：位(01/02) 2000，寄存器(03/04) 125；写多线圈 0F 1968、写多寄存器 10 123。 */
    private static final int MAX_READ_BITS = 2000;
    private static final int MAX_READ_REGISTERS = 125;
    private static final int MAX_WRITE_COILS = 1968;
    private static final int MAX_WRITE_REGISTERS = 123;
    private static final long UINT32_MAX = 0xFFFFFFFFL;

    @Inject
    Logger logger;

    @Inject
    ModbusConfigRepository repo;

    @Inject
    ReactiveMongoClient mongo;

    @ConfigProperty(name = "modbus.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    void init(@Observes StartupEvent event) {
        if (!indexEnabled) {
            return;
        }
        migrateLegacyPoints();
        repo.ensureIndexes().await().indefinitely();
    }

    public Uni<ModbusConfig> create(ModbusConfig cfg) {
        String err = validate(cfg);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        cfg.id = UUID.randomUUID().toString();
        return repo.insert(cfg).replaceWith(cfg);
    }

    public Uni<ModbusConfig> update(String orgId, String id, ModbusConfig patch) {
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
                    // type 缺省/为 null 时保留既有值（兼容存量无 type 的旧配置，编辑其他字段不清空）。
                    if (patch.type != null) {
                        existing.type = patch.type;
                    }
                    // commands 缺省/为 null 时保留既有列表，避免旧客户端或“只改设备信息”的请求体
                    // 不带 commands 字段而把已配置的功能码整体清空（显式传 [] 才表示清空全部）。
                    if (patch.commands != null) {
                        existing.commands = patch.commands;
                    }
                    if (patch.updater != null) {
                        existing.updater = patch.updater;
                    }
                    return repo.replace(existing).replaceWith(existing);
                });
    }

    /**
     * 读单条（orgId 必填，调用方需已校验该组织成员身份）：
     * 返回「当前组织配置（任意可见度）」或「任意组织的公开配置」，与 /visible 列表口径一致；
     * 他组织非公开配置一律视为 not found。
     */
    public Uni<ModbusConfig> get(String orgId, String id) {
        String err = checkId(orgId, id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return repo.findVisibleById(id, orgId)
                .onItem().transformToUni(existing -> {
                    if (existing == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: " + id));
                    }
                    return Uni.createFrom().item(existing);
                });
    }

    /**
     * 读公开单条（无组织场景浏览用，仅需登录，无需成员身份）：任意组织的公开配置；
     * 非公开 → not found。
     */
    public Uni<ModbusConfig> getPublic(String id) {
        if (id == null || id.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("id is required"));
        }
        return repo.findPublicById(id)
                .onItem().transformToUni(existing -> {
                    if (existing == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: " + id));
                    }
                    return Uni.createFrom().item(existing);
                });
    }

    public Uni<List<ModbusConfig>> listByOrg(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("orgId is required"));
        }
        return repo.listByOrg(orgId);
    }

    public Uni<List<ModbusConfig>> listPublic() {
        return repo.listPublic();
    }

    public Uni<List<ModbusConfig>> listVisible(String orgId) {
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

    // ===== 校验 =====

    private String checkId(String orgId, String id) {
        if (orgId == null || orgId.isBlank()) {
            return "orgId is required";
        }
        if (id == null || id.isBlank()) {
            return "id is required";
        }
        return null;
    }

    private String validate(ModbusConfig cfg) {
        if (cfg == null) {
            return "body is required";
        }
        if (cfg.orgId == null || cfg.orgId.isBlank()) {
            return "orgId is required";
        }
        if (cfg.type == null || cfg.type.isBlank()) {
            return "type is required";
        }
        return validateContent(cfg);
    }

    private String validateContent(ModbusConfig cfg) {
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
        return validateCommands(cfg.commands);
    }

    private String validateCommands(List<ModbusCommand> commands) {
        if (commands == null) {
            return null;
        }
        for (int i = 0; i < commands.size(); i++) {
            ModbusCommand c = commands.get(i);
            String base = "commands[" + i + "]";
            if (c == null) {
                return base + " is null";
            }
            if (c.name == null || c.name.isBlank()) {
                return base + ".name is required";
            }
            if (c.fc == null || c.fc.isBlank()) {
                return base + ".fc is required";
            }
            if (!FCS.contains(c.fc)) {
                return "invalid " + base + ".fc: " + c.fc;
            }
            if (c.start == null) {
                return base + ".start is required";
            }
            if (c.start < 0) {
                return "invalid " + base + ".start: " + c.start;
            }
            String err = validateByFc(c, base);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    private String validateByFc(ModbusCommand c, String base) {
        return switch (c.fc) {
            case "01", "02" -> validateQuantity(c, base, MAX_READ_BITS);
            case "03", "04" -> {
                String err = validateQuantity(c, base, MAX_READ_REGISTERS);
                if (err != null) {
                    yield err;
                }
                if (c.dataType == null || c.dataType.isBlank()) {
                    yield base + ".dataType is required";
                }
                if (!REGISTER_DATA_TYPES.contains(c.dataType)) {
                    yield "invalid " + base + ".dataType: " + c.dataType;
                }
                if (c.byteOrder == null || c.byteOrder.isBlank()) {
                    yield base + ".byteOrder is required";
                }
                if (!BYTE_ORDERS.contains(c.byteOrder)) {
                    yield "invalid " + base + ".byteOrder: " + c.byteOrder;
                }
                Integer span = registerSpan(c.dataType);
                if (span != null && !span.equals(c.quantity)) {
                    yield "invalid " + base + ".quantity: " + c.quantity + " (dataType " + c.dataType + " needs " + span + ")";
                }
                yield null;
            }
            case "05" -> {
                if (c.coilState == null || c.coilState.isBlank()) {
                    yield base + ".coilState is required";
                }
                if (!COIL_STATES.contains(c.coilState)) {
                    yield "invalid " + base + ".coilState: " + c.coilState;
                }
                yield null;
            }
            case "06" -> {
                if (c.registerValue == null) {
                    yield base + ".registerValue is required";
                }
                if (c.registerValue < -32768 || c.registerValue > 65535) {
                    yield "invalid " + base + ".registerValue: " + c.registerValue;
                }
                yield null;
            }
            case "0F" -> validateCoils(c, base);
            case "10" -> validateRegisters(c, base);
            default -> null;
        };
    }

    private String validateQuantity(ModbusCommand c, String base, int max) {
        if (c.quantity == null) {
            return base + ".quantity is required";
        }
        if (c.quantity < 1 || c.quantity > max) {
            return "invalid " + base + ".quantity: " + c.quantity;
        }
        return null;
    }

    private String validateCoils(ModbusCommand c, String base) {
        List<ModbusCommand.Coil> coils = c.coils;
        if (coils == null || coils.isEmpty()) {
            return base + ".coils is required";
        }
        if (coils.size() > MAX_WRITE_COILS) {
            return "invalid " + base + ".coils: too many (" + coils.size() + ")";
        }
        Set<Integer> seen = new HashSet<>();
        for (int j = 0; j < coils.size(); j++) {
            ModbusCommand.Coil it = coils.get(j);
            String ibase = base + ".coils[" + j + "]";
            if (it == null) {
                return ibase + " is null";
            }
            if (it.offset == null || it.offset < 0 || it.offset >= coils.size()) {
                return "invalid " + ibase + ".offset: " + it.offset;
            }
            if (!seen.add(it.offset)) {
                return "invalid " + ibase + ".offset: duplicated " + it.offset;
            }
        }
        return null;
    }

    private String validateRegisters(ModbusCommand c, String base) {
        List<ModbusCommand.Register> regs = c.registers;
        if (regs == null || regs.isEmpty()) {
            return base + ".registers is required";
        }
        if (regs.size() > MAX_WRITE_REGISTERS) {
            return "invalid " + base + ".registers: too many (" + regs.size() + ")";
        }
        int expected = -1;
        for (int j = 0; j < regs.size(); j++) {
            ModbusCommand.Register r = regs.get(j);
            String ibase = base + ".registers[" + j + "]";
            if (r == null) {
                return ibase + " is null";
            }
            if (r.address == null) {
                return ibase + ".address is required";
            }
            if (r.address < 0) {
                return "invalid " + ibase + ".address: " + r.address;
            }
            if (expected >= 0 && r.address != expected) {
                return "invalid " + ibase + ".address: " + r.address + " (registers must be contiguous)";
            }
            if (r.dataType == null || r.dataType.isBlank()) {
                return ibase + ".dataType is required";
            }
            if (!WRITE_DATA_TYPES.contains(r.dataType)) {
                return "invalid " + ibase + ".dataType: " + r.dataType;
            }
            if (r.byteOrder == null || r.byteOrder.isBlank()) {
                return ibase + ".byteOrder is required";
            }
            if (!BYTE_ORDERS.contains(r.byteOrder)) {
                return "invalid " + ibase + ".byteOrder: " + r.byteOrder;
            }
            if (r.value == null) {
                return ibase + ".value is required";
            }
            if (!fitsType(r.dataType, r.value)) {
                return "invalid " + ibase + ".value: " + r.value;
            }
            Integer span = registerSpan(r.dataType);
            expected = r.address + (span == null ? 1 : span);
        }
        return null;
    }

    private boolean fitsType(String dataType, long value) {
        return switch (dataType) {
            case "int16" -> value >= -32768 && value <= 32767;
            case "uint16" -> value >= 0 && value <= 65535;
            case "int32" -> value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
            case "uint32" -> value >= 0 && value <= UINT32_MAX;
            case "float32" -> value >= Integer.MIN_VALUE && value <= UINT32_MAX;
            default -> false;
        };
    }

    /** 类型占用寄存器数：16 位→1、32 位/float→2、string→不固定（null）。 */
    private Integer registerSpan(String dataType) {
        if (dataType == null) {
            return null;
        }
        return switch (dataType) {
            case "int16", "uint16" -> 1;
            case "int32", "uint32", "float32" -> 2;
            default -> null;
        };
    }

    // ===== 存量迁移：旧版 points[]（点位中心）→ commands[]（功能码中心） =====

    /** 幂等迁移旧文档：把还带 points[] 且没有 commands[] 的配置转为新结构（读动作）。 */
    void migrateLegacyPoints() {
        if (mongo == null) {
            return;
        }
        try {
            ReactiveMongoCollection<Document> col = mongo.getDatabase("modbus")
                    .getCollection("device-configs", Document.class);
            List<Document> docs = col
                    .find(Filters.and(Filters.exists("points", true), Filters.exists("commands", false)))
                    .collect().asList().await().indefinitely();
            if (docs.isEmpty()) {
                return;
            }
            int done = 0;
            for (Document d : docs) {
                try {
                    Object raw = d.get("points");
                    if (!(raw instanceof List)) {
                        continue;
                    }
                    List<Document> commands = new ArrayList<>();
                    for (Object o : (List<?>) raw) {
                        if (o instanceof Document p) {
                            Document cmd = legacyPointToCommand(p);
                            if (cmd != null) {
                                commands.add(cmd);
                            }
                        }
                    }
                    d.put("commands", commands);
                    d.remove("points");
                    col.replaceOne(Filters.eq("_id", d.get("_id")), d).await().indefinitely();
                    done++;
                } catch (Exception ex) {
                    logger.warnf("skip legacy modbus config migration for _id=%s: %s", d.get("_id"), ex.getMessage());
                }
            }
            if (done > 0) {
                logger.infof("migrated %d legacy modbus device config(s): points[] -> commands[]", done);
            }
        } catch (Exception ex) {
            logger.warnf("modbus legacy migration skipped: %s", ex.getMessage());
        }
    }

    /** 旧点位 → 读功能码命令；无法识别的区域按保持寄存器处理，尽量不丢数据。 */
    private Document legacyPointToCommand(Document p) {
        String area = p.getString("area");
        String fc = switch (area == null ? "" : area) {
            case "coil" -> "01";
            case "discrete_input" -> "02";
            case "input" -> "04";
            default -> "03"; // holding / 未知
        };
        Integer start = p.getInteger("address");
        if (start == null) {
            Integer logical = p.getInteger("logicalAddress");
            if (logical != null) {
                start = logical - logicalBase(area);
            }
        }
        if (start == null || start < 0) {
            start = 0;
        }
        String dataType = p.getString("dataType");
        Document cmd = new Document();
        cmd.put("name", p.getString("name") == null ? "" : p.getString("name"));
        cmd.put("fc", fc);
        cmd.put("start", start);
        if ("01".equals(fc) || "02".equals(fc)) {
            cmd.put("quantity", 1);
        } else {
            if (dataType != null) {
                cmd.put("dataType", dataType);
            }
            cmd.put("byteOrder", "ABCD");
            Integer span = registerSpan(dataType);
            cmd.put("quantity", span == null ? 1 : span);
        }
        if (p.getDouble("scale") != null) {
            cmd.put("scale", p.getDouble("scale"));
        }
        if (p.getString("unit") != null) {
            cmd.put("unit", p.getString("unit"));
        }
        return cmd;
    }

    /** 逻辑地址（1 基工程号）的区段起始，用于老文档换算：40001→保持、30001→输入、10001→离散、00001→线圈。 */
    private int logicalBase(String area) {
        return switch (area == null ? "" : area) {
            case "coil" -> 1;
            case "discrete_input" -> 10001;
            case "input" -> 30001;
            default -> 40001;
        };
    }
}
