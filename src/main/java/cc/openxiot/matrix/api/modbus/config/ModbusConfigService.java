package cc.openxiot.matrix.api.modbus.config;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusDeviceType;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class ModbusConfigService {

    /** 功能码词表（十六进制大写字符串）。 */
    private static final Set<String> FCS = Set.of("01", "02", "03", "04", "05", "06", "0F", "10");
    private static final Set<String> REGISTER_DATA_TYPES = Set.of("int16", "uint16", "int32", "uint32", "float32", "string");
    private static final Set<String> WRITE_DATA_TYPES = Set.of("int16", "uint16", "int32", "uint32", "float32");
    private static final Set<String> BYTE_ORDERS = Set.of("ABCD", "DCBA", "BADC", "CDAB");
    private static final Set<String> COIL_STATES = Set.of("on", "off");
    private static final Set<String> VISIBILITIES = Set.of("private", "public");
    /** 生命周期门禁用（与 xiot-spec Lifecycle 存储串一致）。 */
    private static final String LIFECYCLE_DEVELOPMENT = "development";
    private static final String LIFECYCLE_PREVIEW = "preview";
    private static final String LIFECYCLE_RELEASED = "released";
    /** lifecycle 接口允许的目标值（development / preview / released；不校验流转，服务端直接覆盖）。 */
    private static final Set<String> LIFECYCLES = Set.of(LIFECYCLE_DEVELOPMENT, LIFECYCLE_PREVIEW, LIFECYCLE_RELEASED);
    /** 读功能码单次上限：位(01/02) 2000，寄存器(03/04) 125；写多线圈 0F 1968、写多寄存器 10 123。 */
    private static final int MAX_READ_BITS = 2000;
    private static final int MAX_READ_REGISTERS = 125;
    private static final int MAX_WRITE_COILS = 1968;
    private static final int MAX_WRITE_REGISTERS = 123;
    private static final long UINT32_MAX = 0xFFFFFFFFL;

    @Inject
    ModbusConfigRepository repo;

    @ConfigProperty(name = "modbus.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    /**
     * 启动只做建索引（{@code orgId} 唯一口径）。历史结构迁移已随存量数据一并清除：各环境均为全新库，
     * 旧 points[] / 扁平 slave / 字符串 type / 缺 index / 缺 lifecycle / 字符串主键的文档不再存在，
     * 无需启动期兜底改写。
     */
    void init(@Observes StartupEvent event) {
        if (!indexEnabled) {
            return;
        }
        repo.ensureIndexes().await().indefinitely();
    }

    public Uni<ModbusConfig> create(ModbusConfig cfg) {
        String err = validate(cfg);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        // id 不在此生成：主键由 Panache/Mongo 在落库时生成（见 ModbusConfig extends ReactivePanacheMongoEntity），
        // insert 后回写到 cfg.id，随返回体（ModbusConfigCodec 输出十六进制串）交给前端。
        // 新建一律从「开发」态开始；生命周期只经后续发布流转，禁止在创建请求里直接指定。
        cfg.lifecycle = LIFECYCLE_DEVELOPMENT;
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
                    // 生命周期门禁：仅「开发」态配置允许修改（仿 service-product，released/preview 拒绝）。
                    String lifecycleErr = lifecycleEditError(existing);
                    if (lifecycleErr != null) {
                        return Uni.createFrom().failure(new IllegalArgumentException(lifecycleErr));
                    }
                    String contentErr = validateContent(patch);
                    if (contentErr != null) {
                        return Uni.createFrom().failure(new IllegalArgumentException(contentErr));
                    }
                    // slave 必填（validateContent 已保证），整体替换；type 子对象缺省/为 null 时保留既有值
                    // （兼容存量无 type 的旧配置，编辑其他字段不清空——含已随 type 落库的规范/类型文案）。
                    ModbusDeviceType keepType =
                            (patch.slave.type == null && existing.slave != null) ? existing.slave.type : null;
                    existing.slave = patch.slave;
                    if (keepType != null) {
                        existing.slave.type = keepType;
                    }
                    existing.visibility = patch.visibility;
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
        return repo.findByIdAndOrg(id, orgId)
                .onItem().transformToUni(existing -> {
                    if (existing == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: " + id));
                    }
                    // 删除同为修改：仅「开发」态配置可删除（released/preview 拒绝）。
                    String lifecycleErr = lifecycleEditError(existing);
                    if (lifecycleErr != null) {
                        return Uni.createFrom().failure(new IllegalArgumentException(lifecycleErr));
                    }
                    return repo.deleteByIdAndOrg(id, orgId)
                            .onItem().transformToUni(count -> {
                                if (count == null || count == 0L) {
                                    return Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: " + id));
                                }
                                return Uni.createFrom().item(count);
                            });
                });
    }

    // ===== 生命周期 =====

    /**
     * 独立流转生命周期（预览/发布/下线专用接口）：普通 update 永远不触碰 lifecycle。
     * 仅校验目标值是合法生命周期（development/preview/released），不限制来源态——
     * development→preview（预览）、preview→released（发布）、released/preview→development（下线）都允许，
     * 与 service-product ProductBasicService.update(productId, lifecycle) 口径一致。
     */
    public Uni<ModbusConfig> changeLifecycle(String orgId, String id, String lifecycle, Person updater) {
        String err = checkId(orgId, id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        if (lifecycle == null || !LIFECYCLES.contains(lifecycle)) {
            return Uni.createFrom().failure(new IllegalArgumentException("invalid lifecycle: " + lifecycle));
        }
        return repo.findByIdAndOrg(id, orgId)
                .onItem().transformToUni(existing -> {
                    if (existing == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: " + id));
                    }
                    existing.lifecycle = lifecycle;
                    if (updater != null) {
                        existing.updater = updater;
                    }
                    return repo.replace(existing).replaceWith(existing);
                });
    }

    /**
     * 生命周期门禁：仅「开发」(development) 态可修改/删除；null 视作 development（兼容极早期未落库文档）。
     * released / preview 返回拒绝文案，其余（undefined/未知）放行，与 service-product 口径一致。
     */
    private String lifecycleEditError(ModbusConfig cfg) {
        String lc = cfg.lifecycle == null ? LIFECYCLE_DEVELOPMENT : cfg.lifecycle;
        if (LIFECYCLE_RELEASED.equals(lc)) {
            return "modbus device config is released, only development config can be modified: " + cfg.id;
        }
        if (LIFECYCLE_PREVIEW.equals(lc)) {
            return "modbus device config is preview, only development config can be modified: " + cfg.id;
        }
        return null;
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
        if (cfg.slave == null || cfg.slave.type == null || cfg.slave.type.type == null || cfg.slave.type.type.isBlank()) {
            return "type is required";
        }
        return validateContent(cfg);
    }

    private String validateContent(ModbusConfig cfg) {
        if (cfg.slave == null) {
            return "slave is required";
        }
        if (cfg.slave.manufacturer == null || cfg.slave.manufacturer.isBlank()) {
            return "manufacturer is required";
        }
        if (cfg.slave.model == null || cfg.slave.model.isBlank()) {
            return "model is required";
        }
        if (cfg.slave.slaveId == null) {
            return "slaveId is required";
        }
        if (cfg.slave.slaveId < 0 || cfg.slave.slaveId > 247) {
            return "invalid slaveId: " + cfg.slave.slaveId;
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
        Set<Integer> seenIndex = new HashSet<>();
        for (int i = 0; i < commands.size(); i++) {
            ModbusCommand c = commands.get(i);
            String base = "commands[" + i + "]";
            if (c == null) {
                return base + " is null";
            }
            if (c.index == null) {
                return base + ".index is required";
            }
            if (c.index < 1) {
                return "invalid " + base + ".index: " + c.index + " (index must be a natural number, 1-based)";
            }
            if (!seenIndex.add(c.index)) {
                return "invalid " + base + ".index: " + c.index + " (duplicated)";
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
}
