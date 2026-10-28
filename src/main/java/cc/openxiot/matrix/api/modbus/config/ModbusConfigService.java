package cc.openxiot.matrix.api.modbus.config;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigOwner;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistries;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ModbusConfigService {

    /** 功能码词表（十六进制大写字符串）。 */
    private static final Set<String> FCS = Set.of("01", "02", "03", "04", "05", "06", "0F", "10");
    private static final Set<String> REGISTER_DATA_TYPES = Set.of("int16", "uint16", "int32", "uint32", "int64", "uint64", "float32", "string");
    /** 整型（允许挂 enumMap 枚举映射）——浮点/string 上挂枚举无意义。 */
    private static final Set<String> INTEGER_DATA_TYPES = Set.of("int16", "uint16", "int32", "uint32", "int64", "uint64");
    private static final Set<String> BYTE_ORDERS = Set.of("ABCD", "DCBA", "BADC", "CDAB");
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
        // 命令校验要对账寄存器（被读的一段 / 被写的 writeItems 引用的寄存器）：
        // 以 index 为查询表，一次构建、命令间复用。
        Map<Integer, ModbusRegistry> byIndex = ModbusRegistries.byIndex(cfg.registries);
        return validateCommands(cfg.commands, byIndex);
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

    /**
     * 校验功能码动作列表（{@link ModbusCommand}）。公共字段：index/name/fc/start 如上；
     * 与区/类型相关的对账（读被覆盖的寄存器、写 writeItems 引用的寄存器）按 fc 分叉见
     * {@link #validateByFc}，需要 {@code byIndex}（点表寄存器按 index 的查询表）。
     */
    private String validateCommands(List<ModbusCommand> commands, Map<Integer, ModbusRegistry> byIndex) {
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
            String err = validateByFc(c, base, byIndex);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    private String validateByFc(ModbusCommand c, String base, Map<Integer, ModbusRegistry> byIndex) {
        return switch (c.fc) {
            case "01" -> validateReadBlock(c, base, "coil", byIndex, MAX_READ_BITS, true);
            case "02" -> validateReadBlock(c, base, "discrete", byIndex, MAX_READ_BITS, true);
            case "03" -> validateReadBlock(c, base, "holding", byIndex, MAX_READ_REGISTERS, false);
            case "04" -> validateReadBlock(c, base, "input", byIndex, MAX_READ_REGISTERS, false);
            case "05" -> validateWriteItems(c, base, byIndex, true, true);
            case "06" -> validateWriteItems(c, base, byIndex, false, true);
            case "0F" -> validateWriteItems(c, base, byIndex, true, false);
            case "10" -> validateWriteItems(c, base, byIndex, false, false);
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

    /**
     * 读命令（01/02/03/04）对账寄存器：{@code quantity} 数量校验后，被读的一段寄存器必须都存在于
     * {@code area} 区里。
     *
     * <p>01/02（位）：一个地址一个位，覆盖 {@code start..start+quantity-1} 每位都要有寄存器。
     * 03/04（寄存器）：{@code quantity} 是**值的个数**，起头（{@code start} 处）寄存器的数据类型决定跨度
     * （非 string：帧读 {@code quantity×跨度} 个寄存器、string：帧读 {@code quantity} 个寄存器，
     * 见 {@code ModbusFrameCodec#readQuantity}），整段 {@code start..start+count} 的每个地址都要有寄存器。
     * 位名/缩放/单位等元数据不再内联，由消费方按被引用寄存器的名字/scale/unit 推出。</p>
     */
    private String validateReadBlock(ModbusCommand c, String base, String area,
                                     Map<Integer, ModbusRegistry> byIndex, int max, boolean bits) {
        String err = validateQuantity(c, base, max);
        if (err != null) {
            return err;
        }
        int start = c.start;
        int quantity = c.quantity;
        if (bits) {
            for (int off = 0; off < quantity; off++) {
                ModbusRegistry reg = ModbusRegistries.at(byIndex, area, start + off);
                if (reg == null) {
                    return "invalid " + base + ": no register at address " + (start + off)
                            + " in area " + area + " (fc " + c.fc + " reads " + quantity + " bits from start " + start + ")";
                }
            }
            return null;
        }
        ModbusRegistry head = ModbusRegistries.at(byIndex, area, start);
        if (head == null) {
            return "invalid " + base + ": no register at address " + start + " in area " + area;
        }
        int frameCount = "string".equals(ModbusRegistries.format(head))
                ? quantity
                : quantity * ModbusRegistries.registerCount(head);
        // 数量×跨度 可能让实际读的寄存器数超上限（如 125 × float32 的 2 = 250）：这里按被读起头寄存器拦一次
        if (frameCount > max) {
            return "invalid " + base + ".quantity: " + quantity
                    + " (reads " + frameCount + " registers from start " + start + ", max " + max + ")";
        }
        for (int off = 0; off < frameCount; off++) {
            ModbusRegistry reg = ModbusRegistries.at(byIndex, area, start + off);
            if (reg == null) {
                return "invalid " + base + ": no register at address " + (start + off)
                        + " in area " + area + " (fc " + c.fc + " reads " + frameCount + " registers from start " + start + ")";
            }
        }
        return null;
    }

    /**
     * 写命令（05/06/0F/10）对账 {@code writeItems}：每条引用的寄存器必须存在于点表、落在该 fc
     * 的区（05/0F 线圈、06/10 保持寄存器）且类型匹配，取值类型与取值范围自洽；
     * 单条（05/06）恰一条、多条（0F/10）按地址升序从 {@code start} 起首尾相接（0F 一位、10 按寄存器个数）。
     *
     * <p>与 {@code FieldDeviceValidator.validateWriteRequest} 对 request.fields 的口径一致——
     * writeItems 与 request.fields 是同一件事（引用寄存器 + 原始值）在「点表命令」vs「服务请求」两条线上的投影。</p>
     */
    private String validateWriteItems(ModbusCommand c, String base, Map<Integer, ModbusRegistry> byIndex,
                                      boolean bits, boolean single) {
        List<ModbusCommand.ModbusCommandWriteItem> items = c.writeItems;
        if (items == null || items.isEmpty()) {
            return base + ".writeItems is required";
        }
        if (items.size() > (bits ? MAX_WRITE_COILS : MAX_WRITE_REGISTERS)) {
            return "invalid " + base + ".writeItems: too many (" + items.size() + ")";
        }
        if (single && items.size() != 1) {
            return "invalid " + base + ".writeItems: fc " + c.fc + " writes exactly one entry, got " + items.size();
        }
        String area = bits ? "coil" : "holding";
        Set<Integer> seenRegistry = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ModbusCommand.ModbusCommandWriteItem it = items.get(i);
            String ibase = base + ".writeItems[" + i + "]";
            if (it == null) {
                return ibase + " is null";
            }
            if (it.registryIndex == null) {
                return ibase + ".registryIndex is required";
            }
            if (it.registryIndex < 1) {
                return "invalid " + ibase + ".registryIndex: " + it.registryIndex
                        + " (index must be a natural number, 1-based)";
            }
            if (!seenRegistry.add(it.registryIndex)) {
                return "invalid " + ibase + ".registryIndex: " + it.registryIndex + " (duplicated)";
            }
            ModbusRegistry reg = byIndex == null ? null : byIndex.get(it.registryIndex);
            if (reg == null) {
                return "invalid " + ibase + ".registryIndex: " + it.registryIndex + " (not in registries)";
            }
            if (!area.equals(reg.area)) {
                return "invalid " + ibase + ".registryIndex: " + it.registryIndex
                        + " (fc " + c.fc + " writes " + area + ", registry is in area " + reg.area + ")";
            }
            String format = ModbusRegistries.format(reg);
            if (bits) {
                if (!(it.value instanceof Boolean)) {
                    return "invalid " + ibase + ".value: " + it.value + " (a bit is true or false)";
                }
            } else {
                // fc 06 只写一个寄存器：4 字节格式取用会被静默截掉一半
                if (single && ModbusRegistries.byteWidth(reg) != 2) {
                    return "invalid " + ibase + ".registryIndex: " + it.registryIndex
                            + " (fc 06 writes one register, not data type " + format + ")";
                }
                String valueErr = validateWriteValue(it.value, format, ibase + ".value");
                if (valueErr != null) {
                    return valueErr;
                }
            }
        }
        // 多条（0F/10）：帧只有「起始地址 + 个数 + 紧凑数据区」，没有空洞——位置由寄存器地址相对 start 推出，必须 0 起连续。
        if (!single) {
            int start = c.start;
            List<ModbusCommand.ModbusCommandWriteItem> ordered = new ArrayList<>(items);
            ordered.sort(Comparator.comparingInt(item -> {
                ModbusRegistry reg = byIndex.get(item.registryIndex);
                return reg == null || reg.address == null ? 0 : reg.address;
            }));
            int position = 0;
            for (ModbusCommand.ModbusCommandWriteItem item : ordered) {
                ModbusRegistry reg = byIndex.get(item.registryIndex);
                int offset = reg.address - start;
                if (offset != position) {
                    return "invalid " + base + ".writeItems: " + (bits ? "bit" : "register")
                            + " offset of registry " + item.registryIndex + " is " + offset
                            + " (must be " + position + ", write entries are packed by address without gaps)";
                }
                position += bits ? 1 : ModbusRegistries.registerCount(reg);
            }
        }
        return null;
    }

    /**
     * 一条写寄存器值的自洽性：必填、是有限数值；非 float32 的格式还必须是整数值且不越界。
     * 「整数值」按**值本身**判而不是按 Java 类型判（请求体过 Jackson 后 {@code 2} 可能是
     * {@code Integer} 或 {@code Double}）。
     */
    private String validateWriteValue(Object value, String format, String vbase) {
        if (value == null) {
            return vbase + " is required";
        }
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            return "invalid " + vbase + ": " + value + " (a register value is a finite number)";
        }
        if ("float32".equals(format)) {
            return null;
        }
        double d = number.doubleValue();
        if (d != Math.rint(d)) {
            return "invalid " + vbase + ": " + value + " (format " + format + " needs an integer value)";
        }
        long v = (long) d;
        boolean ok = switch (format) {
            case "int16" -> v >= Short.MIN_VALUE && v <= Short.MAX_VALUE;
            case "uint16" -> v >= 0 && v <= 0xFFFFL;
            case "int32" -> v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE;
            case "uint32" -> v >= 0 && v <= 0xFFFFFFFFL;
            default -> true;
        };
        return ok ? null : "invalid " + vbase + ": " + value + " (out of range for " + format + ")";
    }

    /** 线圈/离散输入区（{@link ModbusRegistry#area}）：数据恒为布尔，无需 dataType。 */
    private static boolean isCoilOrDiscreteArea(String area) {
        return "coil".equals(area) || "discrete".equals(area);
    }
}
