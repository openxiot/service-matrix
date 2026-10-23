package cc.openxiot.matrix.api.modbus.config;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigOwner;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ModbusConfigService {

    /** 功能码词表（十六进制大写字符串）。 */
    private static final Set<String> FCS = Set.of("01", "02", "03", "04", "05", "06", "0F", "10");
    /** 读功能码：只有读操作有应答字段（{@link ModbusCommand#fields}）。 */
    private static final Set<String> READ_FCS = Set.of("01", "02", "03", "04");
    /** 读位功能码（01/02）：数据区按位打包，逐位命名走 {@link ModbusCommand#bitNames}。 */
    private static final Set<String> READ_BIT_FCS = Set.of("01", "02");
    private static final Set<String> REGISTER_DATA_TYPES = Set.of("int16", "uint16", "int32", "uint32", "int64", "uint64", "float32", "string");
    private static final Set<String> WRITE_DATA_TYPES = Set.of("int16", "uint16", "int32", "uint32", "int64", "uint64", "float32");
    /** 整型（允许挂 enumMap 枚举映射）——浮点/string 上挂枚举无意义。 */
    private static final Set<String> INTEGER_DATA_TYPES = Set.of("int16", "uint16", "int32", "uint32", "int64", "uint64");
    private static final Set<String> BYTE_ORDERS = Set.of("ABCD", "DCBA", "BADC", "CDAB");
    private static final Set<String> COIL_STATES = Set.of("on", "off");
    private static final Set<String> VISIBILITIES = Set.of("private", "public");
    private static final Set<String> OWNER_TYPES = Set.of(ModbusConfigOwner.TYPE_ORGANIZATION, ModbusConfigOwner.TYPE_USER);
    /** 寄存器区（{@link ModbusRegistry#area}）：holding 保持 / input 输入 / coil 线圈 / discrete 离散输入。 */
    private static final Set<String> AREAS = Set.of("holding", "input", "coil", "discrete");
    /** 读写方向（{@link ModbusRegistry#access}）：R 只读 / W 只写 / RW 读写。 */
    private static final Set<String> ACCESSES = Set.of("R", "RW", "W");
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

    @Inject
    OrgAccessService orgAccess;

    @ConfigProperty(name = "modbus.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    /**
     * 启动只做建索引（owner 口径）。历史结构迁移已随存量数据一并清除：各环境均为全新库，
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
        normalizeLegacy(cfg); // 旧 fieldNames/命令级 scale-unit → fields（幂等）
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

    public Uni<ModbusConfig> update(String userId, String id, ModbusConfig patch) {
        return authorizeOwnerById(userId, id)
                .onItem().transformToUni(existing -> {
                    // 生命周期门禁：仅「开发」态配置允许修改（仿 service-product，released/preview 拒绝）。
                    String lifecycleErr = lifecycleEditError(existing);
                    if (lifecycleErr != null) {
                        return Uni.createFrom().failure(new IllegalArgumentException(lifecycleErr));
                    }
                    String contentErr = validateContent(patch);
                    if (contentErr != null) {
                        return Uni.createFrom().failure(new IllegalArgumentException(contentErr));
                    }
                    // slave 必填（validateContent 已保证），整体替换
                    existing.slave = patch.slave;
                    existing.visibility = patch.visibility;
                    // commands 缺省/为 null 时保留既有列表，避免旧客户端或“只改设备信息”的请求体
                    // 不带 commands 字段而把已配置的功能码整体清空（显式传 [] 才表示清空全部）。
                    if (patch.commands != null) {
                        existing.commands = patch.commands;
                    }
                    // registries 同一口径：缺省保留既有（存量配置没有该字段，读了只改设备信息不会把它凭空加上）。
                    if (patch.registries != null) {
                        existing.registries = patch.registries;
                    }
                    if (patch.updater != null) {
                        existing.updater = patch.updater;
                    }
                    // 存量命令（fieldNames→fields）在落库前归一，使 DB 迁到新形状、不残留迁移字段
                    normalizeLegacy(existing);
                    return repo.replace(existing).replaceWith(existing);
                });
    }

    /**
     * 读单条：返回「当前账号可读」的配置 —— 任意公开配置，或归属当前账号/当前账号所在组织的
     * 非公开配置（与 /visible 列表口径一致），与 /visible 列表口径一致；
     * 他组织非公开配置一律视为 not found。
     */
    public Uni<ModbusConfig> get(String userId, String id) {
        return authorizeReadById(userId, id);
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

    /** 列出当前账号的私有配置（owner.type=user 且 owner.id=当前账号），不查组织。 */
    public Uni<List<ModbusConfig>> listByUser(String userId) {
        return repo.listByUser(userId);
    }

    public Uni<List<ModbusConfig>> listPublic() {
        return repo.listPublic();
    }

    /**
     * 可见点表：公开的 + 本组织的私有（owner.type=organization + owner.id=orgId）+ 本人的私有
     * （owner.type=user + owner.id=userId），口径同 {@link ModbusConfigRepository#listVisible}。
     * {@code orgId} 可空：没有组织上下文时只见「公开 + 本人」，让设备映射页在「未选组织 / 无组织」
     * 时也能读到公开点表与其个人私有点表。
     */
    public Uni<List<ModbusConfig>> listVisible(String userId, String orgId) {
        return repo.listVisible(userId, orgId);
    }

    /**
     * 组织内可见（精确口径，不含调用者个人私有）：公开的 + 该组织的私有（owner.type=organization +
     * owner.id=orgId）。供 getVisibleByOrg 用；成员校验在 Resource 端做完再进这里。
     */
    public Uni<List<ModbusConfig>> listVisibleByOrg(String orgId) {
        return repo.listVisibleByOrg(orgId);
    }

    public Uni<Long> delete(String userId, String id) {
        return authorizeOwnerById(userId, id)
                .onItem().transformToUni(existing -> {
                    // 删除同为修改：仅「开发」态配置可删除（released/preview 拒绝）。
                    String lifecycleErr = lifecycleEditError(existing);
                    if (lifecycleErr != null) {
                        return Uni.createFrom().failure(new IllegalArgumentException(lifecycleErr));
                    }
                    return repo.deleteById(id)
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
    public Uni<ModbusConfig> changeLifecycle(String userId, String id, String lifecycle, Person updater) {
        if (lifecycle == null || !LIFECYCLES.contains(lifecycle)) {
            return Uni.createFrom().failure(new IllegalArgumentException("invalid lifecycle: " + lifecycle));
        }
        return authorizeOwnerById(userId, id)
                .onItem().transformToUni(existing -> {
                    existing.lifecycle = lifecycle;
                    if (updater != null) {
                        existing.updater = updater;
                    }
                    return repo.replace(existing).replaceWith(existing);
                });
    }

    // ===== 归属鉴权 =====

    /**
     * 按 id 取文档并判「当前账号是归属者」：user 归属 → owner.id 必须等于当前账号；
     * organization 归属 → 当前账号必须是该组织成员（以 owner.id 为锚，不信请求头 X-Org-Id）。
     * 组织成员的校验经 {@link OrgAccessService#checkMember}，防「拿任意组织 id 操作他人组织点击表」。
     * 文档不存在 / owner 缺失或类型非法 / 无权限，一律回 not found。
     */
    private Uni<ModbusConfig> authorizeOwnerById(String userId, String id) {
        String err = checkId(id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return repo.findById(id)
                .onItem().transformToUni(doc -> authorizeOwner(userId, doc));
    }

    /**
     * 取文档并判「当前账号可读」：任意公开配置可读；非公开的须是归属者（见
     * {@link #authorizeOwner}）。供 GET /one 详情用，口径同 /visible 列表。
     */
    private Uni<ModbusConfig> authorizeReadById(String userId, String id) {
        String err = checkId(id);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return repo.findById(id)
                .onItem().transformToUni(doc -> {
                    if (doc == null) {
                        return Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: " + id));
                    }
                    if ("public".equals(doc.visibility)) {
                        return Uni.createFrom().item(doc);
                    }
                    return authorizeOwner(userId, doc);
                });
    }

    private Uni<ModbusConfig> authorizeOwner(String userId, ModbusConfig doc) {
        if (doc == null || doc.owner == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found"));
        }
        if (ModbusConfigOwner.TYPE_USER.equals(doc.owner.type)) {
            if (doc.owner.id == null || !doc.owner.id.equals(userId)) {
                return Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found"));
            }
            return Uni.createFrom().item(doc);
        }
        if (ModbusConfigOwner.TYPE_ORGANIZATION.equals(doc.owner.type)) {
            // 组织成员校验以 owner.id 为锚（不信请求头）；非成员 / 组织不存在，一律回 not found，
            // 不把 checkMember 的异常透出去（那会泄露「这条配置存在」）。
            return orgAccess.checkMember(doc.owner.id, userId)
                    .replaceWith(doc)
                    .onFailure().transform(t -> new IllegalArgumentException("modbus device config not found"));
        }
        return Uni.createFrom().failure(new IllegalArgumentException("invalid owner type"));
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

    private String checkId(String id) {
        if (id == null || id.isBlank()) {
            return "id is required";
        }
        return null;
    }

    private String validate(ModbusConfig cfg) {
        if (cfg == null) {
            return "body is required";
        }
        if (cfg.owner == null || cfg.owner.id == null || cfg.owner.id.isBlank()) {
            return "owner.id is required";
        }
        if (cfg.owner.type == null || !OWNER_TYPES.contains(cfg.owner.type)) {
            return "invalid owner.type: " + cfg.owner.type;
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
        String err = validateRegistries(cfg.registries);
        if (err != null) {
            return err;
        }
        return validateCommands(cfg.commands);
    }

    /** 把存量命令的 fieldNames/命令级 scale-unit 归一成 fields（幂等；新建即使全新也调用无副作用）。 */
    private void normalizeLegacy(ModbusConfig cfg) {
        if (cfg == null || cfg.commands == null) {
            return;
        }
        for (ModbusCommand c : cfg.commands) {
            ModbusCommand.upgradeLegacyFields(c);
        }
    }

    /**
     * 校验寄存器/点位表（{@link ModbusRegistry}）。**允许整体缺省**：为空时点表行为与纯 commands
     * 一致（双模式兼容、存量不迁移）。一旦给出，逐个校验：name/address/area/access 必填，
     * address 非负、area 在四区内、dataType 按区取词表、byteOrder 仅 32/64 位必填、scale/unit 可选；
     * 且同一（area, address）不得重复——一个物理位只出现一次。
     */
    private String validateRegistries(List<ModbusRegistry> registries) {
        if (registries == null) {
            return null;
        }
        // (area, address) 唯一：同区内同一地址重复会让隐式读对同一个物理位发两次读、两次命名
        Set<String> seen = new HashSet<>();
        // index 唯一：它是 ModbusPollFrom.index 的引用来源、也是前后端对这条寄存器的稳定身份，
        // 重复会让「由哪条寄存器展开」对不上号
        Set<Integer> seenIndex = new HashSet<>();
        for (int i = 0; i < registries.size(); i++) {
            ModbusRegistry r = registries.get(i);
            String base = "registries[" + i + "]";
            if (r == null) {
                return base + " is null";
            }
            // 序号（供 ModbusPollFrom.index 引用）：必填、1 起、同表唯一
            if (r.index == null) {
                return base + ".index is required";
            }
            if (r.index < 1) {
                return "invalid " + base + ".index: " + r.index + " (index must be a natural number, 1-based)";
            }
            if (!seenIndex.add(r.index)) {
                return base + ".index is duplicated: " + r.index;
            }
            if (r.address == null) {
                return base + ".address is required";
            }
            if (r.address < 0) {
                return "invalid " + base + ".address: " + r.address;
            }
            if (r.area == null || r.area.isBlank()) {
                return base + ".area is required";
            }
            if (!AREAS.contains(r.area)) {
                return "invalid " + base + ".area: " + r.area;
            }
            if (!seen.add(r.area + ":" + r.address)) {
                return "invalid " + base + ": duplicated (area " + r.area + ", address " + r.address + ")";
            }
            if (r.name == null || r.name.isBlank()) {
                return base + ".name is required";
            }
            if (r.access == null || r.access.isBlank()) {
                return base + ".access is required";
            }
            if (!ACCESSES.contains(r.access)) {
                return "invalid " + base + ".access: " + r.access;
            }
            // 跨多寄存器值（32 位跨 2 个、64 位跨 4 个）解码必须知道字节序；16 位/string/线圈离散无需字节序
            boolean multiRegister = "int32".equals(r.dataType) || "uint32".equals(r.dataType)
                    || "float32".equals(r.dataType) || "int64".equals(r.dataType) || "uint64".equals(r.dataType);
            if (multiRegister) {
                if (r.byteOrder == null || r.byteOrder.isBlank()) {
                    return base + ".byteOrder is required for dataType " + r.dataType;
                }
                if (!BYTE_ORDERS.contains(r.byteOrder)) {
                    return "invalid " + base + ".byteOrder: " + r.byteOrder;
                }
            } else if (!isCoilOrDiscreteArea(r.area)) {
                // 非线圈/离散区：16 位或 string，dataType 必填、须在寄存器词表内
                if (r.dataType == null || r.dataType.isBlank()) {
                    return base + ".dataType is required";
                }
                if (!REGISTER_DATA_TYPES.contains(r.dataType)) {
                    return "invalid " + base + ".dataType: " + r.dataType;
                }
            }
            // 枚举映射（enumMap，可选）：仅挂在整型数值类型上；位区/浮点/string 无意义，key/value 须非空
            if (r.enumMap != null && !r.enumMap.isEmpty()) {
                if (isCoilOrDiscreteArea(r.area)) {
                    return base + ".enumMap is not allowed on bit area";
                }
                if (r.dataType == null || !INTEGER_DATA_TYPES.contains(r.dataType)) {
                    return "invalid " + base + ".enumMap: dataType " + r.dataType + " is not an integer type";
                }
                for (Map.Entry<String, String> e : r.enumMap.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank()) {
                        return "invalid " + base + ".enumMap: keys and values must be non-empty";
                    }
                }
            }
        }
        return null;
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
            if (!READ_FCS.contains(c.fc) && c.fields != null && !c.fields.isEmpty()) {
                return base + ".fields is not allowed: fc " + c.fc + " has no response fields";
            }
            if (!READ_BIT_FCS.contains(c.fc) && c.bitNames != null && !c.bitNames.isEmpty()) {
                return base + ".bitNames is not allowed: fc " + c.fc + " has no bit response";
            }
        }
        return null;
    }

    private String validateByFc(ModbusCommand c, String base) {
        return switch (c.fc) {
            case "01", "02" -> {
                String err = validateQuantity(c, base, MAX_READ_BITS);
                if (err != null) {
                    yield err;
                }
                // 读位的应答数据区按位打包：字段恒为一个（整段位掩码），逐位命名走 bitNames
                if (c.fields != null && !c.fields.isEmpty()) {
                    yield base + ".fields is not allowed: fc " + c.fc + " names each bit via bitNames";
                }
                yield validateBitNames(c, base, c.quantity);
            }
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
                // 数量 = 值的个数：读的寄存器数 = 数量 × 类型跨度（string 的数量本身就是长度）
                Integer span = registerSpan(c.dataType);
                boolean single = span == null;
                int registers = single ? c.quantity : c.quantity * span;
                if (registers > MAX_READ_REGISTERS) {
                    yield "invalid " + base + ".quantity: " + c.quantity
                            + " (dataType " + c.dataType + " reads " + registers + " registers)";
                }
                yield validateFields(c, base, single ? 1 : c.quantity);
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

    /**
     * 校验应答字段（{@link ModbusCommand#fields}，03/04 读寄存器）：允许整体缺省（存量点表；
     * 生成服务时按功能名称兜底），但一旦给出则逐字段校验 —— 名称非空且不重复、
     * 个数与应答字段数一致（string 恒为 1，其余为数量）；字段自带缩放系数须有限且 > 0。
     *
     * @param expected 该功能码的应答字段个数（string 恒为 1，其余为数量）
     */
    private String validateFields(ModbusCommand c, String base, int expected) {
        List<ModbusCommand.Field> fields = c.fields;
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        if (fields.size() != expected) {
            return "invalid " + base + ".fields: " + fields.size()
                    + " (fc " + c.fc + " needs " + expected + ")";
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            ModbusCommand.Field f = fields.get(i);
            String fbase = base + ".fields[" + i + "]";
            if (f == null) {
                return fbase + " is null";
            }
            if (f.name == null || f.name.isBlank()) {
                return fbase + ".name is required";
            }
            if (!seen.add(f.name)) {
                return "invalid " + fbase + ".name: " + f.name + " duplicated";
            }
            if (f.scale != null && (!Double.isFinite(f.scale) || f.scale == 0 || f.scale < 0)) {
                return "invalid " + fbase + ".scale: " + f.scale + " (must be a positive finite number)";
            }
        }
        return null;
    }

    /**
     * 校验位名称（{@link ModbusCommand#bitNames}，01/02 读位）：允许整体缺省（不逐位命名，只按整段位掩码出
     * 一个字段），一旦给出则每条都要有 offset 与非空名称，且同一位不能命名两次 —— 名称会直接成为
     * invoke 返回值的 key，重复会互相覆盖。offset 是 0 基位偏移（从起始地址起，与 0F 的 Coil.offset 同口径）。
     */
    private String validateBitNames(ModbusCommand c, String base, int quantity) {
        List<ModbusCommand.BitName> bits = c.bitNames;
        if (bits == null || bits.isEmpty()) {
            return null;
        }
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < bits.size(); i++) {
            ModbusCommand.BitName bit = bits.get(i);
            String bbase = base + ".bitNames[" + i + "]";
            if (bit == null || bit.offset == null) {
                return bbase + ".offset is required";
            }
            if (bit.offset < 0 || bit.offset >= quantity) {
                return "invalid " + bbase + ".offset: " + bit.offset + " (quantity " + quantity + ")";
            }
            if (bit.name == null || bit.name.isBlank()) {
                return bbase + ".name is required";
            }
            if (!seen.add(bit.offset)) {
                return "invalid " + bbase + ".offset: " + bit.offset + " (duplicated)";
            }
        }
        return null;
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

    /** 类型占用寄存器数：16 位→1、32 位/float→2、64 位→4、string→不固定（null）。 */
    private Integer registerSpan(String dataType) {
        if (dataType == null) {
            return null;
        }
        return switch (dataType) {
            case "int16", "uint16" -> 1;
            case "int32", "uint32", "float32" -> 2;
            case "int64", "uint64" -> 4;
            default -> null;
        };
    }

    /** 线圈/离散输入区（{@link ModbusRegistry#area}）：数据恒为布尔，无需 dataType。 */
    private static boolean isCoilOrDiscreteArea(String area) {
        return "coil".equals(area) || "discrete".equals(area);
    }
}
