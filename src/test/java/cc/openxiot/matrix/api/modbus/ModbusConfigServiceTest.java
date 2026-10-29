package cc.openxiot.matrix.api.modbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cc.openxiot.matrix.api.modbus.config.ModbusConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigOwner;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;

@ExtendWith(MockitoExtension.class)
class ModbusConfigServiceTest {

    /**
     * 实体的落库主键（系统生成的 ObjectId）。仓储对外方法签名仍是字符串（内部转 ObjectId），
     * 所以下面桩里那些 "c1" / "nope" 只是 mock 的入参 key，与实体 id 类型无关。
     */
    private static final ObjectId CID = new ObjectId("6512f3a4b5c6d7e8f9a0b1c2");
    /** 测试用的当前账号（userId，authorize* 的第一个入参）。 */
    private static final String USER = "u1";

    @Mock
    ModbusConfigRepository repo;

    @Mock
    OrgAccessService orgAccess;

    @InjectMocks
    ModbusConfigService service;

    private ModbusConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new ModbusConfig();
        cfg.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        cfg.slave.manufacturer = "开利";
        cfg.slave.model = "19XRV";
        cfg.slave.slaveId = 1;
        cfg.visibility = "private";
        // 命令校验要对账寄存器（读一段 / 写 writeItems 引用）：给一套默认寄存器，多数测试直接沿用
        cfg.registries = defaultRegistries();
    }

    /** 合法的读保持寄存器动作（默认样例）：fc 03 自 start=10 连续读 2 个值。 */
    private ModbusCommand readHolding() {
        ModbusCommand c = new ModbusCommand();
        c.name = "CHW_TEMP";
        c.index = 1;
        c.fc = "03";
        c.start = 10;
        c.quantity = 2;
        return c;
    }

    /** 组装一条写命令（名称/序号默认填合法值）。 */
    private static ModbusCommand command(String fc, int start) {
        ModbusCommand c = new ModbusCommand();
        c.name = "动作";
        c.index = 1;
        c.fc = fc;
        c.start = start;
        return c;
    }

    private static ModbusCommand.ModbusCommandWriteItem write(int registryIndex, Object value) {
        ModbusCommand.ModbusCommandWriteItem item = new ModbusCommand.ModbusCommandWriteItem();
        item.registryIndex = registryIndex;
        item.value = value;
        return item;
    }

    /** 详细的寄存器样例：序号取用者显式给（同表内唯一由取用者保证）。 */
    private static ModbusRegistry reg(int index, String area, int address, String access,
                                      String dataType, String name) {
        ModbusRegistry r = new ModbusRegistry();
        r.name = name;
        r.index = index;
        r.address = address;
        r.area = area;
        r.access = access;
        r.dataType = dataType;
        if (dataType != null && List.of("int32", "uint32", "float32", "int64", "uint64").contains(dataType)) {
            r.byteOrder = "ABCD";
        }
        return r;
    }

    /** 默认寄存器集（序号固定，便于 writeItems 引用）。 */
    private static List<ModbusRegistry> defaultRegistries() {
        return List.of(
                reg(1, "holding", 10, "R", "uint16", "进水温度"),
                reg(2, "holding", 11, "R", "uint16", "进水温度2"),
                reg(3, "coil", 0, "R", null, "启停"),
                reg(4, "holding", 20, "W", "uint16", "设定温度"),
                reg(5, "coil", 30, "RW", null, "多线圈A"),
                reg(6, "coil", 31, "RW", null, "多线圈B"),
                reg(7, "holding", 40, "W", "int16", "寄存器A"),
                reg(8, "holding", 41, "W", "int16", "寄存器B"));
    }

    /** 寄存器序号（供 ModbusRegistry.index）：同表内必须唯一，用个计数器保证。 */
    private static int registrySeq;

    /** 寄存器样例（序号自增）：跨多寄存器类型（32/64 位）自动补 byteOrder，其余免填。 */
    private static ModbusRegistry registry(String area, int address, String access, String dataType, String name) {
        ModbusRegistry r = new ModbusRegistry();
        r.name = name;
        r.index = ++registrySeq;
        r.address = address;
        r.area = area;
        r.access = access;
        r.dataType = dataType;
        if (dataType != null && List.of("int32", "uint32", "float32", "int64", "uint64").contains(dataType)) {
            r.byteOrder = "ABCD";
        }
        return r;
    }

    // ===== 寄存器（registries）校验 =====

    /** 以寄存器为中心的点表：带 registries 也能建。 */
    @Test
    void createWithRegistriesSucceeds() {
        cfg.registries = List.of(
                registry("holding", 0, "R", "float32", "进水温度"),
                registry("holding", 2, "RW", "uint16", "开度"),
                registry("coil", 0, "RW", null, "启停"));
        insertOk();

        assertEquals(CID, service.create(cfg).await().indefinitely().id);
    }

    /** 同一 (area, address) 重复：一个物理位只出现一次。 */
    @Test
    void createWithDuplicatedRegistryAddressFails() {
        cfg.registries = List.of(
                registry("holding", 0, "R", "float32", "进水温度"),
                registry("holding", 0, "R", "float32", "重复点"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid registries[1]: duplicated (area holding, address 0)", ex.getMessage());
    }

    /** 区不在四区内。 */
    @Test
    void createWithInvalidRegistryAreaFails() {
        cfg.registries = List.of(registry("bogus", 0, "R", "uint16", "x"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid registries[0].area: bogus", ex.getMessage());
    }

    /** 32 位跨 2 寄存器：byteOrder 必填。 */
    @Test
    void createWithRegistryMultiRegisterMissingByteOrderFails() {
        ModbusRegistry r = registry("holding", 0, "R", "float32", "进水温度");
        r.byteOrder = null;
        cfg.registries = List.of(r);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("registries[0].byteOrder is required for dataType float32", ex.getMessage());
    }

    /** 读写方向缺失。 */
    @Test
    void createWithRegistryMissingAccessFails() {
        ModbusRegistry r = registry("holding", 0, "R", "uint16", "x");
        r.access = null;
        cfg.registries = List.of(r);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("registries[0].access is required", ex.getMessage());
    }

    /** string 寄存器：声明字节长（偶数 2..250）且只读，是合法点位。 */
    @Test
    void createWithStringRegistrySucceeds() {
        ModbusRegistry r = reg(1, "holding", 300, "R", "string", "表号");
        r.length = 12;
        cfg.registries = List.of(r);
        insertOk();

        assertEquals(CID, service.create(cfg).await().indefinitely().id);
    }

    /** string 的字节长必须是偶数：奇数会让寄存器数向上取整，用户声明与实际不一致。 */
    @Test
    void createWithOddStringLengthFails() {
        ModbusRegistry r = reg(1, "holding", 300, "R", "string", "表号");
        r.length = 13;
        cfg.registries = List.of(r);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid registries[0].length: 13 (an even byte count 2..250)", ex.getMessage());
    }

    /** string 字节长下限 2、上限 250（125 个寄存器 × 2）。 */
    @Test
    void createWithStringLengthOutOfRangeFails() {
        ModbusRegistry r = reg(1, "holding", 300, "R", "string", "表号");
        r.length = 252;
        cfg.registries = List.of(r);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid registries[0].length: 252 (an even byte count 2..250)", ex.getMessage());
    }

    /** 数值类型的宽度由 dataType 推出：挂 length 会让用户以为能改宽度，直接拒绝。 */
    @Test
    void createWithLengthOnNumericRegistryFails() {
        ModbusRegistry r = reg(1, "holding", 10, "R", "uint16", "电压");
        r.length = 4;
        cfg.registries = List.of(r);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid registries[0].length is only allowed for dataType string", ex.getMessage());
    }

    /** string 只读：写链路没有文本编码，W/RW 一律拒绝。 */
    @Test
    void createWithWritableStringRegistryFails() {
        ModbusRegistry r = reg(1, "holding", 300, "RW", "string", "表号");
        r.length = 12;
        cfg.registries = List.of(r);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid registries[0].access: RW (a string register is read-only)", ex.getMessage());
    }

    /** 桩：模拟仓储落库时由 Panache 生成主键（服务层不再自造 id）。 */
    private void insertOk() {
        when(repo.insert(any(ModbusConfig.class))).thenAnswer(inv -> {
            inv.<ModbusConfig>getArgument(0).id = CID;
            return Uni.createFrom().voidItem();
        });
    }

    /** 桩：当前账号 {@link #USER} 在 org1 是成员（authorizeOwner 的组织归属鉴权路径）。 */
    private void authOrgOk() {
        when(orgAccess.checkMember("org1", USER)).thenReturn(Uni.createFrom().voidItem());
    }

    @Test
    void createInsertsAndKeepsGeneratedId() {
        cfg.creator = Person.of("acc1", "小明");
        cfg.updater = Person.of("acc1", "小明");
        cfg.commands = List.of(readHolding());
        insertOk();

        ModbusConfig created = service.create(cfg).await().indefinitely();

        // id 由仓储（Panache）生成；服务层只负责落库并把生成的 id 随返回体带出
        assertEquals(CID, created.id);
        // 创建者/更新者由上层注入
        assertEquals("acc1", created.creator.id);
        assertNotNull(created.creator.timestamp);
        assertEquals("小明", created.updater.name);
        verify(repo).insert(eq(cfg));
    }

    @Test
    void createWithoutCommandsAllowed() {
        cfg.commands = null;
        insertOk();
        assertNotNull(service.create(cfg).await().indefinitely());
    }

    @Test
    void createWithBlankOwnerIdFails() {
        cfg.owner.id = " ";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("owner.id is required", ex.getMessage());
    }

    @Test
    void createWithInvalidOwnerTypeFails() {
        cfg.owner.type = "department";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid owner.type: department", ex.getMessage());
    }

    @Test
    void createWithUserOwnerAllowed() {
        cfg.owner = ModbusConfigOwner.ofUser(USER, "小明");
        cfg.commands = List.of(readHolding());
        insertOk();
        assertEquals(CID, service.create(cfg).await().indefinitely().id);
    }

    @Test
    void createWithBlankManufacturerFails() {
        cfg.slave.manufacturer = "";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("manufacturer is required", ex.getMessage());
    }

    @Test
    void createWithBlankModelFails() {
        cfg.slave.model = " ";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("model is required", ex.getMessage());
    }

    @Test
    void createWithBlankSlaveIdFails() {
        cfg.slave.slaveId = null;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("slaveId is required", ex.getMessage());
    }

    @Test
    void createWithInvalidSlaveIdFails() {
        cfg.slave.slaveId = 300;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid slaveId: 300", ex.getMessage());
    }

    @Test
    void createWithBlankVisibilityFails() {
        cfg.visibility = "";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("visibility is required", ex.getMessage());
    }

    @Test
    void createWithInvalidVisibilityFails() {
        cfg.visibility = "secret";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid visibility: secret", ex.getMessage());
    }

    @Test
    void createReadHoldingAllowed() {
        cfg.commands = List.of(readHolding());
        insertOk();
        ModbusConfig created = service.create(cfg).await().indefinitely();
        assertNotNull(created);
        // index 随行落库（1 起自然数，排序/虚拟生成 action iid 依赖它）
        assertEquals(1, created.commands.get(0).index);
    }

    @Test
    void createWriteSingleCoilAndSingleRegisterAllowed() {
        ModbusCommand coil = command("05", 0);
        coil.name = "PumpOn";
        coil.writeItems = List.of(write(3, true));      // 3 = coil @ addr0
        ModbusCommand reg = command("06", 20);
        reg.name = "SetTemp";
        reg.index = 2;
        reg.writeItems = List.of(write(4, 120));        // 4 = holding @ addr20 uint16
        cfg.commands = List.of(coil, reg);
        insertOk();
        assertNotNull(service.create(cfg).await().indefinitely());
    }

    @Test
    void createWriteMultipleCoilsAllowed() {
        ModbusCommand c = command("0F", 30);
        c.name = "MultiCoil";
        c.writeItems = List.of(write(5, true), write(6, false));   // coil @ addr30,31
        cfg.commands = List.of(c);
        insertOk();
        assertNotNull(service.create(cfg).await().indefinitely());
    }

    @Test
    void createWriteMultipleRegistersAllowed() {
        ModbusCommand c = command("10", 40);
        c.name = "MultiReg";
        c.writeItems = List.of(write(7, -5), write(8, 12000));     // holding @ addr40,41 int16
        cfg.commands = List.of(c);
        insertOk();
        assertNotNull(service.create(cfg).await().indefinitely());
    }

    @Test
    void createWithCommandNameMissingFails() {
        ModbusCommand c = readHolding();
        c.name = null;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].name is required", ex.getMessage());
    }

    @Test
    void createWithInvalidFcFails() {
        ModbusCommand c = readHolding();
        c.fc = "99";
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].fc: 99", ex.getMessage());
    }

    @Test
    void createWithMissingStartFails() {
        ModbusCommand c = readHolding();
        c.start = null;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].start is required", ex.getMessage());
    }

    /** 数量是「值的个数」：uint16 读 3 个值 = 3 个寄存器（逐地址都有寄存器），合法。 */
    @Test
    void createWithMultipleUint16ValuesSucceeds() {
        cfg.registries = List.of(
                reg(1, "holding", 10, "R", "uint16", "a"),
                reg(2, "holding", 11, "R", "uint16", "b"),
                reg(3, "holding", 12, "R", "uint16", "c"));
        ModbusCommand c = readHolding();
        c.quantity = 3;
        cfg.commands = List.of(c);
        insertOk();

        assertEquals(CID, service.create(cfg).await().indefinitely().id);
    }

    /** 数量 × 起头寄存器跨度 = 实际要读的寄存器数，超过 125 个即非法。 */
    @Test
    void createWithTooManyRegistersFails() {
        // 起头寄存器是 float32（跨度 2）：quantity 63 → 126 个寄存器 > 125
        cfg.registries = List.of(reg(1, "holding", 10, "R", "float32", "进水温度"));
        ModbusCommand c = readHolding();
        c.quantity = 63;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].quantity: 63 (reads 126 registers from start 10, max 125)",
                ex.getMessage());
    }

    /** 读命令被覆盖的寄存器必须都存在：数量 3 需 addr10/11/12，缺 12 → 拒绝。 */
    @Test
    void createWithReadCoveringMissingRegisterFails() {
        ModbusCommand c = readHolding();
        c.quantity = 3; // 默认寄存器只有 holding @ addr10,11
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0]: no register at address 12 in area holding "
                + "(fc 03 reads 3 registers from start 10)", ex.getMessage());
    }

    /** 读命令起头地址处没有寄存器也要拒绝。 */
    @Test
    void createWithReadStartMissingRegisterFails() {
        ModbusCommand c = command("03", 99);
        c.quantity = 1;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0]: no register at address 99 in area holding", ex.getMessage());
    }

    /** 单个 float32（跨 2 个寄存器）作为一个值读：只要求 start 处有寄存器，不要求 11 上也有一只。 */
    @Test
    void createWithSingleFloat32ReadSucceeds() {
        cfg.registries = List.of(reg(1, "holding", 10, "R", "float32", "进水温度"));
        ModbusCommand c = readHolding();
        c.quantity = 1;
        cfg.commands = List.of(c);
        insertOk();

        assertEquals(CID, service.create(cfg).await().indefinitely().id);
    }

    /** 数量 = 值的个数：float32 读 2 个值覆盖 10..13，第二个值起点（12）必须同宽。 */
    @Test
    void createWithMultipleFloat32ValuesSucceeds() {
        cfg.registries = List.of(
                reg(1, "holding", 10, "R", "float32", "a"),
                reg(2, "holding", 12, "R", "float32", "b"));
        ModbusCommand c = readHolding();
        c.quantity = 2;
        cfg.commands = List.of(c);
        insertOk();

        assertEquals(CID, service.create(cfg).await().indefinitely().id);
    }

    /** 混宽：起头 float32（跨度 2）却把 12 上的 uint16 当第二个值——拒绝，不能把 4 字节值劈成两段。 */
    @Test
    void createWithMixedWidthReadFails() {
        cfg.registries = List.of(
                reg(1, "holding", 10, "R", "float32", "a"),
                reg(2, "holding", 12, "R", "uint16", "b"));
        ModbusCommand c = readHolding();
        c.quantity = 2;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0]: register at address 12 spans 1 registers, expected 2 "
                + "(fc 03 reads 4 registers from start 10)", ex.getMessage());
    }

    /** 声明了长度的 string 整段是一个值：quantity 只能为 1（帧寄存器数由 length 推出）。 */
    @Test
    void createWithStringReadSucceeds() {
        ModbusRegistry r = reg(1, "holding", 300, "R", "string", "表号");
        r.length = 12;
        cfg.registries = List.of(r);
        ModbusCommand c = command("03", 300);
        c.quantity = 1;
        cfg.commands = List.of(c);
        insertOk();

        assertEquals(CID, service.create(cfg).await().indefinitely().id);
    }

    @Test
    void createWithDeclaredStringReadMultipleValuesFails() {
        ModbusRegistry r = reg(1, "holding", 300, "R", "string", "表号");
        r.length = 12;
        cfg.registries = List.of(r);
        ModbusCommand c = command("03", 300);
        c.quantity = 2;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].quantity: 2 "
                + "(a string register reads as one value, quantity must be 1)", ex.getMessage());
    }

    /**
     * 存量命令兼容：string 没声明 length 时 quantity 仍按寄存器数解释（跨度 1），
     * 升级后不会被新校验挡掉——多读的寄存器照样逐个要有定义。
     */
    @Test
    void createWithLegacyStringReadMultipleRegistersSucceeds() {
        cfg.registries = List.of(
                reg(1, "holding", 300, "R", "string", "表号"),
                reg(2, "holding", 301, "R", "string", "表号2"));
        ModbusCommand c = command("03", 300);
        c.quantity = 2;
        cfg.commands = List.of(c);
        insertOk();

        assertEquals(CID, service.create(cfg).await().indefinitely().id);
    }

    /** 写条目引用的寄存器必须存在于点表。 */
    @Test
    void createWithWriteMissingRegistryFails() {
        ModbusCommand c = command("06", 20);
        c.writeItems = List.of(write(999, 100));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].writeItems[0].registryIndex: 999 (not in registries)", ex.getMessage());
    }

    /** string 只读：fc 06 写它直接拒绝（放在 fc06 宽度检查之前，length=2 也漏不过去）。 */
    @Test
    void createWithStringSingleWriteFails() {
        ModbusRegistry r = reg(1, "holding", 300, "R", "string", "表号");
        r.length = 2;
        cfg.registries = List.of(r);
        ModbusCommand c = command("06", 300);
        c.writeItems = List.of(write(1, 100));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].writeItems[0].registryIndex: 1 (a string register is read-only)",
                ex.getMessage());
    }

    /** string 只读：fc 10 多寄存器写同样拒绝。 */
    @Test
    void createWithStringMultipleWriteFails() {
        ModbusRegistry r = reg(1, "holding", 300, "R", "string", "表号");
        r.length = 12;
        cfg.registries = List.of(r);
        ModbusCommand c = command("10", 300);
        c.writeItems = List.of(write(1, 100));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].writeItems[0].registryIndex: 1 (a string register is read-only)",
                ex.getMessage());
    }

    /** 写条目引用的寄存器必须落在该 fc 的区：06 写 holding，索引到线圈则拒绝。 */
    @Test
    void createWithWriteWrongAreaFails() {
        ModbusCommand c = command("06", 0);
        c.writeItems = List.of(write(3, 100));   // 3 = coil @ addr0
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].writeItems[0].registryIndex: 3 "
                + "(fc 06 writes holding, registry is in area coil)", ex.getMessage());
    }

    /** fc 06 只写一个寄存器：被引用寄存器是 4 字节（float32）会被静默截半，拒绝。 */
    @Test
    void createWithSingleRegisterWriteNot16BitFails() {
        cfg.registries = List.of(reg(1, "holding", 10, "W", "float32", "X"));
        ModbusCommand c = command("06", 10);
        c.writeItems = List.of(write(1, 100));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].writeItems[0].registryIndex: 1 "
                + "(fc 06 writes one register, not data type float32)", ex.getMessage());
    }

    /** 位写（05/0F）的取值必须是布尔。 */
    @Test
    void createWithBitWriteNonBooleanValueFails() {
        ModbusCommand c = command("0F", 30);
        c.writeItems = List.of(write(5, 100));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].writeItems[0].value: 100 (a bit is true or false)", ex.getMessage());
    }

    /** 单条写（05/06）必须有一条 writeItems。 */
    @Test
    void createWithMissingWriteItemsOnSingleWriteFails() {
        ModbusCommand c = command("05", 0);
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].writeItems is required", ex.getMessage());
    }

    /** 06 引用 uint16 但取值越界（>65535）。 */
    @Test
    void createWithOutOfRangeRegisterValueFails() {
        ModbusCommand c = command("06", 20);
        c.writeItems = List.of(write(4, 70000));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].writeItems[0].value: 70000 (out of range for uint16)", ex.getMessage());
    }

    /** 0F/10 必须有 writeItems。 */
    @Test
    void createWithEmptyWriteItemsFails() {
        ModbusCommand c = command("0F", 30);
        c.writeItems = List.of();
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].writeItems is required", ex.getMessage());
    }

    /** 同一条写命令不能重复引用同一个寄存器。 */
    @Test
    void createWithDuplicateRegistryIndexFails() {
        ModbusCommand c = command("0F", 30);
        c.writeItems = List.of(write(5, true), write(5, false));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].writeItems[1].registryIndex: 5 (duplicated)", ex.getMessage());
    }

    /** 10 写多寄存器：帧没有空洞，被写寄存器地址必须相对 start 首尾相接。 */
    @Test
    void createWithNonContiguousRegistersFails() {
        // addr10(①), addr20(④) 中间隔了 10 个地址，应接 addr11
        ModbusCommand c = command("10", 10);
        c.writeItems = List.of(write(1, 1), write(4, 2));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].writeItems: register offset of registry 4 is 10 "
                + "(must be 1, write entries are packed by address without gaps)", ex.getMessage());
    }

    /** 10 写寄存器：取值必须落进被引用寄存器的数据类型范围。 */
    @Test
    void createWithBadRegisterValueFails() {
        ModbusCommand c = command("10", 40);
        c.writeItems = List.of(write(7, 40000));   // 7 = holding @ addr40 int16：40000 越界
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].writeItems[0].value: 40000 (out of range for int16)", ex.getMessage());
    }

    @Test
    void createReadBitQuantityTooLargeFails() {
        ModbusCommand c = new ModbusCommand();
        c.name = "AlarmBit";
        c.index = 1;
        c.fc = "01";
        c.start = 0;
        c.quantity = 2001; // 01 单次最多 2000
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].quantity: 2001", ex.getMessage());
    }

    @Test
    void createWithCommandIndexMissingFails() {
        ModbusCommand c = readHolding();
        c.index = null;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].index is required", ex.getMessage());
    }

    @Test
    void createWithNonNaturalIndexFails() {
        ModbusCommand c = readHolding();
        c.index = 0;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].index: 0 (index must be a natural number, 1-based)", ex.getMessage());
    }

    @Test
    void createWithDuplicateIndexFails() {
        ModbusCommand c1 = readHolding();
        ModbusCommand c2 = readHolding();
        c2.name = "ANOTHER_READ"; // 两条 index 都 = 1
        cfg.commands = List.of(c1, c2);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[1].index: 1 (duplicated)", ex.getMessage());
    }

    @Test
    void updateNotFoundFails() {
        when(repo.findById("nope")).thenReturn(Uni.createFrom().nullItem());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(USER, "nope", cfg).await().indefinitely());
        assertEquals("modbus device config not found", ex.getMessage());
    }

    @Test
    void updateMergesEditableFields() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        existing.slave.manufacturer = "old";
        existing.slave.model = "old-model";
        existing.slave.slaveId = 5;
        existing.visibility = "public";
        existing.creator = Person.of("acc1", "小明");
        existing.updater = Person.of("acc1", "小明");
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        cfg.commands = List.of(readHolding());
        ModbusConfig updated = service.update(USER, "c1", cfg).await().indefinitely();

        assertEquals("new", updated.slave.manufacturer);
        assertEquals("19XRV", updated.slave.model);
        assertEquals(1, updated.slave.slaveId);
        assertEquals("private", updated.visibility);
        assertEquals(CID, updated.id);
        // 更新只替换 updater，creator 保持不变
        assertEquals("acc1", updated.creator.id);
        assertEquals("acc2", updated.updater.id);
        assertNotNull(updated.updater.timestamp);
        assertEquals(1, updated.commands.size());
        verify(repo).replace(eq(existing));
    }

    @Test
    void updateWithCommandsAbsentKeepsExistingCommands() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.visibility = "private";
        existing.commands = List.of(readHolding());
        existing.creator = Person.of("acc1", "小明");
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        // 只改设备信息：patch.commands 为 null（请求体未带 commands 字段）
        cfg.slave.manufacturer = "new-m";
        cfg.updater = Person.of("acc2", "小红");
        ModbusConfig updated = service.update(USER, "c1", cfg).await().indefinitely();

        assertEquals("new-m", updated.slave.manufacturer);
        // 既有功能码不被整体清空
        assertEquals(1, updated.commands.size());
        assertEquals("CHW_TEMP", updated.commands.get(0).name);
    }

    @Test
    void updateWithEmptyCommandsClearsAll() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.visibility = "private";
        existing.commands = List.of(readHolding());
        existing.creator = Person.of("acc1", "小明");
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        // 显式传空数组才算清空全部功能码
        cfg.updater = Person.of("acc2", "小红");
        cfg.commands = List.of();
        ModbusConfig updated = service.update(USER, "c1", cfg).await().indefinitely();

        assertEquals(0, updated.commands.size());
    }

    @Test
    void updateWithBlankModelFails() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));

        cfg.slave.model = "";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(USER, "c1", cfg).await().indefinitely());
        assertEquals("model is required", ex.getMessage());
    }

    @Test
    void updateCrossOrgOwnerForbiddenAsNotFound() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org2", "第二个组织"); // 他组织
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.visibility = "private";
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        // 非本组织成员：ORG2 成员校验失败 → 一律回 not found（不透出「Not a member」）
        when(orgAccess.checkMember("org2", USER))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the organization")));

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(USER, "c1", cfg).await().indefinitely());
        assertEquals("modbus device config not found", ex.getMessage());
    }

    @Test
    void updateUserOwnerPassesWithoutOrgCheck() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofUser(USER, "小明");
        existing.slave.manufacturer = "old";
        existing.slave.model = "mm";
        existing.visibility = "private";
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        cfg.commands = List.of(readHolding());
        ModbusConfig updated = service.update(USER, "c1", cfg).await().indefinitely();
        assertEquals("new", updated.slave.manufacturer);
        // user 归属只认本人，不查组织成员（无组织个人访问）
        verify(orgAccess, org.mockito.Mockito.times(0)).checkMember(eq(existing.owner.id), eq(USER));
    }

    @Test
    void getNotFoundFails() {
        when(repo.findById("ghost")).thenReturn(Uni.createFrom().nullItem());
        assertThrows(IllegalArgumentException.class,
                () -> service.get(USER, "ghost").await().indefinitely());
    }

    @Test
    void getBlankIdFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.get(USER, " ").await().indefinitely());
        assertEquals("id is required", ex.getMessage());
    }

    @Test
    void getDelegatesToVisibleLookup() {
        cfg.id = CID;
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(cfg));
        ModbusConfig found = service.get(USER, "c1").await().indefinitely();
        assertEquals(CID, found.id);
    }

    @Test
    void getCrossOrgPublicReadable() {
        ModbusConfig pub = new ModbusConfig();
        pub.id = CID;
        pub.owner = ModbusConfigOwner.ofOrg("org2", "第二个组织"); // 他组织
        pub.visibility = "public";
        when(repo.findById("c2")).thenReturn(Uni.createFrom().item(pub));
        ModbusConfig found = service.get(USER, "c2").await().indefinitely();
        assertEquals("public", found.visibility);
        assertEquals("org2", found.owner.id);
    }

    @Test
    void getCrossOrgPrivateNotReadable() {
        ModbusConfig priv = new ModbusConfig();
        priv.id = CID;
        priv.owner = ModbusConfigOwner.ofOrg("org2", "第二个组织"); // 他组织私有
        priv.visibility = "private";
        when(repo.findById("c2")).thenReturn(Uni.createFrom().item(priv));
        when(orgAccess.checkMember("org2", USER))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the organization")));
        assertThrows(IllegalArgumentException.class,
                () -> service.get(USER, "c2").await().indefinitely());
    }

    @Test
    void getPublicReadable() {
        ModbusConfig pub = new ModbusConfig();
        pub.id = CID;
        pub.owner = ModbusConfigOwner.ofOrg("org2", "第二个组织");
        pub.visibility = "public";
        when(repo.findPublicById("c2")).thenReturn(Uni.createFrom().item(pub));
        ModbusConfig found = service.getPublic("c2").await().indefinitely();
        assertEquals(CID, found.id);
    }

    @Test
    void getPublicNotFoundFails() {
        when(repo.findPublicById("ghost")).thenReturn(Uni.createFrom().nullItem());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getPublic("ghost").await().indefinitely());
        assertEquals("modbus device config not found: ghost", ex.getMessage());
    }

    @Test
    void getPublicBlankIdFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getPublic(" ").await().indefinitely());
        assertEquals("id is required", ex.getMessage());
    }

    @Test
    void listByOrgDelegates() {
        when(repo.listByOrg("org1")).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listByOrg("org1").await().indefinitely().size());
    }

    @Test
    void listByOrgBlankFails() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listByOrg(" ").await().indefinitely());
    }

    @Test
    void listPublicDelegates() {
        when(repo.listPublic()).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listPublic().await().indefinitely().size());
    }

    @Test
    void listVisibleDelegates() {
        when(repo.listVisible(USER, "org1")).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listVisible(USER, "org1").await().indefinitely().size());
    }

    @Test
    void listVisibleWithNullOrgDelegates() {
        // 没有组织上下文：透传给仓储（仓储内部退化为「公开 + 本人」），服务层不拦截
        when(repo.listVisible(USER, " ")).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listVisible(USER, " ").await().indefinitely().size());
        verify(repo).listVisible(USER, " ");
    }

    @Test
    void listByUserDelegates() {
        when(repo.listByUser(USER)).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listByUser(USER).await().indefinitely().size());
        verify(repo).listByUser(USER);
    }

    @Test
    void listVisibleByOrgDelegates() {
        when(repo.listVisibleByOrg("org1")).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listVisibleByOrg("org1").await().indefinitely().size());
        verify(repo).listVisibleByOrg("org1");
    }

    @Test
    void deleteZeroFailsAsNotFound() {
        // 鉴权（按 owner）先按 id 读原档：开发态才放行到删除
        ModbusConfig existing = devConfig();
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.deleteById("c1")).thenReturn(Uni.createFrom().item(0L));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.delete(USER, "c1").await().indefinitely());
        assertEquals("modbus device config not found: c1", ex.getMessage());
    }

    @Test
    void deleteDelegates() {
        ModbusConfig existing = devConfig();
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.deleteById("c1")).thenReturn(Uni.createFrom().item(1L));
        assertEquals(1L, service.delete(USER, "c1").await().indefinitely());
    }

    // ===== 生命周期：仅开发态可修改/删除 =====

    /** 造一条已落库的配置骨架（生命周期可另指定），用于 update/delete 门禁测试。 */
    private ModbusConfig devConfig() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        existing.lifecycle = "development";
        existing.visibility = "private";
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.creator = Person.of("acc1", "小明");
        return existing;
    }

    @Test
    void createDefaultsLifecycleToDevelopment() {
        cfg.commands = List.of(readHolding());
        insertOk();
        ModbusConfig created = service.create(cfg).await().indefinitely();
        assertEquals("development", created.lifecycle);
    }

    @Test
    void createForcesLifecycleToDevelopmentRegardlessOfRequest() {
        cfg.lifecycle = "released"; // 客户端请求里带的生命周期被忽略，一律回到开发态
        insertOk();
        ModbusConfig created = service.create(cfg).await().indefinitely();
        assertEquals("development", created.lifecycle);
    }

    @Test
    void updateDevelopmentConfigAllowed() {
        ModbusConfig existing = devConfig();
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        cfg.commands = List.of(readHolding());
        ModbusConfig updated = service.update(USER, "c1", cfg).await().indefinitely();
        assertEquals("new", updated.slave.manufacturer);
        // 生命周期不随普通更新流转，仍停留在开发态
        assertEquals("development", updated.lifecycle);
    }

    @Test
    void updateReleasedConfigFails() {
        ModbusConfig existing = devConfig();
        existing.lifecycle = "released";
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(USER, "c1", cfg).await().indefinitely());
        assertEquals("modbus device config is released, only development config can be modified: " + CID.toHexString(),
                ex.getMessage());
    }

    @Test
    void updatePreviewConfigFails() {
        ModbusConfig existing = devConfig();
        existing.lifecycle = "preview";
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(USER, "c1", cfg).await().indefinitely());
        assertEquals("modbus device config is preview, only development config can be modified: " + CID.toHexString(),
                ex.getMessage());
    }

    @Test
    void deleteReleasedConfigFails() {
        ModbusConfig existing = devConfig();
        existing.lifecycle = "released";
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.delete(USER, "c1").await().indefinitely());
        assertEquals("modbus device config is released, only development config can be modified: " + CID.toHexString(),
                ex.getMessage());
    }

    @Test
    void changeLifecycleInvalidValueFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.changeLifecycle(USER, "c1", "archive", null).await().indefinitely());
        assertEquals("invalid lifecycle: archive", ex.getMessage());
    }

    @Test
    void changeLifecycleNullValueFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.changeLifecycle(USER, "c1", null, null).await().indefinitely());
        assertEquals("invalid lifecycle: null", ex.getMessage());
    }

    @Test
    void changeLifecycleNotFoundFails() {
        when(repo.findById("ghost")).thenReturn(Uni.createFrom().nullItem());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.changeLifecycle(USER, "ghost", "preview", null).await().indefinitely());
        assertEquals("modbus device config not found", ex.getMessage());
    }

    @Test
    void changeLifecycleDevelopmentToPreviewUpdates() {
        ModbusConfig existing = devConfig();
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        Person who = Person.of("acc2", "小红");
        ModbusConfig updated = service.changeLifecycle(USER, "c1", "preview", who).await().indefinitely();
        assertEquals("preview", updated.lifecycle);
        assertEquals(who, updated.updater);
        verify(repo).replace(eq(existing));
    }

    @Test
    void changeLifecyclePreviewToReleasedAllowed() {
        ModbusConfig existing = devConfig();
        existing.lifecycle = "preview";
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        ModbusConfig updated = service.changeLifecycle(USER, "c1", "released", null).await().indefinitely();
        assertEquals("released", updated.lifecycle);
    }

    @Test
    void changeLifecycleReleasedToDevelopmentUpdates() {
        ModbusConfig existing = devConfig();
        existing.lifecycle = "released";
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        ModbusConfig updated = service.changeLifecycle(USER, "c1", "development", null).await().indefinitely();
        assertEquals("development", updated.lifecycle);
    }
}
